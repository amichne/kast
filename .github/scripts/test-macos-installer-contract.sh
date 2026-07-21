#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-contract.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

bundle="$scratch/bundle"
log="$scratch/setup.log"
codex_log="$scratch/codex.log"
mkdir -p "$bundle/bin" "$scratch/bin"
bundle="$(cd -- "$bundle" && pwd -P)"
cat >"$bundle/bin/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${KAST_INSTALL_TEST_LOG:?}"
SH
chmod +x "$bundle/bin/kast"

cat >"$scratch/bin/codex" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${KAST_INSTALL_TEST_CODEX_LOG:?}"
SH
chmod +x "$scratch/bin/codex"

PATH="$scratch/bin:$PATH" KAST_INSTALL_TEST_LOG="$log" \
  KAST_INSTALL_TEST_CODEX_LOG="$codex_log" KAST_HOME="$scratch/home" \
  "$repo_root/install.sh" --source "$bundle" >/dev/null

grep -Fqx -- "setup --source $bundle" "$log"
grep -Fqx -- "plugin marketplace add amichne/kast-marketplace --ref main --json" "$codex_log"
grep -Fqx -- "plugin add kast@kast --json" "$codex_log"
grep -Fq -- 'kast setup --source <bundle>' "$repo_root/install.sh"
! grep -Eiq -- 'homebrew|\bbrew\b|kast machine|kast repair|\.local/bin' "$repo_root/install.sh"
bash -n "$repo_root/install.sh"

printf '%s\n' 'cross-platform setup bootstrap contract passed'
