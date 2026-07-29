#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-contract.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

bundle="$scratch/bundle"
log="$scratch/setup.log"
kagent_log="$scratch/kagent.log"
mkdir -p "$bundle/bin" "$scratch/bin"
bundle="$(cd -- "$bundle" && pwd -P)"
cat >"$bundle/bin/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_INSTALL_TEST_LOG:?}"
mkdir -p "${KAST_HOME:?}/current/bin" "${HOME:?}/.local/bin"
cp "${KAST_INSTALL_TEST_KAGENT_SOURCE:?}" "${KAST_HOME}/current/bin/kagent"
cp "${KAST_INSTALL_TEST_KAGENT_SOURCE}" "${HOME}/.local/bin/kagent"
chmod 755 "${KAST_HOME}/current/bin/kagent" "${HOME}/.local/bin/kagent"
SH
chmod +x "$bundle/bin/kast"

cat >"$bundle/bin/kagent" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_INSTALL_TEST_KAGENT_LOG:?}"
[[ "${KAST_INSTALL_TEST_KAGENT_FAIL:-0}" != "1" ]]
SH
chmod +x "$bundle/bin/kagent"

for harness in codex claude copilot; do
  cat >"$scratch/bin/$harness" <<'SH'
#!/usr/bin/env bash
exit 0
SH
  chmod +x "$scratch/bin/$harness"
done

run_installer() {
  : >"$log"
  : >"$kagent_log"
  HOME="$scratch/user" PATH="$scratch/bin:$PATH" NONINTERACTIVE=1 \
    KAST_HOME="$scratch/home" KAST_INSTALL_TEST_LOG="$log" \
    KAST_INSTALL_TEST_KAGENT_LOG="$kagent_log" \
    KAST_INSTALL_TEST_KAGENT_SOURCE="$bundle/bin/kagent" \
    KAST_INSTALL_TEST_KAGENT_FAIL="${KAST_INSTALL_TEST_KAGENT_FAIL:-0}" \
    "$repo_root/install.sh" --source "$bundle" "$@" >"$scratch/stdout" 2>"$scratch/stderr"
}

assert_selected_harnesses() {
  local expected=" $* " invocation harness line_count
  line_count="$(wc -l <"$kagent_log" | tr -d ' ')"
  if [[ "$line_count" != "1" ]]; then
    printf 'expected one kagent resource-install invocation, found %s\n' "$line_count" >&2
    return 1
  fi
  invocation="$(<"$kagent_log")"
  for harness in codex claude copilot; do
    if [[ "$expected" == *" $harness "* ]]; then
      if [[ "$invocation" != *"--harness $harness"* ]]; then
        printf 'kagent invocation omitted selected harness %s: %s\n' "$harness" "$invocation" >&2
        return 1
      fi
    elif [[ "$invocation" == *"--harness $harness"* ]]; then
      printf 'kagent invocation included unselected harness %s: %s\n' "$harness" "$invocation" >&2
      return 1
    fi
  done
}

run_installer
grep -Fqx -- "setup --source $bundle" "$log"
assert_selected_harnesses codex claude copilot

for harness in codex claude copilot; do
  run_installer --harness "$harness"
  assert_selected_harnesses "$harness"
done

run_installer --harness none
[[ ! -s "$kagent_log" ]]

if KAST_INSTALL_TEST_KAGENT_FAIL=1 run_installer; then
  printf '%s\n' 'installer must propagate an aggregated kagent provider failure' >&2
  exit 1
fi
assert_selected_harnesses codex claude copilot

! grep -Fq -- 'amichne/kast-marketplace' "$repo_root/install.sh"
! grep -Fq -- '--ref main' "$repo_root/install.sh"
! grep -Fq -- 'kast@kast' "$repo_root/install.sh"
grep -Fq -- 'kast setup --source <bundle>' "$repo_root/install.sh"
grep -Fq -- 'local bin_dir="${HOME}/.local/bin"' "$repo_root/install.sh"
grep -Fq -- 'export PATH="$HOME/.local/bin:$PATH"' "$repo_root/install.sh"
! grep -Eiq -- 'homebrew|\bbrew\b|kast machine|kast repair' "$repo_root/install.sh"
bash -n "$repo_root/install.sh"

printf '%s\n' 'cross-platform setup bootstrap contract passed'
