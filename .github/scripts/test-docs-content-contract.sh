#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"
manifest="${repo_root}/cli-rs/resources/codex-plugin/plugins/kast/.codex-plugin/plugin.json"

require_contains() {
  grep -Fq -- "$2" "$1" || die "missing '$2' in $1"
}

require_absent() {
  [[ ! -e "$1" ]] || die "obsolete public path exists: $1"
}

require_not_contains() {
  ! grep -R -Fq --include='*.md' -- "$2" "$1" || die "found '$2' under $1"
}

expected_pages=(
  "design/operating-model.md"
  "index.md"
  "install/macos.md"
  "reference/codex-plugin.md"
  "troubleshoot.md"
  "use/codex.md"
)
actual_pages="$(find "$docs_root" -type f -name '*.md' -print | sed "s#${docs_root}/##" | sort)"
expected_page_lines="$(printf '%s\n' "${expected_pages[@]}" | sort)"
[[ "$actual_pages" == "$expected_page_lines" ]] || {
  printf 'expected pages:\n%s\nactual pages:\n%s\n' "$expected_page_lines" "$actual_pages" >&2
  die "public Markdown set differs from the Codex workstation journey"
}

require_absent "${docs_root}/privacy.md"
require_absent "${docs_root}/terms.md"
require_absent "${docs_root}/install/headless-linux.md"
require_absent "${docs_root}/install/codex.md"
require_absent "${docs_root}/learn"
require_absent "${docs_root}/assets/demo"

installer='/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"'
feed='https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml'
require_contains "$readme" "$installer"
require_contains "$readme" "$feed"
require_contains "$readme" "creates no global Kast skill"
require_contains "${docs_root}/install/macos.md" "$installer"
require_contains "${docs_root}/install/macos.md" "$feed"
require_contains "${docs_root}/install/macos.md" "start a new Codex task"
require_contains "${docs_root}/use/codex.md" "without operating Kast"
require_contains "${docs_root}/reference/codex-plugin.md" "sole agent-facing component"
require_contains "${docs_root}/design/operating-model.md" "does not project a global skill"
require_contains "${docs_root}/troubleshoot.md" "obsolete Kast-owned symlink"

require_not_contains "$docs_root" "kast agent"
require_not_contains "$docs_root" "--output"
require_not_contains "$docs_root" "Headless Linux"
require_not_contains "$docs_root" "codex plugin marketplace add"
! grep -Fq 'privacyPolicyURL' "$manifest" || die "Codex manifest still publishes a policy page"
! grep -Fq 'termsOfServiceURL' "$manifest" || die "Codex manifest still publishes a service page"

python3 - "$docs_root" "${expected_pages[@]}" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
for relative in sys.argv[2:]:
    if relative == "index.md":
        continue
    path = root / relative
    text = path.read_text()
    if not text.startswith("---\n"):
        raise SystemExit(f"{relative}: missing frontmatter")
    try:
        frontmatter = text.split("---\n", 2)[1]
    except IndexError:
        raise SystemExit(f"{relative}: unterminated frontmatter")
    if not any(line.startswith("type:") and line.removeprefix("type:").strip() for line in frontmatter.splitlines()):
        raise SystemExit(f"{relative}: missing non-empty OKF type")
    if "code_sources:\n" not in frontmatter:
        raise SystemExit(f"{relative}: missing code_sources")
PY

printf '%s\n' "Docs content contract passed"
