#!/usr/bin/env bash
# Bootstrap the Android toolchain from a clean checkout.
#
# Machine preconditions (everything else is installed by this script):
#   - macOS or Linux with a JDK 21 on PATH (this repo pins `.java-version` 21;
#     `mise install` or Homebrew temurin@21 both work)
#   - Homebrew on macOS if the Android cmdline-tools are not yet installed
#   - Network access for SDK package downloads (~3 GB on first run)
#
# Installs: platform/build-tools for the compileSdk, emulator, phone + TV
# system images, and the two reusable AVDs. Writes local.properties.
# Idempotent: safe to re-run; already-installed packages are skipped.
#
#   scripts/bootstrap.sh [--profile phone|tv]
#
# --profile provisions one emulator surface for a narrow CI or local proof.
# Without it, bootstrap provisions both surfaces.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

COMPILE_SDK_PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;37.0.0"

profiles=(phone tv)
if [[ $# -gt 0 ]]; then
  [[ $# -eq 2 && "$1" == "--profile" ]] || die "usage: scripts/bootstrap.sh [--profile phone|tv]"
  case "$2" in
    phone|tv) profiles=("$2") ;;
    *) die "unknown emulator profile '$2' (expected phone|tv)" ;;
  esac
fi

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
packages=("platform-tools" "emulator" "${COMPILE_SDK_PLATFORM}" "${BUILD_TOOLS}")
for profile in "${profiles[@]}"; do
  packages+=("$(image_for "${profile}")")
done
for pkg in "${packages[@]}"; do
  [[ -d "${SDK_ROOT}/$(echo "${pkg}" | tr ';' '/')" ]] || packages_missing=1
done

if [[ "${packages_missing}" == "1" ]]; then
  log "accepting SDK licenses"
  # `yes` dies with SIGPIPE when sdkmanager exits first; don't let pipefail
  # turn that into a bootstrap failure.
  (yes || true) | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null

  log "installing SDK packages (first run downloads ~3 GB)"
  packages_installed=0
  for attempt in 1 2 3; do
    if "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --install "${packages[@]}"; then
      packages_installed=1
      break
    fi
    log "SDK package install attempt ${attempt}/3 failed"
  done
  [[ "${packages_installed}" == "1" ]] || die "SDK package install failed after 3 attempts"

  for pkg in "${packages[@]}"; do
    [[ -d "${SDK_ROOT}/$(echo "${pkg}" | tr ';' '/')" ]] || die "package ${pkg} missing after install"
  done
else
  log "all SDK packages already installed; skipping sdkmanager"
fi

# ffprobe backs the evidence integrity checks and prove.sh's pixel gate.
if ! command -v ffprobe >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    log "installing ffmpeg (ffprobe) for evidence verification"
    brew install ffmpeg
  elif command -v apt-get >/dev/null 2>&1; then
    elevate=()
    if [[ "$(id -u)" -ne 0 ]]; then
      command -v sudo >/dev/null 2>&1 || die "ffprobe missing and sudo unavailable; install ffmpeg and re-run"
      elevate=(sudo)
    fi
    log "installing ffmpeg (ffprobe) for evidence verification"
    "${elevate[@]}" apt-get update
    "${elevate[@]}" apt-get install -y ffmpeg
  else
    die "ffprobe missing; install ffmpeg and re-run"
  fi
  command -v ffprobe >/dev/null 2>&1 || die "ffmpeg install did not provide ffprobe"
fi

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

for profile in "${profiles[@]}"; do
  "${REPO_ROOT}/scripts/emulator.sh" create "${profile}"
done

log "bootstrap complete"
log "next: ./gradlew verify :app:assembleMobileProductionDebug :app:assembleTvProductionDebug"
