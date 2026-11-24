#!/usr/bin/env python3
"""Provision an owned Linux runtime without changing the caller's HOME or credentials."""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import re
import zipfile

HERE = Path(__file__).resolve().parent


def run(*argv: str, **kwargs: object) -> None:
    subprocess.run(argv, check=True, **kwargs)


def verify_source(destination: Path, revision: str) -> None:
    observed = subprocess.check_output(["git", "-C", str(destination), "rev-parse", "HEAD"], text=True).strip()
    if observed != revision:
        raise SystemExit(f"Refusing unpinned source checkout: {destination.name}")
    status = subprocess.check_output(["git", "-C", str(destination), "status", "--porcelain", "--untracked-files=all"], text=True)
    if status:
        raise SystemExit(f"Refusing modified source checkout: {destination.name}")


def verify_idea(target: Path, build: str) -> None:
    product = json.loads((target / "product-info.json").read_text())
    if product["buildNumber"] != build:
        raise SystemExit("IDE build mismatch")
    release = (target / "jbr/release").read_text()
    version = re.search(r'^JAVA_VERSION="(\d+)', release, re.MULTILINE)
    if version is None or int(version[1]) < 25:
        raise SystemExit("Bundled JBR 25+ required")
    with zipfile.ZipFile(target / "plugins/Kotlin/lib/kotlin-plugin.jar") as source:
        metadata = source.read("META-INF/plugin.xml").decode()
    if f"<version>{build}-IJ</version>" not in metadata:
        raise SystemExit("Bundled Kotlin plugin build mismatch")


def provision(root: Path, with_idea: bool, with_fixture: bool, with_control: bool, cache: Path | None) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise SystemExit("This profile requires Linux x86_64")
    if root.exists() and not (root / ".kast-cloud-owned").is_file():
        raise SystemExit("Refusing an existing directory without .kast-cloud-owned")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    (root / ".kast-cloud-owned").touch(mode=0o600)
    # Bootstrap owns one extraction transaction at a time in this root.
    lock = (root / ".bootstrap.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit("Another bootstrap owns this runtime") from None
    downloads = root / "downloads"
    downloads.mkdir(exist_ok=True)
    pins = json.loads((HERE / "pins.json").read_text())
    for name, spec in pins["artifacts"].items():
        if name == "idea" and not with_idea:
            continue
        if name == "fixture_idea" and not with_fixture:
            continue
        if name == "go" and not with_control:
            continue
        archive = downloads / spec["url"].rsplit("/", 1)[1]
        if cache is not None and (cache / archive.name).is_file():
            archive = cache / archive.name
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
            if "build" in spec:
                verify_idea(target, spec["build"])
            continue
        if target.exists():
            raise SystemExit(f"Refusing incomplete existing extraction: {target}")
        with tempfile.TemporaryDirectory(dir=root, prefix="unpack-") as temporary:
            stage = Path(temporary)
            with tarfile.open(archive) as source:
                source.extractall(stage, filter="data")
            if spec.get("flat"):
                stage.rename(target)
            else:
                children = list(stage.iterdir())
                if len(children) != 1 or not children[0].is_dir():
                    raise SystemExit(f"Expected one archive root: {name}")
                children[0].rename(target)
        receipt.write_text(spec["sha256"] + "\n")
        if "build" in spec:
            verify_idea(target, spec["build"])
    destination = root / "sources" / "slopsentral"
    revision = pins["slopsentral_source"]
    if not destination.exists():
        destination.parent.mkdir(exist_ok=True)
        run("git", "init", "--quiet", str(destination))
        run("git", "-C", str(destination), "remote", "add", "origin", "https://github.com/amichne/slopsentral.git")
        run("git", "-C", str(destination), "fetch", "--depth=1", "origin", revision)
        run("git", "-C", str(destination), "checkout", "--detach", "FETCH_HEAD")
    verify_source(destination, revision)
    node_bin = root / pins["artifacts"]["node"]["directory"] / "bin"
    for directory in ("gradle", "uv-cache", "uv-python", "config", "data", "state", "cache"):
        (root / directory).mkdir(exist_ok=True, mode=0o700)
    shutil.copyfile(HERE / "configure-gradle.py", root / "configure-gradle.py")
    quote = shlex.quote
    activation = (
        "# Generated; source in a shell. HOME and credentials stay untouched.\n"
        f"export DEV_CLOUD_ROOT={quote(str(root))}\n"
        f"export JAVA_HOME={quote(str(root / pins['artifacts']['jdk']['directory']))}\n"
        f"export PATH={quote(str(node_bin))}:{quote(str(root / pins['artifacts']['uv']['directory']))}:"
        "\"$JAVA_HOME/bin:$PATH\"\n"
        'export PATH="$DEV_CLOUD_ROOT/vale:$PATH"\n'
        'export GRADLE_USER_HOME="$DEV_CLOUD_ROOT/gradle"\n'
        'export UV_CACHE_DIR="$DEV_CLOUD_ROOT/uv-cache"\n'
        'export UV_PYTHON_INSTALL_DIR="$DEV_CLOUD_ROOT/uv-python"\n'
        'export npm_config_cache="$DEV_CLOUD_ROOT/npm-cache"\n'
        'export DEV_CLOUD_GRADLE_BASE_OPTS="${DEV_CLOUD_GRADLE_BASE_OPTS-${GRADLE_OPTS-}}"\n'
        'python3 "$DEV_CLOUD_ROOT/configure-gradle.py" || return 1\n'
        'source "$DEV_CLOUD_ROOT/gradle-runtime.sh"\n'
    )
    if with_control:
        # The pinned release equals manager/go.mod's toolchain line, so Go never downloads another.
        go = root / pins["artifacts"]["go"]["directory"]
        activation += f"export PATH={quote(str(go / 'bin'))}:\"$PATH\"\nexport GOTOOLCHAIN=local\n"
    (root / "activate.sh").write_text(activation)
    run(str(node_bin / "node"), "--version")
    run(str(root / pins["artifacts"]["uv"]["directory"] / "uv"), "--version")
    print(json.dumps({"mode": "NO_INFERENCE", "runtime": str(root), "idea_unpacked": with_idea,
                      "activation": str(root / "activate.sh")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--with-idea", action="store_true", help="Unpack vendor archive; accept no license")
    parser.add_argument("--with-native-fixture", action="store_true", help="Also unpack the separately pinned native test IDE")
    parser.add_argument("--with-control", action="store_true", help="Also unpack Go for Control executable builds")
    parser.add_argument("--download-cache", type=Path, help="Optional read-only public archive cache; all digests are still verified")
    args = parser.parse_args()
    provision(args.root.resolve(), args.with_idea, args.with_native_fixture, args.with_control, args.download_cache)
