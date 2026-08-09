#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"

python3 - "$repo_root" <<'PY'
import sys
import json
import tomllib
from pathlib import Path

root = Path(sys.argv[1])
config = tomllib.loads((root / "zensical.toml").read_text())
project = config["project"]

if project.get("docs_dir") != "docs/public":
    raise SystemExit("Zensical must publish only docs/public")
if "extra_javascript" in project:
    raise SystemExit("public documentation must not load repository architecture modules")

expected = [
    ("Start", ["index.md"]),
    (
        "Repository questions",
        [
            "questions/resolve-declaration.md",
            "questions/dependents.md",
            "questions/value-flow.md",
            "questions/contract-change.md",
            "questions/verify-coverage.md",
        ],
    ),
    ("Trust the evidence", ["concepts/evidence-boundaries.md"]),
    ("CLI contract", ["reference/cli.md"]),
]

actual = []
for item in project["nav"]:
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
        if not (root / project["docs_dir"] / page).is_file():
            raise SystemExit(f"navigation target does not exist: {page}")

mintlify_path = root / project["docs_dir"] / "docs.json"
if not mintlify_path.is_file():
    raise SystemExit("Mintlify must be configured from docs/public/docs.json")

mintlify = json.loads(mintlify_path.read_text())
if mintlify.get("$schema") != "https://mintlify.com/docs.json":
    raise SystemExit("Mintlify must use the official docs.json schema")
if mintlify.get("theme") != "mint":
    raise SystemExit("Mintlify must use the mint theme")
if mintlify.get("name") != "Kast":
    raise SystemExit("Mintlify must identify the documentation as Kast")
if mintlify.get("colors", {}).get("primary") != "#536DFE":
    raise SystemExit("Mintlify must use Kast's public documentation accent color")

mintlify_groups = mintlify.get("navigation", {}).get("groups")
if not isinstance(mintlify_groups, list):
    raise SystemExit("Mintlify navigation must use explicit problem-led groups")

mintlify_actual = []
for group in mintlify_groups:
    label = group.get("group")
    pages = group.get("pages")
    if not isinstance(label, str) or not isinstance(pages, list):
        raise SystemExit(f"invalid Mintlify navigation group: {group!r}")
    mintlify_actual.append((label, [f"{page}.md" for page in pages]))

if mintlify_actual != expected:
    raise SystemExit(
        "Mintlify navigation differs from the public documentation contract"
        f"\nexpected={expected!r}\nactual={mintlify_actual!r}"
    )

print("Docs navigation contract passed")
PY
