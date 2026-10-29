#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["defusedxml==0.7.1", "types-defusedxml==0.7.0.20260504"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
# pyright: strict
"""Stage a compatible local Host bundle for IntelliJ's native automatic reload."""

from __future__ import annotations

import argparse
import ctypes
import fcntl
import hashlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
import xml.etree.ElementTree as XML
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

from defusedxml import ElementTree as SafeXML
from defusedxml.common import DefusedXmlException

PLUGIN_ID = "io.github.amichne.kast.ide-hosted"
PLUGIN_DIRECTORY = "kast-ide-hosted"
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024


class Failure(StrEnum):
    PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED"
    OWNERSHIP_UNPROVEN = "OWNERSHIP_UNPROVEN"
    ARCHIVE_REJECTED = "ARCHIVE_REJECTED"
    DESCRIPTOR_CHANGED = "DESCRIPTOR_CHANGED"
    CONTRACT_CHANGED = "CONTRACT_CHANGED"
    LAYOUT_CHANGED = "LAYOUT_CHANGED"
    AUTO_RELOAD_DISABLED = "AUTO_RELOAD_DISABLED"
    CONCURRENT_CHANGE = "CONCURRENT_CHANGE"
    IO_FAILURE = "IO_FAILURE"


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Failure
    type: str = "REJECTED"


@dataclass(frozen=True, slots=True)
class Jar:
    name: str
    content: bytes


@dataclass(frozen=True, slots=True)
class Bundle:
    jars: tuple[Jar, ...]
    host_jar: str
    version: str
    descriptor: bytes
    contract: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Replacement:
    jars: tuple[Jar, ...]
    version: str
    previous_digest: str


@dataclass(frozen=True, slots=True)
class Staged:
    version: str
    backup: str
    digest: str
    type: str = "STAGED"
    next_action: str = "Return to IntelliJ or use File > Reload All from Disk. Request a fresh Kast query and examine its Host identity."


def bundle(jars: tuple[Jar, ...]) -> Bundle | Rejected:
    names = [jar.name for jar in jars]
    if not jars or len(names) != len(set(names)):
        return Rejected(Failure.ARCHIVE_REJECTED)
    if any(Path(name).name != name or not name.endswith(".jar") for name in names):
        return Rejected(Failure.ARCHIVE_REJECTED)
    hosts = [jar for jar in jars if jar.name.startswith("kast-ide-hosted-")]
    if len(hosts) != 1:
        return Rejected(Failure.ARCHIVE_REJECTED)
    host = hosts[0]
    try:
        with zipfile.ZipFile(io.BytesIO(host.content)) as archive:
            descriptor = SafeXML.fromstring(archive.read("META-INF/plugin.xml"))
            properties = archive.read("kast-hosted-query.properties").decode("utf-8")
        version = descriptor.find("version")
        if descriptor.findtext("id") != PLUGIN_ID or version is None or not version.text:
            return Rejected(Failure.ARCHIVE_REJECTED)
        identity = version.text
        version.text = "LOCAL_DEVELOPMENT_VERSION"
        contract = tuple(
            sorted(line for line in properties.splitlines() if not line.startswith("version="))
        )
        if not contract or not any(line.startswith("schemaDigest=") for line in contract):
            return Rejected(Failure.ARCHIVE_REJECTED)
        return Bundle(jars, host.name, identity, XML.tostring(descriptor), contract)
    except (KeyError, UnicodeDecodeError, XML.ParseError, DefusedXmlException, zipfile.BadZipFile):
        return Rejected(Failure.ARCHIVE_REJECTED)


def digest(jars: tuple[Jar, ...]) -> str:
    result = hashlib.sha256()
    for jar in sorted(jars, key=lambda item: item.name):
        result.update(jar.name.encode())
        result.update(b"\0")
        result.update(hashlib.sha256(jar.content).digest())
    return result.hexdigest()


def plan(current: Bundle, candidate: Bundle) -> Replacement | Rejected:
    if current.descriptor != candidate.descriptor:
        return Rejected(Failure.DESCRIPTOR_CHANGED)
    if current.contract != candidate.contract:
        return Rejected(Failure.CONTRACT_CHANGED)
    mapped = tuple(
        Jar(current.host_jar if jar.name == candidate.host_jar else jar.name, jar.content)
        for jar in candidate.jars
    )
    if {jar.name for jar in mapped} != {jar.name for jar in current.jars}:
        return Rejected(Failure.LAYOUT_CHANGED)
    return Replacement(mapped, candidate.version, digest(current.jars))


def read_archive(path: Path) -> Bundle | Rejected:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = [entry for entry in archive.infolist() if not entry.is_dir()]
            if sum(entry.file_size for entry in entries) > MAX_ARCHIVE_BYTES:
                return Rejected(Failure.ARCHIVE_REJECTED)
            prefix = f"{PLUGIN_DIRECTORY}/lib/"
            if any(not entry.filename.startswith(prefix) for entry in entries):
                return Rejected(Failure.ARCHIVE_REJECTED)
            return bundle(
                tuple(
                    Jar(entry.filename.removeprefix(prefix), archive.read(entry))
                    for entry in entries
                )
            )
    except (OSError, zipfile.BadZipFile):
        return Rejected(Failure.ARCHIVE_REJECTED)


def owned(path: Path, *, directory: bool) -> bool:
    info = path.lstat()
    kind = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    return kind and info.st_uid == os.getuid()


def read_installed(lib: Path) -> Bundle | Rejected:
    try:
        if not all(owned(path, directory=True) for path in (lib.parent.parent, lib.parent, lib)):
            return Rejected(Failure.OWNERSHIP_UNPROVEN)
        paths = sorted(lib.iterdir())
        if not all(owned(path, directory=False) for path in paths):
            return Rejected(Failure.OWNERSHIP_UNPROVEN)
        return bundle(tuple(Jar(path.name, path.read_bytes()) for path in paths))
    except OSError:
        return Rejected(Failure.IO_FAILURE)


def exchange(staged: Path, installed: Path) -> Rejected | None:
    """macOS RENAME_SWAP replaces the complete directory in one filesystem operation."""
    if sys.platform != "darwin":
        return Rejected(Failure.PLATFORM_UNSUPPORTED)
    rename = ctypes.CDLL(None, use_errno=True).renamex_np
    rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(os.fsencode(staged), os.fsencode(installed), 2) != 0:
        return Rejected(Failure.IO_FAILURE)
    return None


def apply_replacement(lib: Path, replacement: Replacement, recovery: Path) -> Staged | Rejected:
    try:
        recovery.mkdir(mode=0o700, exist_ok=True)
        if not owned(recovery, directory=True):
            return Rejected(Failure.OWNERSHIP_UNPROVEN)
        with (recovery / "reload.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            current = read_installed(lib)
            if isinstance(current, Rejected):
                return current
            if digest(current.jars) != replacement.previous_digest:
                return Rejected(Failure.CONCURRENT_CHANGE)
            stage = Path(tempfile.mkdtemp(prefix="bundle-", dir=recovery))
            for jar in replacement.jars:
                _ = (stage / jar.name).write_bytes(jar.content)
            rejected = exchange(stage, lib)
            if rejected is not None:
                return rejected
            return Staged(replacement.version, str(stage), digest(replacement.jars))
    except OSError:
        return Rejected(Failure.IO_FAILURE)


def reload_archive(archive: Path, plugins: Path) -> Staged | Rejected:
    if sys.platform != "darwin":
        return Rejected(Failure.PLATFORM_UNSUPPORTED)
    try:
        options = (plugins.parent / "idea.vmoptions").read_text().splitlines()
        if "-Didea.auto.reload.plugins=true" not in options:
            return Rejected(Failure.AUTO_RELOAD_DISABLED)
    except OSError:
        return Rejected(Failure.AUTO_RELOAD_DISABLED)
    current = read_installed(plugins / PLUGIN_DIRECTORY / "lib")
    if isinstance(current, Rejected):
        return current
    candidate = read_archive(archive)
    if isinstance(candidate, Rejected):
        return candidate
    replacement = plan(current, candidate)
    if isinstance(replacement, Rejected):
        return replacement
    return apply_replacement(
        plugins / PLUGIN_DIRECTORY / "lib", replacement, plugins.parent / ".kast-plugin-reload"
    )


def main(arguments: list[str]) -> int:
    if arguments == ["--self-test"]:
        suite = unittest.defaultTestLoader.loadTestsFromName("test_reload_hosted")
        if suite.countTestCases() == 0:
            return 1
        return 0 if unittest.TextTestRunner().run(suite).wasSuccessful() else 1
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--archive", required=True, type=Path)
    _ = parser.add_argument("--plugins-dir", required=True, type=Path)
    args = parser.parse_args(arguments)
    archive: Path = args.archive
    plugins: Path = args.plugins_dir
    outcome = reload_archive(archive, plugins)
    print(json.dumps(asdict(outcome)))
    return 1 if isinstance(outcome, Rejected) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
