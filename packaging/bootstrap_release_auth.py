"""Authenticate published code before the standalone bootstrap imports that code.

This stdlib-only prelude is embedded verbatim in install.sh. It establishes the
catalog/promotion/candidate and Control-byte binding; installed ownership and
runtime effects remain with the authenticated managed installer.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tarfile
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import TYPE_CHECKING, Literal

if TYPE_CHECKING:
    from typing import TypeGuard

MAX_RECORD = 65_536
MAX_ENTRIES = 16_384
MAX_PAYLOAD = 1_073_741_824
REPOSITORY = "amichne/kast"


class Failure(str, Enum):
    CATALOG_INVALID = "CATALOG_INVALID"
    SOURCE_CHANGED = "SOURCE_CHANGED"
    HOST_TARGET_UNAVAILABLE = "HOST_TARGET_UNAVAILABLE"
    QUALIFICATION_UNPROVEN = "QUALIFICATION_UNPROVEN"
    CONTROL_MISMATCH = "CONTROL_MISMATCH"
    FILESYSTEM_REJECTED = "FILESYSTEM_REJECTED"


@dataclass(frozen=True)
class Reference:
    name: str
    sha256: str


@dataclass(frozen=True)
class Target:
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str
    record: Reference


@dataclass(frozen=True)
class Identity:
    releaseTag: str
    version: str
    buildRevision: str
    sourceTree: str
    mainRevision: str


@dataclass(frozen=True)
class Entry:
    mainRevision: str
    buildRevision: str
    sourceTree: str
    releaseTag: str
    version: str
    platformTargets: tuple[str, ...]
    candidate: Reference
    promotion: Reference
    hostTargets: tuple[Target, Target]


@dataclass(frozen=True)
class Policy:
    type: Literal["TrackMain"] = "TrackMain"


@dataclass(frozen=True)
class Selection:
    identity: Identity
    policy: Policy
    catalogEntry: Entry
    type: Literal["Main"] = "Main"


@dataclass(frozen=True)
class Artifact:
    name: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class CodeAuthenticated:
    selection: Selection
    control: Artifact


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def positive(value: object) -> TypeGuard[int]:
    return type(value) is int and value > 0


def digest(value: object, length: int = 64) -> TypeGuard[str]:
    return (
        isinstance(value, str)
        and re.fullmatch(rf"[0-9a-f]{{{length}}}", value) is not None
        and value != "0" * length
    )


def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def document(raw: bytes) -> dict[object, object] | Failure:
    if len(raw) > MAX_RECORD:
        return Failure.QUALIFICATION_UNPROVEN
    try:
        value: object = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    except (ValueError, UnicodeError, RecursionError):
        return Failure.QUALIFICATION_UNPROVEN
    return value if mapping(value) else Failure.QUALIFICATION_UNPROVEN


def reference(value: object, name: str) -> Reference | Failure:
    if (
        not mapping(value)
        or set(value) != {"name", "sha256"}
        or value["name"] != name
        or not digest(value["sha256"])
    ):
        return Failure.CATALOG_INVALID
    return Reference(name, value["sha256"])


def targets(value: object, version: str) -> tuple[Target, Target] | Failure:
    if not sequence(value) or len(value) != 2:
        return Failure.CATALOG_INVALID
    result: list[Target] = []
    for index, line in enumerate(("262", "263")):
        item = value[index]
        if not mapping(item) or set(item) != {
            "intellijReleaseLine",
            "ideBuild",
            "kotlinPluginBuild",
            "record",
        }:
            return Failure.CATALOG_INVALID
        ide, kotlin = item["ideBuild"], item["kotlinPluginBuild"]
        name = (
            f"kast-host-release-v{version}.json"
            if line == "262"
            else f"kast-host-release-v{version}-idea-263.json"
        )
        record = reference(item["record"], name)
        if (
            item["intellijReleaseLine"] != line
            or not isinstance(ide, str)
            or len(ide) > 64
            or re.fullmatch(rf"{line}\.[0-9]+(?:\.[0-9]+)*", ide) is None
            or not isinstance(kotlin, str)
            or len(kotlin) > 80
            or re.fullmatch(rf"{line}\.[0-9]+(?:\.[0-9]+)*-IJ", kotlin) is None
            or isinstance(record, Failure)
        ):
            return Failure.CATALOG_INVALID
        result.append(Target(line, ide, kotlin, record))
    return result[0], result[1]


def entry(value: object) -> Entry | Failure:
    if not mapping(value) or set(value) != {
        "mainRevision",
        "buildRevision",
        "sourceTree",
        "releaseTag",
        "version",
        "platformTargets",
        "candidate",
        "promotion",
        "hostTargets",
    }:
        return Failure.CATALOG_INVALID
    main, build, tree, tag, version = (
        value[k] for k in ("mainRevision", "buildRevision", "sourceTree", "releaseTag", "version")
    )
    platforms = value["platformTargets"]
    if (
        not all(digest(x, 40) for x in (main, build, tree))
        or not isinstance(main, str)
        or not isinstance(build, str)
        or not isinstance(tree, str)
        or not isinstance(version, str)
        or len(version) > 64
        or re.fullmatch(r"0\.0\.[1-9][0-9]*", version) is None
        or tag != "developer-v" + version
        or not isinstance(tag, str)
        or not sequence(platforms)
        or not platforms
    ):
        return Failure.CATALOG_INVALID
    admitted_platforms: list[str] = []
    for platform in platforms:
        if (
            not isinstance(platform, str)
            or re.fullmatch(r"[a-z0-9]+-[a-z0-9]+", platform) is None
            or platform in admitted_platforms
        ):
            return Failure.CATALOG_INVALID
        admitted_platforms.append(platform)
    candidate = reference(value["candidate"], "candidate-v2.json")
    promotion = reference(value["promotion"], "promotion-v2.json")
    hosts = targets(value["hostTargets"], version)
    if (
        isinstance(candidate, Failure)
        or isinstance(promotion, Failure)
        or isinstance(hosts, Failure)
    ):
        return Failure.CATALOG_INVALID
    return Entry(
        main, build, tree, tag, version, tuple(admitted_platforms), candidate, promotion, hosts
    )


def select_catalog(
    raw: bytes, tag: str, version: str, build: str, line: str
) -> Selection | Failure:
    catalog = document(raw)
    if (
        isinstance(catalog, Failure)
        or set(catalog) != {"schemaVersion", "builds"}
        or type(catalog["schemaVersion"]) is not int
        or catalog["schemaVersion"] != 2
    ):
        return Failure.CATALOG_INVALID
    builds = catalog["builds"]
    if not sequence(builds) or not 1 <= len(builds) <= 5:
        return Failure.CATALOG_INVALID
    admitted: list[Entry] = []
    for item in builds:
        parsed = entry(item)
        if isinstance(parsed, Failure) or any(
            old.mainRevision == parsed.mainRevision for old in admitted
        ):
            return Failure.CATALOG_INVALID
        admitted.append(parsed)
    selected = admitted[0]
    if (selected.releaseTag, selected.version, selected.buildRevision) != (tag, version, build):
        return Failure.SOURCE_CHANGED
    if line not in ("262", "263") or "macos-aarch64" not in selected.platformTargets:
        return Failure.HOST_TARGET_UNAVAILABLE
    return Selection(
        Identity(tag, version, build, selected.sourceTree, selected.mainRevision),
        Policy(),
        selected,
    )


def payload_names(version: str, dual: bool) -> frozenset[str]:
    bases = {
        f"kast-control-v{version}-macos-aarch64.tar.gz",
        f"kast-ide-hosted-v{version}-idea-262.zip",
        f"kast-plugin-v{version}.zip",
        f"kast-marketplace-v{version}.zip",
        f"kast-control-release-v{version}.json",
        f"kast-host-release-v{version}.json",
        "host-installation.py",
        f"kast-hosted-catalog-v{version}.json",
        f"kast-module-knowledge-v{version}.json",
        f"kast-sbom-v{version}.cdx.json",
    }
    if dual:
        bases |= {
            f"kast-ide-hosted-v{version}-idea-263.zip",
            f"kast-host-release-v{version}-idea-263.json",
        }
    return frozenset(bases | {name + ".sha256" for name in bases})


def inventory(value: object) -> dict[str, Artifact] | Failure:
    if not sequence(value) or not 1 <= len(value) <= 32:
        return Failure.QUALIFICATION_UNPROVEN
    result: dict[str, Artifact] = {}
    for item in value:
        if not mapping(item) or set(item) != {"name", "sha256", "bytes"}:
            return Failure.QUALIFICATION_UNPROVEN
        name, sha, size = item["name"], item["sha256"], item["bytes"]
        if (
            not isinstance(name, str)
            or re.fullmatch(r"[A-Za-z0-9_.-]{1,160}", name) is None
            or name in (".", "..")
            or name in result
            or not digest(sha)
            or not positive(size)
            or size > MAX_PAYLOAD
        ):
            return Failure.QUALIFICATION_UNPROVEN
        result[name] = Artifact(name, sha, size)
    return result


def qualify(
    selected: Selection,
    candidate_raw: bytes,
    promotion_raw: bytes,
    legacy_candidate_raw: bytes,
    legacy_promotion_raw: bytes,
    control: Artifact,
) -> CodeAuthenticated | Failure:
    expected = selected.catalogEntry
    if (
        hashlib.sha256(candidate_raw).hexdigest() != expected.candidate.sha256
        or hashlib.sha256(promotion_raw).hexdigest() != expected.promotion.sha256
    ):
        return Failure.QUALIFICATION_UNPROVEN
    candidate, promotion, legacy, prior = (
        document(raw)
        for raw in (candidate_raw, promotion_raw, legacy_candidate_raw, legacy_promotion_raw)
    )
    if any(isinstance(x, Failure) for x in (candidate, promotion, legacy, prior)):
        return Failure.QUALIFICATION_UNPROVEN
    assert (
        not isinstance(candidate, Failure)
        and not isinstance(promotion, Failure)
        and not isinstance(legacy, Failure)
        and not isinstance(prior, Failure)
    )
    candidate_fields = {
        "schemaVersion",
        "repository",
        "sourceRevision",
        "sourceTree",
        "version",
        "runId",
        "runNumber",
        "runAttempt",
        "event",
        "platform",
        "toolchain",
        "assets",
    }
    promotion_fields = {
        "schemaVersion",
        "repository",
        "buildRevision",
        "mergedRevision",
        "sourceTree",
        "version",
        "buildRunId",
        "buildRunAttempt",
        "mainRunId",
        "mainRunAttempt",
        "candidateSha256",
    }
    if (
        set(candidate) != candidate_fields | {"hostTargets"}
        or set(legacy) != candidate_fields
        or set(promotion) != promotion_fields
        or set(prior) != promotion_fields
    ):
        return Failure.QUALIFICATION_UNPROVEN
    for current, schema in ((candidate, 2), (promotion, 2), (legacy, 1), (prior, 1)):
        if (
            type(current["schemaVersion"]) is not int
            or current["schemaVersion"] != schema
            or current["repository"] != REPOSITORY
            or current["version"] != expected.version
            or current["sourceTree"] != expected.sourceTree
        ):
            return Failure.QUALIFICATION_UNPROVEN
    if (
        candidate["sourceRevision"] != expected.buildRevision
        or candidate["platform"] != "macos-aarch64"
        or any(not positive(candidate[k]) for k in ("runId", "runNumber", "runAttempt"))
        or not isinstance(candidate["runNumber"], int)
        or candidate["runNumber"] > 2_147_483_647
        or candidate["version"] != f"0.0.{candidate['runNumber']}"
        or candidate["event"] not in ("pull_request", "merge_group", "push", "workflow_dispatch")
        or promotion["buildRevision"] != expected.buildRevision
        or promotion["mergedRevision"] != expected.mainRevision
        or promotion["candidateSha256"] != expected.candidate.sha256
        or promotion["buildRunId"] != candidate["runId"]
        or promotion["buildRunAttempt"] != candidate["runAttempt"]
        or any(
            not positive(promotion[k])
            for k in ("buildRunId", "buildRunAttempt", "mainRunId", "mainRunAttempt")
        )
        or prior["candidateSha256"] != hashlib.sha256(legacy_candidate_raw).hexdigest()
    ):
        return Failure.QUALIFICATION_UNPROVEN
    toolchain = candidate["toolchain"]
    if (
        not mapping(toolchain)
        or set(toolchain) != {"javaVersion", "vendor", "releaseSha256"}
        or any(
            not isinstance(value, str) or not 0 < len(value) <= 160
            for value in (toolchain["javaVersion"], toolchain["vendor"])
        )
        or not digest(toolchain["releaseSha256"])
    ):
        return Failure.QUALIFICATION_UNPROVEN
    if any(
        type(legacy[k]) is not type(candidate[k]) or legacy[k] != candidate[k]
        for k in candidate_fields - {"schemaVersion", "assets"}
    ) or any(
        type(prior[k]) is not type(promotion[k]) or prior[k] != promotion[k]
        for k in promotion_fields - {"schemaVersion", "candidateSha256"}
    ):
        return Failure.QUALIFICATION_UNPROVEN
    assets, old_assets = inventory(candidate["assets"]), inventory(legacy["assets"])
    hosts = targets(candidate["hostTargets"], expected.version)
    if (
        isinstance(assets, Failure)
        or isinstance(old_assets, Failure)
        or isinstance(hosts, Failure)
        or hosts != expected.hostTargets
    ):
        return Failure.QUALIFICATION_UNPROVEN
    if (
        frozenset(assets) != payload_names(expected.version, True)
        or frozenset(old_assets) != payload_names(expected.version, False)
        or any(assets.get(name) != item for name, item in old_assets.items())
        or any(assets[target.record.name].sha256 != target.record.sha256 for target in hosts)
    ):
        return Failure.QUALIFICATION_UNPROVEN
    if (
        control.name != f"kast-control-v{expected.version}-macos-aarch64.tar.gz"
        or assets.get(control.name) != control
    ):
        return Failure.CONTROL_MISMATCH
    return CodeAuthenticated(selected, control)


class TreeAdmission(str, Enum):
    ADMITTED = "ADMITTED"


def tree_file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(65_536):
            digest.update(data)
    return digest.hexdigest()


class TreeFailure(str, Enum):
    BOUNDS = "Control archive bounds"
    PATH = "Control archive path or type"
    PRODUCT_PATH = "Control product path"
    ARCHIVE_MODE = "Control archive mode"
    PRODUCT_MODE = "Control product mode"
    DIRECTORY = "Control product directory"
    SIZE = "Control product file size"
    ARCHIVE_FILE = "Control archive file"
    BYTES = "Control product bytes"
    ENTRY = "Control product entry"
    INVENTORY = "Control product inventory"
    FILESYSTEM = "Control filesystem unavailable"


@dataclass(frozen=True)
class TreeRejected:
    reason: TreeFailure
    path: str = ""


def admit_control_tree(archive_path: Path, product: Path) -> TreeAdmission | TreeRejected:
    names: set[str] = set()
    files: set[str] = set()
    total = 0
    try:
        with tarfile.open(archive_path, "r:gz") as archive:
            for member in archive:
                name = member.name.rstrip("/") if member.isdir() else member.name
                parts = name.split("/")
                total += member.size
                if len(names) >= MAX_ENTRIES or not 0 <= total <= MAX_PAYLOAD:
                    return TreeRejected(TreeFailure.BOUNDS)
                if (
                    name in names
                    or member.size < 0
                    or len(name) > 4096
                    or len(parts) > 32
                    or "\\" in name
                    or any(part in ("", ".", "..") for part in parts)
                    or parts[0] not in ("bin", "lib", "share")
                    or not (member.isdir() or member.isfile())
                ):
                    return TreeRejected(TreeFailure.PATH)
                names.add(name)
                path = product.joinpath(*parts)
                if path.resolve(strict=True) != path or path.is_symlink():
                    return TreeRejected(TreeFailure.PRODUCT_PATH, name)
                if not 0 <= member.mode <= 0o177777:
                    return TreeRejected(TreeFailure.ARCHIVE_MODE, name)
                permissions = stat.S_IMODE(member.mode)
                kind = stat.S_IFDIR if member.isdir() else stat.S_IFREG
                if permissions & ~0o777 or member.mode not in (permissions, kind | permissions):
                    return TreeRejected(TreeFailure.ARCHIVE_MODE, name)
                if stat.S_IMODE(path.stat().st_mode) != permissions:
                    return TreeRejected(TreeFailure.PRODUCT_MODE, name)
                if member.isdir():
                    if not path.is_dir():
                        return TreeRejected(TreeFailure.DIRECTORY, name)
                    continue
                if not path.is_file() or path.stat().st_size != member.size:
                    return TreeRejected(TreeFailure.SIZE, name)
                opened = archive.extractfile(member)
                if opened is None:
                    return TreeRejected(TreeFailure.ARCHIVE_FILE, name)
                digest = hashlib.sha256()
                with opened:
                    while block := opened.read(65_536):
                        digest.update(block)
                if tree_file_digest(path) != digest.hexdigest():
                    return TreeRejected(TreeFailure.BYTES, name)
                files.add(name)
        actual: set[str] = set()
        for index, path in enumerate(product.rglob("*"), 1):
            if index > MAX_ENTRIES or path.is_symlink() or not (path.is_file() or path.is_dir()):
                return TreeRejected(TreeFailure.ENTRY)
            if path.is_file():
                actual.add(path.relative_to(product).as_posix())
        return (
            TreeAdmission.ADMITTED
            if files and files == actual
            else TreeRejected(TreeFailure.INVENTORY)
        )
    except (OSError, ValueError, tarfile.TarError):
        return TreeRejected(TreeFailure.FILESYSTEM)


def read_record(path: Path) -> bytes:
    if (
        path.is_symlink()
        or not path.is_file()
        or path.stat().st_uid != os.getuid()
        or path.stat().st_size > MAX_RECORD
    ):
        raise OSError("record rejected")
    return path.read_bytes()


def read_control(path: Path) -> Artifact:
    if (
        path.is_symlink()
        or not path.is_file()
        or path.stat().st_uid != os.getuid()
        or not 0 < path.stat().st_size <= MAX_PAYLOAD
    ):
        raise OSError("Control rejected")
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(65_536):
            sha.update(block)
    return Artifact(path.name, sha.hexdigest(), path.stat().st_size)


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("phase", choices=("select", "authenticate", "readmit-control"))
    _ = parser.add_argument("--catalog", type=Path, required=True)
    _ = parser.add_argument("--catalog-sha256")
    _ = parser.add_argument("--tag", required=True)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--source", required=True)
    _ = parser.add_argument("--idea-line", required=True)
    _ = parser.add_argument("--output", type=Path)
    _ = parser.add_argument("--records", type=Path)
    _ = parser.add_argument("--control", type=Path)
    _ = parser.add_argument("--control-root", type=Path)
    _ = parser.add_argument("--print-selection-sha256", action="store_true")
    args = parser.parse_args(arguments)
    try:
        catalog_raw = read_record(args.catalog)
        selected = (
            Failure.SOURCE_CHANGED
            if args.catalog_sha256 is not None
            and (
                not digest(args.catalog_sha256)
                or hashlib.sha256(catalog_raw).hexdigest() != args.catalog_sha256
            )
            else select_catalog(catalog_raw, args.tag, args.version, args.source, args.idea_line)
        )
        outcome: Selection | CodeAuthenticated | Failure = selected
        if not isinstance(selected, Failure) and args.phase in ("authenticate", "readmit-control"):
            if (
                args.records is None
                or args.control is None
                or args.control.is_symlink()
                or not args.control.is_file()
                or args.control.stat().st_uid != os.getuid()
            ):
                outcome = Failure.FILESYSTEM_REJECTED
            else:
                candidate = read_record(args.records / "candidate-v2.json")
                promotion = read_record(args.records / "promotion-v2.json")
                legacy_candidate = read_record(args.records / "candidate.json")
                legacy_promotion = read_record(args.records / "promotion.json")
                outcome = qualify(
                    selected,
                    candidate,
                    promotion,
                    legacy_candidate,
                    legacy_promotion,
                    read_control(args.control),
                )
        if isinstance(outcome, Failure):
            print(f"kast-install: versioned release rejected ({outcome.value})", file=sys.stderr)
            return 1
        if args.phase == "readmit-control":
            if not isinstance(outcome, CodeAuthenticated) or args.control_root is None:
                raise ValueError("Control tree input unavailable")
            tree = admit_control_tree(args.control, args.control_root)
            if isinstance(tree, TreeRejected):
                print(
                    "kast-install: versioned Control tree rejected "
                    f"({Failure.CONTROL_MISMATCH.value}; {tree.reason.value} {tree.path})",
                    file=sys.stderr,
                )
                return 1
            return 0
        if args.output is None:
            raise ValueError("selection output unavailable")
        admitted = outcome.selection if isinstance(outcome, CodeAuthenticated) else outcome
        raw = json.dumps(asdict(admitted)).encode() + b"\n"
        with args.output.open("xb") as output:
            _ = output.write(raw)
        if args.print_selection_sha256:
            print(hashlib.sha256(raw).hexdigest())
        return 0
    except (OSError, ValueError):
        print(
            f"kast-install: versioned release rejected ({Failure.FILESYSTEM_REJECTED.value})",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
