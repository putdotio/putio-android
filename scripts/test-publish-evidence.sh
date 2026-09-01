#!/usr/bin/env bash
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/publish-evidence.sh"
PROVE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/prove.sh"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

fixture="${tmpdir}/proof.png"
printf 'validated fixture' > "${fixture}"

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
echo 'successful diagnostic that must stay off stdout' >&2
case " $* " in
  *' --markdown '*) echo '![proof](https://attach.uinaf.dev/o/object)' ;;
  *) echo 'https://attach.uinaf.dev/p/preview' ;;
esac
EOF
  chmod +x "${fake_bin}/attach"
}

write_fake_attach
args_file="${tmpdir}/args"
output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --repo putdotio/putio-android --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/preview" ]]
[[ "$(<"${args_file}")" == "put ${fixture} --repo putdotio/putio-android --pr 50" ]]

output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50 --markdown)"
[[ "${output}" == '![proof](https://attach.uinaf.dev/o/object)' ]]
grep -q -- '--markdown' "${args_file}"

PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50 --dry-run >/dev/null
grep -q -- '--dry-run' "${args_file}"

output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  ATTACH_API_BASE="https://attach.uinaf.dev/" \
  "${SCRIPT}" "${fixture}" --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/preview" ]]

rm "${fake_bin}/attach"
cat > "${fake_bin}/gh" <<'EOF'
#!/usr/bin/env bash
if [[ "$1 $2" == "attach help" ]]; then exit 0; fi
printf '%s\n' "$*" > "${ATTACH_ARGS_FILE:?}"
echo 'https://attach.uinaf.dev/p/gh-preview'
EOF
chmod +x "${fake_bin}/gh"
output="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  "${SCRIPT}" "${fixture}" --pr 50)"
[[ "${output}" == "https://attach.uinaf.dev/p/gh-preview" ]]
[[ "$(<"${args_file}")" == "attach put ${fixture} --repo putdotio/putio-android --pr 50" ]]

if PATH="/usr/bin:/bin" "${SCRIPT}" "${fixture}" --pr 50 >"${tmpdir}/missing.out" 2>&1; then
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

# Invoke prove.sh's real argument and marker boundary without an emulator.
prove_out="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  PUTIO_PROVE_TEST_CAPTURE="${fixture}" \
  "${PROVE}" mobile --pr 50 --repo putdotio/putio-android)"
[[ "${prove_out}" == $'EVIDENCE '"${fixture}"$'\nEVIDENCE_URL https://attach.uinaf.dev/p/preview' ]]
[[ "$(<"${args_file}")" == "put ${fixture} --repo putdotio/putio-android --pr 50" ]]

prove_markdown="$(PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" \
  PUTIO_PROVE_TEST_CAPTURE="${fixture}" \
  "${PROVE}" mobile --pr 50 --markdown)"
[[ "${prove_markdown}" == $'EVIDENCE '"${fixture}"$'\nEVIDENCE_MARKDOWN ![proof](https://attach.uinaf.dev/o/object)' ]]

if PATH="${fake_bin}:/usr/bin:/bin" ATTACH_ARGS_FILE="${args_file}" ATTACH_FAIL=1 \
  PUTIO_PROVE_TEST_CAPTURE="${fixture}" \
  "${PROVE}" mobile --pr 50 >"${tmpdir}/prove-failure.out" 2>&1; then
  echo "expected prove publication failure to propagate" >&2
  exit 1
fi
grep -qF "EVIDENCE ${fixture}" "${tmpdir}/prove-failure.out"
if grep -q '^EVIDENCE_URL ' "${tmpdir}/prove-failure.out"; then
  echo "failed prove publication emitted a URL marker" >&2
  exit 1
fi
[[ -f "${fixture}" ]] || { echo "prove publication failure removed local evidence" >&2; exit 1; }

echo "Attach evidence publishing tests passed"
