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
agent_log="$scratch/agent.log"
mkdir -p "$bundle/bin" "$scratch/bin"
bundle="$(cd -- "$bundle" && pwd -P)"
cat >"$bundle/bin/_kastctl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_INSTALL_TEST_LOG:?}"
mkdir -p "${KAST_HOME:?}/current/bin" "${HOME:?}/.local/bin"
cp "$0" "${KAST_HOME}/current/bin/_kastctl"
cp "$0" "${HOME}/.local/bin/_kastctl"
cp "${KAST_INSTALL_TEST_AGENT_SOURCE:?}" "${KAST_HOME}/current/bin/kast"
cp "${KAST_INSTALL_TEST_AGENT_SOURCE}" "${HOME}/.local/bin/kast"
chmod 755 "${KAST_HOME}/current/bin/_kastctl" "${HOME}/.local/bin/_kastctl"
chmod 755 "${KAST_HOME}/current/bin/kast" "${HOME}/.local/bin/kast"
SH
chmod +x "$bundle/bin/_kastctl"

cat >"$bundle/bin/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_INSTALL_TEST_AGENT_LOG:?}"
[[ "${KAST_INSTALL_TEST_AGENT_FAIL:-0}" != "1" ]]
SH
chmod +x "$bundle/bin/kast"

for harness in codex claude copilot; do
  cat >"$scratch/bin/$harness" <<'SH'
#!/usr/bin/env bash
exit 0
SH
  chmod +x "$scratch/bin/$harness"
done

run_installer() {
  : >"$log"
  : >"$agent_log"
  HOME="$scratch/user" PATH="$scratch/bin:$PATH" NONINTERACTIVE=1 \
    KAST_HOME="$scratch/home" KAST_INSTALL_TEST_LOG="$log" \
    KAST_INSTALL_TEST_AGENT_LOG="$agent_log" \
    KAST_INSTALL_TEST_AGENT_SOURCE="$bundle/bin/kast" \
    KAST_INSTALL_TEST_AGENT_FAIL="${KAST_INSTALL_TEST_AGENT_FAIL:-0}" \
    "$repo_root/install.sh" --source "$bundle" "$@" >"$scratch/stdout" 2>"$scratch/stderr"
}

assert_selected_harnesses() {
  local expected=" $* " invocation harness line_count
  line_count="$(wc -l <"$agent_log" | tr -d ' ')"
  if [[ "$line_count" != "1" ]]; then
    printf 'expected one Kast resource-install invocation, found %s\n' "$line_count" >&2
    return 1
  fi
  invocation="$(<"$agent_log")"
  for harness in codex claude copilot; do
    if [[ "$expected" == *" $harness "* ]]; then
      if [[ "$invocation" != *"--harness $harness"* ]]; then
        printf 'Kast invocation omitted selected harness %s: %s\n' "$harness" "$invocation" >&2
        return 1
      fi
    elif [[ "$invocation" == *"--harness $harness"* ]]; then
      printf 'Kast invocation included unselected harness %s: %s\n' "$harness" "$invocation" >&2
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
[[ ! -s "$agent_log" ]]

if KAST_INSTALL_TEST_AGENT_FAIL=1 run_installer; then
  printf '%s\n' 'installer must propagate an aggregated agent provider failure' >&2
  exit 1
fi
assert_selected_harnesses codex claude copilot

! grep -Fq -- 'amichne/kast-marketplace' "$repo_root/install.sh"
! grep -Fq -- '--ref main' "$repo_root/install.sh"
! grep -Fq -- 'kast@kast' "$repo_root/install.sh"
grep -Fq -- '_kastctl setup --source <bundle>' "$repo_root/install.sh"
grep -Fq -- 'local bin_dir="${HOME}/.local/bin"' "$repo_root/install.sh"
grep -Fq -- 'export PATH="$HOME/.local/bin:$PATH"' "$repo_root/install.sh"
! grep -Eiq -- 'homebrew|\bbrew\b|kast machine|kast repair' "$repo_root/install.sh"
! grep -Fiq -- 'kagent' "$repo_root/install.sh"
bash -n "$repo_root/install.sh"

printf '%s\n' 'cross-platform setup bootstrap contract passed'
