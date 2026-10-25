#!/usr/bin/env bash
# Laptop side of verify-putio-android: copy this worktree to zebroid-devbox, run
# one command there, bring the run's evidence home, remove the copy.
#
#   devbox.sh sync   <run>            copy the worktree to the devbox copy for <run>
#   devbox.sh run    <run> '<command>' run <command> with bash in that copy; exits with its status.
#                                     It sees $RUN, $V (the skill's scripts dir), $SERIAL (once
#                                     session.sh up ran) and $ANDROID_HOME.
#   devbox.sh pull   <run>            copy the copy's .evidence/<run>/ to this worktree's .evidence/<run>/
#   devbox.sh remove <run>            delete the copy (refuses while .evidence/<run>/run.env names a live AVD)
#
# <run> names the copy, the AVD and the evidence directory: [A-Za-z0-9._-]+.
# Environment: PUTIO_VERIFY_HOST (default zebroid-devbox),
# PUTIO_VERIFY_SDK (default /opt/homebrew/share/android-commandlinetools).
set -euo pipefail

HOST="${PUTIO_VERIFY_HOST:-zebroid-devbox}"
SDK="${PUTIO_VERIFY_SDK:-/opt/homebrew/share/android-commandlinetools}"
REPO="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"

die() { echo "devbox.sh: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; run="${2:-}"
[[ -n "${cmd}" && -n "${run}" ]] || usage
[[ "${run}" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid run name '${run}'"
REMOTE_DIR="projects/putdotio/putio-android/.claude/worktrees/${run}"

# Tailscale SSH to a Mac always exits 0, so the status travels in-band.
remote() {
  local script="$1" out status
  out="$(mktemp)"
  # stdin stays closed: adb shell would otherwise read the caller's terminal.
  ssh -o BatchMode=yes "${HOST}" "bash -c $(printf '%q' "${script}"); echo __VERIFY_EXIT=\$?" </dev/null | tee "${out}"
  status="$(grep -E '^__VERIFY_EXIT=[0-9]+$' "${out}" | tail -1 | cut -d= -f2)"
  rm -f "${out}"
  [[ -n "${status}" ]] || die "no exit status from ${HOST}; the connection dropped"
  return "${status}"
}

case "${cmd}" in
  sync)
    revision="$(git -C "${REPO}" rev-parse HEAD)"
    dirty="$(git -C "${REPO}" status --short | wc -l | tr -d ' ')"
    remote "mkdir -p ~/${REMOTE_DIR}" >/dev/null
    rsync -a --delete \
      --exclude .git --exclude build/ --exclude .gradle/ --exclude .kotlin/ --exclude .evidence/ \
      --exclude local.properties --exclude core/design/brand-fonts/ --exclude node_modules/ \
      "${REPO}/" "${HOST}:${REMOTE_DIR}/"
    # Own .git: oxfmt would otherwise read the parent checkout's .gitignore.
    remote "cd ~/${REMOTE_DIR} && { [ -d .git ] || git init -q; } && printf 'sdk.dir=%s\n' '${SDK}' > local.properties \
      && mkdir -p .evidence/${run} && printf 'revision=%s\nuncommitted_paths=%s\n' '${revision}' '${dirty}' > .evidence/${run}/revision.txt \
      && ./scripts/sync-brand-fonts.sh >/dev/null && echo 'synced ${run} at ${revision} (${dirty} uncommitted paths)'"
    ;;
  run)
    command="${3:-}"; [[ -n "${command}" ]] || usage
    remote "cd ~/${REMOTE_DIR} && export ANDROID_HOME='${SDK}' RUN='${run}' V=.claude/skills/verify-putio-android/scripts \
      && export SERIAL=\"\$(sed -n 's/^SERIAL=//p' .evidence/${run}/run.env 2>/dev/null | tail -1)\" && ${command}"
    ;;
  pull)
    mkdir -p "${REPO}/.evidence/${run}"
    rsync -a "${HOST}:${REMOTE_DIR}/.evidence/${run}/" "${REPO}/.evidence/${run}/"
    echo "EVIDENCE ${REPO}/.evidence/${run}"
    ;;
  remove)
    remote "cd ~/${REMOTE_DIR} 2>/dev/null || exit 0
      if [ -f .evidence/${run}/run.env ] && grep -q '^STATE=up$' .evidence/${run}/run.env; then
        echo 'run ${run} still has its emulator; run session.sh down ${run} first' >&2; exit 1
      fi
      cd .. && rm -rf './${run}' && echo 'removed devbox copy ${run}'"
    ;;
  *) usage ;;
esac
