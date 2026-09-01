#!/usr/bin/env bash
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/publish-evidence.sh"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

fixture="${tmpdir}/proof.png"
printf 'validated fixture' > "${fixture}"
video_fixture="${tmpdir}/proof.mp4"
printf 'validated video fixture' > "${video_fixture}"

fake_bin="${tmpdir}/bin"
mkdir -p "${fake_bin}"

write_fake_attach() {
  cat > "${fake_bin}/attach" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" > "${ATTACH_ARGS_FILE:?}"
if [[ "${ATTACH_FAIL:-0}" == "1" ]]; then
  echo 'verification code ABCD-EFGH token att_secret' >&2
  exit 9
fi
if [[ "${ATTACH_INVALID:-0}" == "1" ]]; then
  echo 'invalid metadata att_secret' >&2
  exit 2
fi
if [[ "${ATTACH_MALFORMED:-}" == "preview" ]]; then
  echo 'https://attach.uinaf.dev/p/preview att_secret'
  exit 0
fi
if [[ "${ATTACH_MALFORMED:-}" == "markdown" ]]; then
  echo 'prefix ![proof](https://attach.uinaf.dev/o/object) att_secret'
  exit 0
fi
echo 'successful diagnostic that must stay off stdout' >&2
case " $* " in
  *' --markdown '*)
    case "$2" in
      *.mp4) echo '[proof.mp4](https://attach.uinaf.dev/o/object_key_1234567890)' ;;
      *) echo '![proof.png](https://attach.uinaf.dev/o/object_key_1234567890)' ;;
    esac
    ;;
  *) echo 'https://attach.uinaf.dev/p/preview_key_1234567890' ;;
esac
EOF
  chmod +x "${fake_bin}/attach"
}

write_fake_attach
args_file="${tmpdir}/args"
output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --repo putdotio/putio-android --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/preview_key_1234567890" ]]
[[ "$(<"${args_file}")" == "put ${fixture} --repo putdotio/putio-android --pr 50" ]]

output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50 --markdown)"
[[ "${output}" == '![proof.png](https://attach.uinaf.dev/o/object_key_1234567890)' ]]
grep -q -- '--markdown' "${args_file}"

output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${video_fixture}" --pr 50 --markdown)"
[[ "${output}" == '[proof.mp4](https://attach.uinaf.dev/o/object_key_1234567890)' ]]

PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50 --dry-run >/dev/null
grep -q -- '--dry-run' "${args_file}"

for option in --repo --pr; do
  set +e
  PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
    "${SCRIPT}" "${fixture}" "${option}" >"${tmpdir}/missing-value.out" 2>&1
  status=$?
  set -e
  [[ "${status}" -eq 64 ]]
  grep -qF "publish-evidence: ${option} requires a value" "${tmpdir}/missing-value.out"
done

unsafe_markdown="${tmpdir}/proof\\"
printf 'unsafe fixture' > "${unsafe_markdown}"
rm -f "${args_file}"
if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${unsafe_markdown}" --pr 50 --markdown >"${tmpdir}/unsafe-markdown.out" 2>&1; then
  echo "expected unsafe Markdown filename to fail" >&2
  exit 1
fi
grep -q 'cannot be represented safely in Markdown' "${tmpdir}/unsafe-markdown.out"
[[ ! -e "${args_file}" ]]

for quarantined_name in proof.png.corrupt proof.black.png proof.mp4.idle proof.unverified.png; do
  quarantined="${tmpdir}/${quarantined_name}"
  printf 'rejected fixture' > "${quarantined}"
  rm -f "${args_file}"
  if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
    "${SCRIPT}" "${quarantined}" --pr 50 >"${tmpdir}/quarantined.out" 2>&1; then
    echo "expected quarantined evidence to fail: ${quarantined_name}" >&2
    exit 1
  fi
  grep -qF "refusing to publish quarantined evidence: ${quarantined}" "${tmpdir}/quarantined.out"
  [[ ! -e "${args_file}" ]]
done

output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  ATTACH_API_BASE="https://attach.uinaf.dev/" \
  "${SCRIPT}" "${fixture}" --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/preview_key_1234567890" ]]

rm "${fake_bin}/attach"
cat > "${fake_bin}/gh" <<'EOF'
#!/usr/bin/env bash
if [[ "$1 $2" == "attach help" ]]; then exit 0; fi
printf '%s\n' "$*" > "${ATTACH_ARGS_FILE:?}"
echo 'https://attach.uinaf.dev/p/gh_preview_key_123456'
EOF
chmod +x "${fake_bin}/gh"
output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/gh_preview_key_123456" ]]
[[ "$(<"${args_file}")" == "attach put ${fixture} --repo putdotio/putio-android --pr 50" ]]

empty_bin="${tmpdir}/empty-bin"
mkdir -p "${empty_bin}"
if PATH="${empty_bin}" /bin/bash "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/missing.out" 2>&1; then
  echo "expected missing Attach CLI to fail" >&2
  exit 1
fi
grep -q 'Attach CLI not found' "${tmpdir}/missing.out"

if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  ATTACH_API_BASE="https://attach.example.test" \
  "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/client.out" 2>&1; then
  echo "expected missing custom client id to fail" >&2
  exit 1
fi
grep -q 'requires ATTACH_GITHUB_CLIENT_ID' "${tmpdir}/client.out"

write_fake_attach
if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" ATTACH_INVALID=1 \
  "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/invalid.out" 2>&1; then
  echo "expected invalid Attach metadata to fail" >&2
  exit 1
fi
grep -q 'rejected the file or metadata (exit 2)' "${tmpdir}/invalid.out"
if grep -q 'att_secret' "${tmpdir}/invalid.out"; then
  echo "Attach metadata failure leaked credential-shaped output" >&2
  exit 1
fi

if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" ATTACH_FAIL=1 \
  "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/failure.out" 2>&1; then
  echo "expected Attach failure to propagate" >&2
  exit 1
fi
grep -q 'Attach upload failed (exit 9)' "${tmpdir}/failure.out"
if grep -Eq 'ABCD-EFGH|att_secret' "${tmpdir}/failure.out"; then
  echo "Attach failure leaked device-code or credential-shaped output" >&2
  exit 1
fi

if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" ATTACH_FAIL=1 \
  bash -x "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/xtrace.out" 2>&1; then
  echo "expected traced Attach failure to propagate" >&2
  exit 1
fi
if grep -Eq 'ABCD-EFGH|att_secret' "${tmpdir}/xtrace.out"; then
  echo "xtrace leaked suppressed Attach output" >&2
  exit 1
fi

for malformed in preview markdown; do
  malformed_args=("${fixture}" --pr 50)
  [[ "${malformed}" == "markdown" ]] && malformed_args+=(--markdown)
  if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
    ATTACH_MALFORMED="${malformed}" \
    "${SCRIPT}" "${malformed_args[@]}" >"${tmpdir}/malformed-${malformed}.out" 2>&1; then
    echo "expected malformed successful ${malformed} output to fail" >&2
    exit 1
  fi
  if grep -q 'att_secret' "${tmpdir}/malformed-${malformed}.out"; then
    echo "malformed successful ${malformed} output leaked arbitrary stdout" >&2
    exit 1
  fi
done

echo "Attach evidence publishing tests passed"
