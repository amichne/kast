"""Install the existing composed Host and run one task inside an offline macOS guest."""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import tarfile
from dataclasses import asdict
from pathlib import Path

from composed_bundle import BundleInstalled, install_bundle
from composition_platform import HostPlatform

ROOT = Path("/private/tmp/kt")


def task_environment(root: Path) -> dict[str, str]:
    """No host or guest-login environment enters the task process."""
    return {
        "PATH": f"{root}/install/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": str(root / "home"),
        "TMPDIR": str(root / "tmp"),
        "JAVA_HOME": str(
            root / "install" / HostPlatform.MACOS_ARM64.layout.home / "jbr/Contents/Home"
        ),
        "GRADLE_USER_HOME": str(root / "home/.gradle"),
        "KAST_INSTALL_ROOT": str(root / "install"),
        "LC_ALL": "en_US.UTF-8",
    }


def install(root: Path, digest: str, version: str) -> int:
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        return 2
    repository = root / "repository"
    repository.mkdir(mode=0o700)
    with tarfile.open(root / "source.tar") as source:
        source.extractall(repository, filter="data")
    for name in ("home", "tmp"):
        (root / name).mkdir(mode=0o700)
    installed = install_bundle(
        root / "bundle.tar.gz", digest, root / "install", version, repository
    )
    print(json.dumps(asdict(installed)), flush=True)
    if not isinstance(installed, BundleInstalled):
        return 1
    environment = task_environment(root)
    for args in (
        ("git", "init", "-q", "--initial-branch=task"),
        ("git", "-c", "core.hooksPath=/dev/null", "add", "--all"),
        (
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "-c",
            "user.name=Kast task",
            "-c",
            "user.email=task@localhost",
            "-c",
            "commit.gpgsign=false",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "Task source snapshot",
        ),
    ):
        result = subprocess.run(args, cwd=repository, env=environment, check=False)
        if result.returncode != 0:
            return result.returncode
    return 0


def run_task(root: Path, command: tuple[str, ...]) -> int:
    if not command or not (root / "install/bin/kast").is_file():
        return 2
    try:
        result = subprocess.run(
            command,
            cwd=root / "repository",
            env=task_environment(root),
            check=False,
        )
    except FileNotFoundError:
        return 127
    return result.returncode if result.returncode >= 0 else 128 - result.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    sub = parser.add_subparsers(dest="stage", required=True)
    setup = sub.add_parser("install")
    _ = setup.add_argument("--bundle-sha256", required=True)
    _ = setup.add_argument("--version", required=True)
    task = sub.add_parser("task")
    _ = task.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    _ = os.umask(0o077)
    if args.stage == "install":
        return install(ROOT, args.bundle_sha256, args.version)
    command = tuple(args.command)
    return run_task(ROOT, command[1:] if command[:1] == ("--",) else command)


if __name__ == "__main__":
    raise SystemExit(main())
