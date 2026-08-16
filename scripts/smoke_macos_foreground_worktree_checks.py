#!/usr/bin/env python3
"""Read-only assertions for the macOS foreground-worktree smoke."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import sys
from pathlib import Path


def load(path: str) -> dict:
    with Path(path).open(encoding="utf-8") as source:
        return json.load(source)


def overlaps(left: Path, right: Path) -> bool:
    return left == right or left in right.parents or right in left.parents


def settings_fixture(settings_argument: str, marker_argument: str) -> None:
    settings = Path(settings_argument).read_text(encoding="utf-8")
    marker = json.dumps(str(Path(marker_argument).resolve()))
    forbidden = ("KAST_SMOKE_PHASE", "KAST_SMOKE_MARKER_DIR", "System.getenv")
    required = (
        f"java.io.File({marker})",
        "gradle.startParameter.projectCacheDir?.canonicalFile",
        'java.io.File(settingsDir, ".gradle").canonicalFile',
        'cache.path.endsWith("${java.io.File.separator}gradle-project-cache")',
        'if (kastOwnedCache) "headless" else "foreground"',
    )
    if any(value in settings for value in forbidden):
        raise SystemExit("Gradle fixture still depends on launcher environment propagation")
    missing = [value for value in required if value not in settings]
    if missing:
        raise SystemExit(f"Gradle fixture lacks self-contained phase evidence: {missing}")


def product(app_argument: str) -> None:
    app = Path(app_argument)
    metadata = load(str(app / "Contents/Resources/product-info.json"))
    build = (app / "Contents/Resources/build.txt").read_text(encoding="utf-8").strip()
    launches = [item for item in metadata.get("launch", []) if item.get("os") == "macOS"]
    expected_arch = {"arm64": "aarch64", "x86_64": "x86_64"}.get(platform.machine())
    supported_launch = any(
        item.get("arch") in {expected_arch, platform.machine()} for item in launches
    )
    if metadata.get("productCode") != "IU" or not build.startswith("IU-262."):
        raise SystemExit(f"unsupported IntelliJ IDEA host: {metadata.get('productCode')} {build}")
    if not supported_launch:
        raise SystemExit(f"installed IntelliJ IDEA has no launch for {platform.machine()}")
    print(f"IntelliJ IDEA {metadata.get('version')} ({build})")


def digest(root_argument: str) -> None:
    root = Path(root_argument)
    result = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        stat = path.lstat()
        result.update(f"{relative}\0{stat.st_mode}\0{stat.st_mtime_ns}\0".encode())
        if path.is_symlink():
            result.update(os.readlink(path).encode())
        elif path.is_file():
            result.update(path.read_bytes())
    print(result.hexdigest())


def started(result_path: str, workspace_argument: str) -> None:
    result = load(result_path)
    workspace = str(Path(workspace_argument).resolve())
    if result.get("state") != "STARTED" or result.get("workspaceRoot") != workspace:
        raise SystemExit(f"unexpected background result: {result}")
    print(result["pid"], result["storageRoot"])


def collision(result_path: str) -> None:
    error = load(result_path)
    if error.get("code") != "INDEXER_STORAGE_IN_USE":
        raise SystemExit(f"unexpected duplicate rejection: {error}")


def status_ready(result_path: str) -> None:
    selected = load(result_path).get("selected") or {}
    runtime = selected.get("runtimeStatus") or {}
    if runtime.get("state") != "READY" or runtime.get("referenceIndexReady") is not True:
        raise SystemExit(1)


def workspace_data(report: dict) -> Path:
    item = next(item for item in report["configFiles"] if item["scope"] == "workspace")
    return Path(item["path"]).resolve().parent


def layout(
    start_path: str,
    main_paths_path: str,
    linked_paths_path: str,
    workspace_argument: str,
    foreground_argument: str,
) -> None:
    start = load(start_path)
    main_paths = load(main_paths_path)
    linked_paths = load(linked_paths_path)
    workspace = Path(workspace_argument).resolve()
    foreground = Path(foreground_argument).resolve()
    storage = Path(start["storageRoot"]).resolve()
    manifest = load(str(storage / "launch-manifest.json"))
    main_data = workspace_data(main_paths)
    linked_data = workspace_data(linked_paths)
    if main_data == linked_data or Path(manifest["workspaceDataDirectory"]).resolve() != linked_data:
        raise SystemExit("sibling worktrees did not receive distinct admitted analysis storage")
    repository_data = Path(manifest["repositoryDataDirectory"]).resolve()
    if linked_data.parent.name != "worktrees" or repository_data != linked_data.parent.parent:
        raise SystemExit("linked-worktree analysis storage lost its common-repository identity")
    paths = {
        "source .idea": workspace / ".idea",
        "foreground config": foreground / "config",
        "foreground system": foreground / "system",
        "foreground log": foreground / "log",
        "foreground plugins": foreground / "plugins",
        "sidecar project": storage / "project-identity",
        "sidecar Gradle cache": storage / "gradle-project-cache",
        "sidecar config": storage / "idea-config",
        "sidecar system": storage / "idea-system",
        "sidecar log": storage / "idea-log",
        "sidecar plugins": storage / "plugins",
        "analysis workspace": linked_data,
    }
    for left_name, left in paths.items():
        for right_name, right in paths.items():
            if left_name < right_name and overlaps(left, right):
                raise SystemExit(
                    f"writable paths overlap: {left_name}={left}, {right_name}={right}"
                )
    for name, path in paths.items():
        if name != "analysis workspace" and overlaps(repository_data, path):
            raise SystemExit(f"repository analysis storage overlaps {name}: {repository_data}")
    if Path(manifest["canonicalWorkspaceRoot"]).resolve() != workspace:
        raise SystemExit("launch manifest does not bind the exact linked worktree")


def reused(result_path: str, pid: str, storage_root: str) -> None:
    result = load(result_path)
    if (
        result.get("state") != "REUSED"
        or str(result.get("pid")) != pid
        or result.get("storageRoot") != storage_root
    ):
        raise SystemExit(f"runtime was duplicated instead of reused: {result}")


def main(arguments: list[str]) -> None:
    command, *values = arguments
    if command == "product":
        product(*values)
    elif command == "settings-fixture":
        settings_fixture(*values)
    elif command == "overlaps":
        raise SystemExit(0 if overlaps(*(Path(value).resolve() for value in values)) else 1)
    elif command == "digest":
        digest(*values)
    elif command == "started":
        started(*values)
    elif command == "collision":
        collision(*values)
    elif command == "status-ready":
        status_ready(*values)
    elif command == "layout":
        layout(*values)
    elif command == "reused":
        reused(*values)
    else:
        raise SystemExit(f"unknown smoke assertion: {command}")


if __name__ == "__main__":
    main(sys.argv[1:])
