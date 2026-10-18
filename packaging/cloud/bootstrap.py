#!/usr/bin/env python3
"""Provision an owned Linux runtime without changing the caller's HOME or credentials."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent


def run(*argv: str, **kwargs: object) -> None:
    subprocess.run(argv, check=True, **kwargs)


def provision(root: Path, with_idea: bool) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise SystemExit("This profile requires Linux x86_64")
    if root.exists() and not (root / ".pi-eval-owned").is_file():
        raise SystemExit("Refusing an existing directory without .pi-eval-owned")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    (root / ".pi-eval-owned").touch(mode=0o600)
    downloads = root / "downloads"
    downloads.mkdir(exist_ok=True)
    pins = json.loads((HERE / "pins.json").read_text())
    for name, spec in pins["artifacts"].items():
        if name == "idea" and not with_idea:
            continue
        archive = downloads / spec["url"].rsplit("/", 1)[1]
        if not archive.exists():
            partial = archive.with_suffix(archive.suffix + ".partial")
            run("curl", "--fail", "--silent", "--show-error", "--location", "--retry", "2",
                "--output", str(partial), spec["url"])
            partial.rename(archive)
        if hashlib.file_digest(archive.open("rb"), "sha256").hexdigest() != spec["sha256"]:
            raise SystemExit(f"Checksum mismatch: {name}")
        target = root / spec["directory"]
        receipt = root / f".{name}.sha256"
        if receipt.exists() and receipt.read_text().strip() == spec["sha256"] and target.is_dir():
            continue
        if target.exists():
            raise SystemExit(f"Refusing incomplete existing extraction: {target}")
        with tempfile.TemporaryDirectory(dir=root, prefix="unpack-") as temporary:
            stage = Path(temporary)
            with tarfile.open(archive) as source:
                source.extractall(stage, filter="data")
            children = list(stage.iterdir())
            if len(children) != 1 or not children[0].is_dir():
                raise SystemExit(f"Expected one archive root: {name}")
            children[0].rename(target)
        receipt.write_text(spec["sha256"] + "\n")
    npm = root / "npm"
    npm.mkdir(exist_ok=True)
    for name in ("package.json", "package-lock.json"):
        shutil.copyfile(HERE / name, npm / name)
    node_bin = root / pins["artifacts"]["node"]["directory"] / "bin"
    child = {**os.environ, "PATH": str(node_bin) + os.pathsep + os.environ["PATH"],
             "npm_config_cache": str(root / "npm-cache")}
    run(str(node_bin / "npm"), "ci", "--ignore-scripts", "--no-audit", "--no-fund", cwd=npm, env=child)
    for directory in ("agent", "gradle", "uv-cache", "uv-python", "config", "data", "state", "cache"):
        (root / directory).mkdir(exist_ok=True, mode=0o700)
    quote = shlex.quote
    activation = (
        "# Generated; source in a shell. HOME and credentials stay untouched.\n"
        f"export PI_EVAL_ROOT={quote(str(root))}\n"
        f"export JAVA_HOME={quote(str(root / pins['artifacts']['jdk']['directory']))}\n"
        f"export PATH={quote(str(node_bin))}:{quote(str(root / pins['artifacts']['uv']['directory']))}:"
        f"{quote(str(npm / 'node_modules/.bin'))}:\"$JAVA_HOME/bin:$PATH\"\n"
        'export PI_CODING_AGENT_DIR="$PI_EVAL_ROOT/agent"\n'
        'export GRADLE_USER_HOME="$PI_EVAL_ROOT/gradle"\n'
        'export UV_CACHE_DIR="$PI_EVAL_ROOT/uv-cache"\n'
        'export UV_PYTHON_INSTALL_DIR="$PI_EVAL_ROOT/uv-python"\n'
        'export npm_config_cache="$PI_EVAL_ROOT/npm-cache"\n'
    )
    (root / "activate.sh").write_text(activation)
    run(str(node_bin / "node"), "--version")
    run(str(root / pins["artifacts"]["uv"]["directory"] / "uv"), "--version")
    print(json.dumps({"mode": "NO_INFERENCE", "runtime": str(root), "idea_unpacked": with_idea,
                      "activation": str(root / "activate.sh")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--with-idea", action="store_true", help="Unpack vendor archive; accept no license")
    args = parser.parse_args()
    provision(args.root.resolve(), args.with_idea)
