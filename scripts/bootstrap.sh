#!/usr/bin/env bash
# Bootstrap the Android toolchain from a clean checkout.
#
# Machine preconditions (everything else is installed by this script):
#   - macOS or Linux with a JDK 21 on PATH (this repo pins `.java-version` 21;
#     `mise install` or Homebrew temurin@21 both work)
#   - Homebrew on macOS if the Android cmdline-tools are not yet installed
#   - Network access for several GB of SDK packages on first run
#
# Installs: platform/build-tools for the compileSdk, emulator, the API 37
# Google Play phone image, the API 36 Android TV image, and both reusable
# AVDs. Writes local.properties.
# Idempotent: safe to re-run; already-installed packages are skipped.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

COMPILE_SDK_PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;37.0.0"

log "checking JDK"
java -version >/dev/null 2>&1 || die "no java on PATH; install JDK 21 (mise install / brew install temurin@21)"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "${JAVA_MAJOR}" -ge 21 ]] || die "JDK 21+ required, found ${JAVA_MAJOR}"

if ! SDK_ROOT="$(resolve_sdk_root)"; then
  log "no Android SDK root found; installing cmdline-tools via Homebrew"
  command -v brew >/dev/null || die "Homebrew not found; install android-commandlinetools manually and re-run"
  brew install --cask android-commandlinetools
  SDK_ROOT="$(resolve_sdk_root)" || die "cmdline-tools install did not produce a usable SDK root"
fi
SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
[[ -x "${SDKMANAGER}" ]] || die "sdkmanager missing under ${SDK_ROOT}"
log "SDK root: ${SDK_ROOT}"

packages_missing=0
for pkg in "platform-tools" "emulator" "${COMPILE_SDK_PLATFORM}" "${BUILD_TOOLS}" "$(phone_image)" "$(tv_image)"; do
  [[ -d "${SDK_ROOT}/$(echo "${pkg}" | tr ';' '/')" ]] || packages_missing=1
done

if [[ "${packages_missing}" == "1" ]]; then
  log "accepting SDK licenses"
  # `yes` dies with SIGPIPE when sdkmanager exits first; don't let pipefail
  # turn that into a bootstrap failure.
  (yes || true) | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null

  log "installing SDK packages (first run downloads several GB)"
  "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --install \
    "platform-tools" \
    "emulator" \
    "${COMPILE_SDK_PLATFORM}" \
    "${BUILD_TOOLS}" \
    "$(phone_image)" \
    "$(tv_image)"

  for pkg in "platform-tools" "emulator" "${COMPILE_SDK_PLATFORM}" "${BUILD_TOOLS}" "$(phone_image)" "$(tv_image)"; do
    [[ -d "${SDK_ROOT}/$(echo "${pkg}" | tr ';' '/')" ]] || die "package ${pkg} missing after install"
  done
else
  log "all SDK packages already installed; skipping sdkmanager"
fi

# FFmpeg backs the canonical evidence regression gate as well as runtime
# integrity checks, so every supported bootstrap path must provision it.
if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    log "installing ffmpeg for evidence verification"
    brew install ffmpeg
  elif command -v apt-get >/dev/null 2>&1; then
    apt_prefix=()
    if [[ "$(id -u)" -ne 0 ]]; then
      command -v sudo >/dev/null 2>&1 || die "ffmpeg missing; install it with your system package manager and re-run"
      apt_prefix=(sudo)
    fi
    log "installing ffmpeg for evidence verification"
    "${apt_prefix[@]}" apt-get update
    "${apt_prefix[@]}" apt-get install -y ffmpeg
  else
    die "ffmpeg missing; install it with your system package manager and re-run"
  fi
fi
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg install did not put ffmpeg on PATH"

# emulator.sh needs lsof to find a free console port; macOS ships it, Debian images do not.
if ! command -v lsof >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt_prefix=()
    if [[ "$(id -u)" -ne 0 ]]; then
      command -v sudo >/dev/null 2>&1 || die "lsof missing; install it with your system package manager and re-run"
      apt_prefix=(sudo)
    fi
    log "installing lsof for emulator port discovery"
    "${apt_prefix[@]}" apt-get update
    "${apt_prefix[@]}" apt-get install -y lsof
  else
    die "lsof missing; install it with your system package manager and re-run"
  fi
fi
command -v lsof >/dev/null 2>&1 || die "lsof install did not put lsof on PATH"
command -v ffprobe >/dev/null 2>&1 || die "ffmpeg install did not put ffprobe on PATH"
ffmpeg_filters="$(ffmpeg -hide_banner -filters 2>/dev/null)"
grep -q ' freezedetect ' <<<"${ffmpeg_filters}" || die "ffmpeg is missing the freezedetect filter"
ffmpeg_encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
grep -q ' libx264 ' <<<"${ffmpeg_encoders}" || die "ffmpeg is missing the libx264 encoder"

log "writing local.properties"
SDK_KOTLIN_DEFAULT="$(cd "${REPO_ROOT}/.." 2>/dev/null && pwd)/putio-sdk-kotlin"
if [[ ! -f "${REPO_ROOT}/local.properties" ]]; then
  {
    echo "sdk.dir=${SDK_ROOT}"
    if [[ -d "${SDK_KOTLIN_DEFAULT}" ]]; then
      echo "putioSdkKotlinPath=${SDK_KOTLIN_DEFAULT}"
    fi
  } > "${REPO_ROOT}/local.properties"
elif grep -q '^sdk\.dir=' "${REPO_ROOT}/local.properties"; then
  # Refresh a stale sdk.dir (moved SDK, changed ANDROID_HOME) instead of
  # leaving Gradle pointed somewhere bootstrap did not provision.
  tmp="$(mktemp)"
  awk -v line="sdk.dir=${SDK_ROOT}" '/^sdk\.dir=/ { print line; next } { print }' \
    "${REPO_ROOT}/local.properties" > "${tmp}" && mv "${tmp}" "${REPO_ROOT}/local.properties"
else
  echo "sdk.dir=${SDK_ROOT}" >> "${REPO_ROOT}/local.properties"
fi
# settings.gradle.kts unconditionally includes this composite build; without
# it every Gradle command fails, so an absent checkout is a bootstrap failure,
# not a warning.
SDK_KOTLIN_PATH="$(sed -n 's/^putioSdkKotlinPath=//p' "${REPO_ROOT}/local.properties" | head -1)"
[[ -n "${SDK_KOTLIN_PATH}" ]] || SDK_KOTLIN_PATH="${SDK_KOTLIN_DEFAULT}"
if [[ ! -d "${SDK_KOTLIN_PATH}" ]]; then
  die "putio-sdk-kotlin checkout missing at ${SDK_KOTLIN_PATH}; run: git clone git@github.com:putdotio/putio-sdk-kotlin.git '${SDK_KOTLIN_PATH}' (or point putioSdkKotlinPath in local.properties at an existing checkout), then re-run bootstrap"
fi

for profile in phone tv; do
  "${REPO_ROOT}/scripts/emulator.sh" create "${profile}"
done

log "bootstrap complete"
log "next: ./gradlew verify :app:assembleMobileProductionDebug :app:assembleTvProductionDebug"
