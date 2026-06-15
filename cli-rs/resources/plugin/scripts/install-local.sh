#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s --target REPO_ROOT [--force]\n' "${0##*/}" >&2
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

target_root=""
force=false
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --target)
      [[ "$#" -ge 2 ]] || die "--target requires a path"
      target_root="$2"
      shift 2
      ;;
    --force)
      force=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$target_root" ]] || { usage; die "--target is required"; }
plugin_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
target_root="$(cd -- "$target_root" >/dev/null 2>&1 && pwd)"
python3 - "$plugin_root" "$target_root" "$force" <<'PY'
import json
import os
import pathlib
import shutil
import stat
import sys

plugin_root = pathlib.Path(sys.argv[1])
target_root = pathlib.Path(sys.argv[2])
force = sys.argv[3] == "true"
manifest = json.loads((plugin_root / "primitive-manifest.json").read_text())
skill_root = plugin_root.parent / "kast-skill"

def safe_relative(value):
    path = pathlib.PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(f"error: unsafe manifest path: {value}")
    return pathlib.Path(*path.parts)

installed = []
for output in manifest["outputs"]:
    source = safe_relative(output["source"])
    target = target_root / ".github" / safe_relative(output["target"])
    if output["type"] == "PACKAGE_FILE":
        source_root = plugin_root
    elif output["type"] == "KAST_SKILL_FILE":
        source_root = skill_root
    else:
        raise SystemExit(f"error: unsupported output type: {output['type']}")
    source_path = source_root / source
    if not source_path.is_file():
        raise SystemExit(f"error: manifest source not found: {source_path}")
    if target.exists() and not force:
        raise SystemExit(f"error: refusing to overwrite {target}; pass --force")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_path, target)
    if output.get("executable") or target.suffix in {".sh", ".py", ".mjs"}:
        mode = target.stat().st_mode
        target.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    installed.append(str(target.relative_to(target_root)))

print(json.dumps({"ok": True, "installedAt": str(target_root), "installedFiles": installed}))
PY
