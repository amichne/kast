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
import sys
import tomllib
from pathlib import Path

repo_root = Path(sys.argv[1])
zensical_path = repo_root / "zensical.toml"

zensical = tomllib.loads(zensical_path.read_text())


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


groups = zensical_groups(zensical["project"]["nav"])
groups_by_name = {group["group"]: group["pages"] for group in groups}

required_group_order = [
    "Overview",
    "Install",
    "Use with agents",
    "Supported use cases",
    "Troubleshooting",
    "Reference",
    "Architecture",
]
actual_group_order = [group["group"] for group in groups]
if actual_group_order != required_group_order:
    print("Docs sidebar groups should stay intent-first and reader-oriented", file=sys.stderr)
    print("expected:", required_group_order, file=sys.stderr)
    print("actual:", actual_group_order, file=sys.stderr)
    sys.exit(1)

placement_checks = [
    ("Install", "getting-started/install"),
    ("Install", "getting-started/headless-linux"),
    ("Use with agents", "for-agents/install-the-skill"),
    ("Supported use cases", "supported-use-cases"),
    ("Reference", "cli-cheat-sheet"),
    ("Reference", "getting-started/backends"),
    ("Architecture", "architecture/kast-vs-lsp"),
]
for group_name, page in placement_checks:
    if page not in groups_by_name.get(group_name, []):
        print(f"{page} must appear under {group_name} in the sidebar", file=sys.stderr)
        sys.exit(1)

print("Docs navigation contract passed")
PY
