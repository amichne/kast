#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
docs_root="${repo_root}/docs"
readme="${repo_root}/README.md"

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
  "explanation/architecture.md"
  "explanation/compiler-evidence.md"
  "how-to/explore-kotlin-code.md"
  "how-to/install-or-update.md"
  "how-to/plan-safe-edits.md"
  "how-to/troubleshoot.md"
  "index.md"
  "reference/cli.md"
  "reference/codex-plugin.md"
  "tutorials/first-compiler-backed-task.md"
)
actual_pages="$(find "$docs_root" -type f -name '*.md' -print | sed "s#${docs_root}/##" | sort)"
expected_page_lines="$(printf '%s\n' "${expected_pages[@]}" | sort)"
[[ "$actual_pages" == "$expected_page_lines" ]] || {
  printf 'expected pages:\n%s\nactual pages:\n%s\n' "$expected_page_lines" "$actual_pages" >&2
  die "public Markdown set differs from the Codex workstation journey"
}

require_absent "${docs_root}/privacy.md"
require_absent "${docs_root}/terms.md"
require_absent "${docs_root}/install"
require_absent "${docs_root}/use"
require_absent "${docs_root}/design"
require_absent "${docs_root}/assets/demo"

installer='/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"'
require_contains "$readme" "$installer"
require_contains "$readme" "kast setup"
require_contains "$readme" "prior active release usable"
require_contains "$readme" "amichne/kast-marketplace"
require_contains "${docs_root}/how-to/install-or-update.md" "$installer"
require_contains "${docs_root}/how-to/install-or-update.md" "./gradlew refreshDevelopmentMachine"
require_contains "${docs_root}/how-to/install-or-update.md" "current/bin/kast"
require_contains "${docs_root}/tutorials/first-compiler-backed-task.md" "IdeaIndexSemanticAdmission"
require_contains "${docs_root}/how-to/explore-kotlin-code.md" "coverage is complete or limited"
require_contains "${docs_root}/how-to/plan-safe-edits.md" "one exact compiler identity"
require_contains "${docs_root}/reference/cli.md" 'kast agent'
require_contains "${docs_root}/reference/cli.md" '`toon`'
require_contains "${docs_root}/reference/codex-plugin.md" 'tracks its `main` branch independently'
require_contains "${docs_root}/explanation/architecture.md" "exact workspace"
require_contains "${docs_root}/explanation/compiler-evidence.md" "scope fingerprint"
require_contains "${docs_root}/how-to/troubleshoot.md" 'Do not edit `current`'

require_not_contains "$docs_root" "codex plugin marketplace add"
require_not_contains "$docs_root" "Homebrew"
require_not_contains "$docs_root" "kast repair"
require_not_contains "$docs_root" "kast machine"
require_not_contains "$docs_root" "raw/semantic-graph"

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
