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
mkdir -p "$bundle/bin"
bundle="$(cd -- "$bundle" && pwd -P)"
cat >"$bundle/bin/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${KAST_INSTALL_TEST_LOG:?}"
SH
chmod +x "$bundle/bin/kast"

KAST_INSTALL_TEST_LOG="$log" KAST_HOME="$scratch/home" \
  "$repo_root/install.sh" --source "$bundle" >/dev/null

grep -Fqx -- "setup --source $bundle" "$log"
grep -Fq -- 'kast setup --source <bundle>' "$repo_root/install.sh"
! grep -Eiq -- 'homebrew|\bbrew\b|kast machine|kast repair|\.local/bin' "$repo_root/install.sh"
bash -n "$repo_root/install.sh"

printf '%s\n' 'cross-platform setup bootstrap contract passed'
