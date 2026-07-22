#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"

python3 - "$repo_root" <<'PY'
import sys
import tomllib
from pathlib import Path

root = Path(sys.argv[1])
config = tomllib.loads((root / "zensical.toml").read_text())

expected = [
    ("Start", ["index.md"]),
    ("Tutorials", ["tutorials/first-compiler-backed-task.md"]),
    (
        "How-to guides",
        [
            "how-to/install-or-update.md",
            "how-to/explore-kotlin-code.md",
            "how-to/plan-safe-edits.md",
            "how-to/troubleshoot.md",
        ],
    ),
    ("Reference", ["reference/cli.md", "reference/codex-plugin.md"]),
    (
        "Explanation",
        ["explanation/architecture.md", "explanation/compiler-evidence.md"],
    ),
]

actual = []
for item in config["project"]["nav"]:
    if len(item) != 1:
        raise SystemExit(f"invalid navigation item: {item!r}")
    label, value = next(iter(item.items()))
    if isinstance(value, str):
        pages = [value]
    else:
        pages = [next(iter(child.values())) for child in value]
    actual.append((label, pages))

if actual != expected:
    raise SystemExit(f"unexpected navigation\nexpected={expected!r}\nactual={actual!r}")

for _, pages in actual:
    for page in pages:
        if not (root / "docs" / page).is_file():
            raise SystemExit(f"navigation target does not exist: {page}")

print("Docs navigation contract passed")
PY
