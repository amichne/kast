#!/usr/bin/env python3
"""Prove an immutable published 0.x upgrade before candidate semantic acceptance.

Only bounded identities and finite outcomes leave this helper. GitHub access
uses the release runner's credentials; product commands use the isolated host.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
from enum import Enum

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "amichne/kast"
MAX_OUTPUT_BYTES = 8 * 1024 * 1024


class Cause(str, Enum):
    PRIOR_RELEASE_UNAVAILABLE = "prior-release-unavailable"
    PRIOR_RELEASE_MUTABLE = "prior-release-mutable"
    PRIOR_ASSET_UNAVAILABLE = "prior-asset-unavailable"
    PRIOR_ASSET_IDENTITY_MISMATCH = "prior-asset-identity-mismatch"
    INPUT_INVALID = "input-invalid"
    COMMAND_FAILED = "command-failed"
    COMMAND_BUDGET_EXCEEDED = "command-budget-exceeded"
    INSTALLATION_UNPROVEN = "installation-unproven"
    PASSIVE_STATUS_UNPROVEN = "passive-status-unproven"
    CORRUPTION_NOT_REJECTED = "corruption-not-rejected"
    ACTIVE_INSTALLATION_CHANGED = "active-installation-changed"
    WORKSPACE_CHANGED = "workspace-changed"
    CANDIDATE_ASSETS_CHANGED = "candidate-assets-changed"


class Corruption(str, Enum):
    CHECKSUM_MISMATCH = "checksum-mismatch"
    UNSAFE_ARCHIVE_PATH = "unsafe-archive-path"


class UpgradeFailure(Exception):
    def __init__(self, cause: Cause):
        self.cause = cause
        super().__init__(cause.value)


def identity(value) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def file_digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise UpgradeFailure(Cause.INPUT_INVALID)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def version_tuple(version: str) -> tuple[int, int, int]:
    if not isinstance(version, str) or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise UpgradeFailure(Cause.INPUT_INVALID)
    return tuple(int(part) for part in version.split("."))


def asset_names(version: str) -> tuple[str, ...]:
    version_tuple(version)
    control = f"kast-control-v{version}-macos-aarch64.tar.gz"
    runtime = f"kast-semantic-runtime-{version}-macos-aarch64.zip"
    return control, control + ".sha256", runtime, runtime + ".sha256"


def command(arguments, *, cwd=None, environment=None, timeout=120):
    try:
        result = subprocess.run(arguments, cwd=cwd, env=environment, capture_output=True,
                                text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as failure:
        raise UpgradeFailure(Cause.COMMAND_BUDGET_EXCEEDED) from failure
    except OSError as failure:
        raise UpgradeFailure(Cause.COMMAND_FAILED) from failure
    if len(result.stdout.encode()) + len(result.stderr.encode()) > MAX_OUTPUT_BYTES:
        raise UpgradeFailure(Cause.COMMAND_BUDGET_EXCEEDED)
    return result


def required(arguments, **options) -> str:
    result = command(arguments, **options)
    if result.returncode != 0:
        raise UpgradeFailure(Cause.COMMAND_FAILED)
    return result.stdout


def select_prior_release(releases: list[dict]) -> dict:
    candidates = []
    for release in releases:
        if not isinstance(release, dict):
            raise UpgradeFailure(Cause.PRIOR_RELEASE_UNAVAILABLE)
        if release.get("draft") is not False or release.get("prerelease") is not False:
            continue
        tag = release.get("tag_name")
        if not isinstance(tag, str) or not re.fullmatch(r"v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", tag):
            continue
        candidates.append((version_tuple(tag[1:]), release))
    if not candidates:
        raise UpgradeFailure(Cause.PRIOR_RELEASE_UNAVAILABLE)
    version, selected = max(candidates, key=lambda candidate: candidate[0])
    if sum(candidate[0] == version for candidate in candidates) != 1:
        raise UpgradeFailure(Cause.PRIOR_RELEASE_UNAVAILABLE)
    if selected.get("immutable") is not True:
        raise UpgradeFailure(Cause.PRIOR_RELEASE_MUTABLE)
    return selected


def download_prior_assets(destination: Path) -> dict:
    try:
        pages = json.loads(required(["gh", "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/releases"]))
    except (ValueError, UnicodeError) as failure:
        raise UpgradeFailure(Cause.PRIOR_RELEASE_UNAVAILABLE) from failure
    if not isinstance(pages, list) or not all(isinstance(page, list) for page in pages):
        raise UpgradeFailure(Cause.PRIOR_RELEASE_UNAVAILABLE)
    releases = [release for page in pages for release in page]
    release = select_prior_release(releases)
    tag = release["tag_name"]
    version = tag[1:]
    assets = release.get("assets")
    if not isinstance(assets, list) or not all(isinstance(asset, dict) for asset in assets):
        raise UpgradeFailure(Cause.PRIOR_ASSET_UNAVAILABLE)
    identities = {}
    destination.mkdir(parents=True, exist_ok=False)
    for name in asset_names(version):
        matches = [asset for asset in assets if asset.get("name") == name]
        if len(matches) != 1:
            raise UpgradeFailure(Cause.PRIOR_ASSET_UNAVAILABLE)
        asset = matches[0]
        expected = asset.get("digest")
        size = asset.get("size")
        if not isinstance(expected, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", expected) or type(size) is not int or not 0 < size <= 2 * 1024**3:
            raise UpgradeFailure(Cause.PRIOR_ASSET_IDENTITY_MISMATCH)
        required(["gh", "release", "download", tag, "--repo", REPOSITORY, "--pattern", name, "--dir", str(destination)], timeout=300)
        path = destination / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != size or file_digest(path) != expected:
            raise UpgradeFailure(Cause.PRIOR_ASSET_IDENTITY_MISMATCH)
        identities[name] = expected
    return {"tag": tag, "version": version, "immutable": True, "assets": identities,
            "releaseCatalogDigest": identity(releases)}


def workspace_identity(workspace: Path) -> str:
    # Include staged, unstaged, and untracked file bytes; no source payload enters the proof.
    files = required(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=workspace).split("\0")
    result = {}
    for name in sorted(set(files) - {""}):
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise UpgradeFailure(Cause.INPUT_INVALID)
        path = workspace / relative
        if path.is_symlink():
            result[name] = {"link": os.readlink(path)}
        elif path.is_file():
            result[name] = {"digest": file_digest(path), "mode": stat.S_IMODE(path.stat().st_mode)}
        elif not path.exists():
            result[name] = {"state": "deleted"}
        else:
            raise UpgradeFailure(Cause.INPUT_INVALID)
    return identity({"files": result, "diff": required(["git", "diff", "--binary", "HEAD"], cwd=workspace),
                     "status": required(["git", "status", "--porcelain", "--untracked-files=all"], cwd=workspace)})


def installation_identity(host, environment: dict[str, str], version: str) -> str:
    root = host.root / "installation"
    current, public = root / "current", host.root / "bin/kast"
    if not current.is_symlink() or not public.is_symlink() or os.readlink(current) != f"versions/{version}" or os.readlink(public) != str(root / "current/bin/kast-complete"):
        raise UpgradeFailure(Cause.INSTALLATION_UNPROVEN)
    active = root / "versions" / version
    if active.is_symlink() or not active.is_dir():
        raise UpgradeFailure(Cause.INSTALLATION_UNPROVEN)
    observed = {}
    for path in sorted(active.rglob("*")):
        name = str(path.relative_to(active))
        if path.is_symlink():
            observed[name] = {"link": os.readlink(path)}
        elif path.is_file():
            observed[name] = {"digest": file_digest(path), "mode": stat.S_IMODE(path.stat().st_mode)}
        elif path.is_dir():
            observed[name] = {"mode": stat.S_IMODE(path.stat().st_mode)}
        else:
            raise UpgradeFailure(Cause.INSTALLATION_UNPROVEN)
    config = Path(environment["XDG_CONFIG_HOME"]) / "kast/environment"
    return identity({"links": [os.readlink(current), os.readlink(public)], "active": observed,
                     "configuration": file_digest(config), "configurationMode": stat.S_IMODE(config.stat().st_mode)})


def assert_version(host, environment, version):
    observed = required([str(host.root / "bin/kast"), "--version"], cwd=host.workspace, environment=environment).strip()
    if observed != f"kast {version} (IntelliJ sidecar)":
        raise UpgradeFailure(Cause.INSTALLATION_UNPROVEN)


def passive_status(host, environment):
    result = command([str(host.root / "bin/kast"), "status"], cwd=host.workspace, environment=environment)
    try:
        document = json.loads(result.stdout)
    except (ValueError, UnicodeError) as failure:
        raise UpgradeFailure(Cause.PASSIVE_STATUS_UNPROVEN) from failure
    if result.returncode != 0 or result.stderr.strip() or not isinstance(document, dict) or document.get("command") != "status" or document.get("status") != "complete" or document.get("runtime") != "stopped" or document.get("root") != str(host.workspace):
        raise UpgradeFailure(Cause.PASSIVE_STATUS_UNPROVEN)
    if host.readiness_file.exists() or host.broker_socket.exists() or any((host.runtime / "endpoints").rglob("*.sock")):
        raise UpgradeFailure(Cause.PASSIVE_STATUS_UNPROVEN)
    return {"status": "stopped", "documentDigest": identity(document)}


def corrupt_assets(source: Path, destination: Path, version: str, case: Corruption) -> None:
    destination.mkdir()
    for name in asset_names(version):
        shutil.copy2(source / name, destination / name)
    control = destination / asset_names(version)[0]
    checksum = control.with_name(control.name + ".sha256")
    if case is Corruption.CHECKSUM_MISMATCH:
        original = file_digest(control).removeprefix("sha256:")
        changed = ("1" if original[0] == "0" else "0") + original[1:]
        checksum.write_text(changed + "  " + control.name + "\n")
    elif case is Corruption.UNSAFE_ARCHIVE_PATH:
        rewritten = destination / "corrupted-control.tar.gz"
        with tarfile.open(control, "r:gz") as archive, tarfile.open(rewritten, "w:gz") as output:
            for member in archive:
                stream = archive.extractfile(member) if member.isfile() else None
                try:
                    output.addfile(member, stream)
                finally:
                    if stream is not None:
                        stream.close()
            escape = tarfile.TarInfo("../escape")
            escape.size = 1
            output.addfile(escape, io.BytesIO(b"x"))
        rewritten.replace(control)
        checksum.write_text(file_digest(control).removeprefix("sha256:") + "  " + control.name + "\n")
    else:
        raise UpgradeFailure(Cause.INPUT_INVALID)


def assert_corruption_rejected(result, case: Corruption, version: str) -> None:
    expected = {
        Corruption.CHECKSUM_MISMATCH: f"x kast-install: SHA-256 mismatch for {asset_names(version)[0]}",
        Corruption.UNSAFE_ARCHIVE_PATH: "x kast-install: control archive contains an unsafe path: ../escape",
    }[case]
    lines = [line.strip() for line in result.stderr.splitlines() if line.strip()]
    if result.returncode != 1 or result.stdout.strip() or not lines or lines[-1] != expected:
        raise UpgradeFailure(Cause.CORRUPTION_NOT_REJECTED)


def install_candidate_with_upgrade_proof(host, assets: Path, version: str, idea: Path,
                                         environment: dict[str, str], install) -> tuple[dict[str, str], dict]:
    version_tuple(version)
    assets = assets.resolve(strict=True)
    candidate = {name: file_digest(assets / name) for name in asset_names(version)}
    before_workspace = workspace_identity(host.workspace)
    product_environment = dict(environment)
    product_environment.pop("KAST_RUNTIME_ARCHIVE", None)
    product_environment.update(NO_COLOR="1", KAST_ASCII="1")
    with tempfile.TemporaryDirectory(prefix="upgrade-proof-", dir=host.root) as raw:
        scratch = Path(raw)
        prior_assets = scratch / "prior"
        prior = download_prior_assets(prior_assets)
        if version_tuple(version) <= version_tuple(prior["version"]):
            raise UpgradeFailure(Cause.INPUT_INVALID)
        prior_environment = install(host, prior_assets, prior["version"], idea, product_environment)
        assert_version(host, prior_environment, prior["version"])
        prior["passiveStatus"] = passive_status(host, prior_environment)
        installed_environment = install(host, assets, version, idea, product_environment)
        assert_version(host, installed_environment, version)
        installed_identity = installation_identity(host, installed_environment, version)
        if workspace_identity(host.workspace) != before_workspace:
            raise UpgradeFailure(Cause.WORKSPACE_CHANGED)
        rejected = []
        for case in Corruption:
            corrupted = scratch / case.value
            corrupt_assets(assets, corrupted, version, case)
            result = command(["/bin/bash", str(ROOT / "install.sh"), "install", "--version", version,
                              "--assets-directory", str(corrupted)], cwd=host.workspace,
                             environment=installed_environment, timeout=300)
            assert_corruption_rejected(result, case, version)
            if installation_identity(host, installed_environment, version) != installed_identity:
                raise UpgradeFailure(Cause.ACTIVE_INSTALLATION_CHANGED)
            if workspace_identity(host.workspace) != before_workspace:
                raise UpgradeFailure(Cause.WORKSPACE_CHANGED)
            assert_version(host, installed_environment, version)
            rejected.append({"case": case.value, "status": "rejected", "exitCode": result.returncode,
                             "activeInstallationDigest": installed_identity, "workspaceDigest": before_workspace})
        if {name: file_digest(assets / name) for name in asset_names(version)} != candidate:
            raise UpgradeFailure(Cause.CANDIDATE_ASSETS_CHANGED)
    return installed_environment, {"schemaVersion": 1, "status": "passed", "candidateVersion": version,
                                   "candidateAssets": candidate, "priorRelease": prior,
                                   "activeInstallationDigest": installed_identity,
                                   "workspaceDigest": before_workspace, "corruptionCases": rejected}
