#!/usr/bin/env python3
"""Install a pinned composed bundle, then verify it before per-task activation."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shlex
import subprocess
import sys
from collections.abc import Mapping
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from composed_bundle import BundleInstalled, BundleRejected, install_bundle
from managed_headless import Rejected, object_fields


class Failure(StrEnum):
    INVALID_PIN = "INVALID_PIN"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    UNSAFE_ROOT = "UNSAFE_ROOT"
    INCOMPLETE_INSTALLATION = "INCOMPLETE_INSTALLATION"
    PIN_MISMATCH = "PIN_MISMATCH"
    VERSION_FAILED = "VERSION_FAILED"
    IO_FAILED = "IO_FAILED"
    INVALID_PROXY = "INVALID_PROXY"
    TRUSTSTORE_MISSING = "TRUSTSTORE_MISSING"
    NETWORK_STATE_CONFLICT = "NETWORK_STATE_CONFLICT"
    INVALID_WORKSPACE = "INVALID_WORKSPACE"
    GRADLE_FAILED = "GRADLE_FAILED"


@dataclass(frozen=True)
class ImageRejected:
    reason: Failure
    type: Literal["rejected"] = "rejected"
    exitCode: int | None = None


@dataclass(frozen=True)
class ImagePin:
    root: Path
    version: str
    sha256: str


@dataclass(frozen=True)
class VerifiedImage:
    root: str
    version: str
    bundleSha256: str
    semanticReadiness: Literal["NOT_RUN"] = "NOT_RUN"
    type: Literal["verified"] = "verified"


@dataclass(frozen=True)
class NetworkProperties:
    entries: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class ConfiguredNetwork:
    path: Path
    properties: NetworkProperties


@dataclass(frozen=True)
class PreparedWorkspace:
    root: str
    workspace: str
    gradleVersion: str
    semanticReadiness: Literal["NOT_RUN"] = "NOT_RUN"
    type: Literal["workspace_prepared"] = "workspace_prepared"


def network_properties(environment: Mapping[str, str]) -> NetworkProperties | ImageRejected:
    truststore = Path(environment.get("CLOUD_IMAGE_JAVA_TRUSTSTORE", "/etc/ssl/certs/java/cacerts"))
    if (
        not truststore.is_absolute()
        or not truststore.is_file()
        or any(c in str(truststore) for c in "\r\n")
    ):
        return ImageRejected(Failure.TRUSTSTORE_MISSING)
    entries = [
        ("javax.net.ssl.trustStore", str(truststore)),
        ("http.nonProxyHosts", "localhost|127.*|[::1]"),
    ]
    for protocol in ("https", "http"):
        raw = environment.get(protocol.upper() + "_PROXY") or environment.get(protocol + "_proxy")
        if not raw:
            continue
        try:
            proxy = urlparse(raw)
            if (
                any(c in raw for c in "\r\n")
                or proxy.scheme != "http"
                or not proxy.hostname
                or proxy.username
                or proxy.password
            ):
                return ImageRejected(Failure.INVALID_PROXY)
            entries.extend(
                (
                    (protocol + ".proxyHost", proxy.hostname),
                    (protocol + ".proxyPort", str(proxy.port or 80)),
                )
            )
        except ValueError:
            return ImageRejected(Failure.INVALID_PROXY)
    return NetworkProperties(tuple(entries))


def configure_network(
    pin: ImagePin, settings: NetworkProperties
) -> ConfiguredNetwork | ImageRejected:
    directory = pin.root / "state/home/.gradle"
    path = directory / "gradle.properties"
    if directory.resolve() != directory or path.is_symlink():
        return ImageRejected(Failure.NETWORK_STATE_CONFLICT)
    begin = "# BEGIN Kast cloud task network"
    end = "# END Kast cloud task network"
    existing = path.read_text() if path.exists() else ""
    if existing.count(begin) != existing.count(end) or existing.count(begin) > 1:
        return ImageRejected(Failure.NETWORK_STATE_CONFLICT)
    retained = re.sub(
        r"(?ms)^" + re.escape(begin) + r"\n.*?^" + re.escape(end) + r"\n?", "", existing
    )
    if begin in retained or end in retained:
        return ImageRejected(Failure.NETWORK_STATE_CONFLICT)
    directory.mkdir(parents=True, exist_ok=True)
    _ = path.write_text(
        retained.rstrip()
        + "\n"
        + begin
        + "\n"
        + "".join("systemProp." + key + "=" + value + "\n" for key, value in settings.entries)
        + end
        + "\n"
    )
    return ConfiguredNetwork(path, settings)


def prepare_workspace(
    pin: ImagePin, workspace: Path, settings: NetworkProperties
) -> PreparedWorkspace | ImageRejected:
    wrapper = workspace / "gradlew"
    java = pin.root / "runtime/kast-analysis-host/jbr"
    if (
        not workspace.is_absolute()
        or workspace.resolve() != workspace
        or wrapper.is_symlink()
        or not wrapper.is_file()
        or not os.access(wrapper, os.X_OK)
    ):
        return ImageRejected(Failure.INVALID_WORKSPACE)
    if not (java / "bin/java").is_file():
        return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
    environment = dict(os.environ)
    environment.update(JAVA_HOME=str(java), GRADLE_USER_HOME=str(pin.root / "state/home/.gradle"))
    environment["GRADLE_OPTS"] = (
        environment.get("GRADLE_OPTS", "")
        + " "
        + shlex.join("-D" + key + "=" + value for key, value in settings.entries)
    )
    try:
        result = subprocess.run(
            [str(wrapper), "--version"],
            cwd=workspace,
            env=environment,
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return ImageRejected(Failure.GRADLE_FAILED)
    except OSError:
        return ImageRejected(Failure.GRADLE_FAILED)
    log = pin.root / "state/log/cloud-gradle-bootstrap.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    _ = log.write_text((result.stdout + result.stderr)[-65536:])
    version = re.search(r"(?m)^Gradle (\S+)$", result.stdout)
    if result.returncode != 0 or version is None:
        return ImageRejected(Failure.GRADLE_FAILED, exitCode=result.returncode)
    return PreparedWorkspace(str(pin.root), str(workspace), version[1])


def admit_pin(root: Path, version: str, digest: str) -> ImagePin | ImageRejected:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not re.fullmatch(
        r"[a-f0-9]{64}", digest
    ):
        return ImageRejected(Failure.INVALID_PIN)
    try:
        safe_root = root.is_absolute() and root.resolve() == root and root != Path("/")
    except (OSError, RuntimeError):
        return ImageRejected(Failure.UNSAFE_ROOT)
    if not safe_root:
        return ImageRejected(Failure.UNSAFE_ROOT)
    return ImagePin(root, version, digest)


def verify(pin: ImagePin) -> VerifiedImage | ImageRejected:
    root = pin.root
    try:
        marker = root / ".kast-headless-owned"
        receipt = root / "bundle-installation.json"
        command = root / "bin/kast"
        if any(path.is_symlink() for path in (root, marker, receipt, command)):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        if marker.read_text() != "kast-managed-headless-v1\n":
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        record: object = json.loads(receipt.read_text())
        if not object_fields(record) or not isinstance(record.get("bundleSha256"), str):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        if record["bundleSha256"] != pin.sha256:
            return ImageRejected(Failure.PIN_MISMATCH)
        if not command.is_file() or not os.access(command, os.X_OK):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        observed = subprocess.run(
            [str(command), "--version"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, ValueError):
        return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
    except subprocess.TimeoutExpired:
        return ImageRejected(Failure.VERSION_FAILED)
    if observed.returncode != 0 or observed.stdout.strip() != pin.version:
        return ImageRejected(Failure.VERSION_FAILED)
    return VerifiedImage(str(root), pin.version, pin.sha256)


def prepare(
    pin: ImagePin, archive: Path
) -> VerifiedImage | ImageRejected | BundleRejected | Rejected:
    if pin.root.exists() or pin.root.is_symlink():
        # A matching installation is reusable. Never replace partial or foreign state.
        return verify(pin)
    installed = install_bundle(archive, pin.sha256, pin.root, pin.version)
    if not isinstance(installed, BundleInstalled):
        return installed
    return verify(pin)


def activation(verified: VerifiedImage) -> str:
    # Proxy, CA, HOME and JAVA_HOME values belong to the current task, not this image.
    return "export PATH=" + shlex.quote(str(Path(verified.root) / "bin")) + ':"$PATH"\n'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("command", choices=("install", "verify", "activate", "prepare"))
    _ = parser.add_argument("--root", type=Path, required=True)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--sha256", required=True)
    _ = parser.add_argument("--archive", type=Path)
    _ = parser.add_argument("--workspace", type=Path)
    args = parser.parse_args()
    if args.command == "install" and args.archive is None:
        parser.error("install requires --archive")
    if args.command != "install" and args.archive is not None:
        parser.error("--archive is only valid for install")
    if (args.command == "prepare") != (args.workspace is not None):
        parser.error("prepare requires --workspace; other commands do not accept it")
    result: ImagePin | VerifiedImage | PreparedWorkspace | ImageRejected | BundleRejected | Rejected
    if (platform.system(), platform.machine()) != ("Linux", "x86_64"):
        result = ImageRejected(Failure.UNSUPPORTED_PLATFORM)
    else:
        result = admit_pin(args.root, args.version, args.sha256)
        if isinstance(result, ImagePin):
            pin = result
            try:
                result = (
                    prepare(result, args.archive) if args.command == "install" else verify(result)
                )
                if isinstance(result, VerifiedImage) and args.command in {"activate", "prepare"}:
                    settings = network_properties(os.environ)
                    if isinstance(settings, ImageRejected):
                        result = settings
                    else:
                        configured = configure_network(pin, settings)
                        if isinstance(configured, ImageRejected):
                            result = configured
                        elif args.command == "prepare":
                            result = prepare_workspace(pin, args.workspace, settings)
            except OSError:
                result = ImageRejected(Failure.IO_FAILED)
    if isinstance(result, VerifiedImage) and args.command == "activate":
        print(activation(result), end="")
    else:
        print(
            json.dumps(asdict(result)),
            file=sys.stderr if args.command == "activate" else sys.stdout,
        )
    return 0 if isinstance(result, (VerifiedImage, PreparedWorkspace)) else 1


if __name__ == "__main__":
    raise SystemExit(main())
