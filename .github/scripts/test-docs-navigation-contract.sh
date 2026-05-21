#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"

python3 - "$repo_root" <<'PY'
import json
import sys
import tomllib
from pathlib import Path

repo_root = Path(sys.argv[1])
zensical_path = repo_root / "zensical.toml"
mintlify_path = repo_root / "docs" / "docs.json"

zensical = tomllib.loads(zensical_path.read_text())
mintlify = json.loads(mintlify_path.read_text())


def normalize_page(page: str) -> str:
    return page.removesuffix(".md")


def zensical_groups(nav):
    groups = []
    for item in nav:
        if len(item) != 1:
            raise AssertionError(f"Zensical nav item must have one label: {item!r}")
        label, value = next(iter(item.items()))
        if isinstance(value, str):
            groups.append({"group": label, "pages": [normalize_page(value)]})
            continue
        pages = []
        for child in value:
            if len(child) != 1:
                raise AssertionError(f"Zensical child item must have one label: {child!r}")
            _, child_page = next(iter(child.items()))
            pages.append(normalize_page(child_page))
        groups.append({"group": label, "pages": pages})
    return groups


expected = zensical_groups(zensical["project"]["nav"])
actual = mintlify["navigation"]["groups"]

if actual != expected:
    print("docs/docs.json navigation must mirror zensical.toml", file=sys.stderr)
    print("expected:", json.dumps(expected, indent=2), file=sys.stderr)
    print("actual:", json.dumps(actual, indent=2), file=sys.stderr)
    sys.exit(1)

print("Docs navigation contract passed")
PY
