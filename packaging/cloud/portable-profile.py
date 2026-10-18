#!/usr/bin/env python3
"""Copy only named public Pi resources into an otherwise empty owned agent directory."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

here = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--runtime", required=True, type=Path)
parser.add_argument("--dotfiles", required=True, type=Path)
args = parser.parse_args()
root = args.runtime.resolve()
source = args.dotfiles.resolve()
pins = json.loads((here / "pins.json").read_text())
if not (root / ".pi-eval-owned").is_file():
    raise SystemExit("Runtime ownership marker required")
revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
if revision != pins["dotfiles_source"]:
    raise SystemExit("Dotfiles revision does not match pins.json")
subprocess.run(["git", "-C", str(source), "diff", "--exit-code", "HEAD", "--",
                ".pi/agent/APPEND_SYSTEM.md", ".pi/agent/agents", ".pi/agent/prompts"], check=True)
agent = root / "agent"
for relative in ("APPEND_SYSTEM.md", "agents", "prompts"):
    origin = source / ".pi/agent" / relative
    destination = agent / relative
    if destination.exists():
        raise SystemExit(f"Refusing to replace existing resource: {destination}")
    tracked = subprocess.check_output(["git", "-C", str(source), "ls-files", "--", ".pi/agent/" + relative], text=True).splitlines()
    if not tracked:
        raise SystemExit("Expected tracked public resources")
    for name in tracked:
        path = source / name
        if path.is_symlink() or not path.is_file():
            raise SystemExit("Public resources must be regular files")
        installed = agent / path.relative_to(source / ".pi/agent")
        installed.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, installed)
print(json.dumps({"mode": "PUBLIC_PI_RESOURCES_ONLY", "revision": revision,
                  "paths": ["APPEND_SYSTEM.md", "agents", "prompts"]}))
