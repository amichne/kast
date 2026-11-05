"""Local captured-pair admission before the existing public reset owner runs.

This module never discovers releases, starts children, signals processes, or
changes an installation. Its only output is a binding in the owned asset scratch.
The locked managed installer exposes this separate private command.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import bootstrap_release_auth as bootstrap
import managed_release as release

BINDING_NAME = "force-selection-binding.json"


TreeRejected = bootstrap.TreeRejected


def admit_control_tree(archive_path: Path, product: Path) -> release.Admission | TreeRejected:
    result = bootstrap.admit_control_tree(archive_path, product)
    return release.Admission.ADMITTED if result is bootstrap.TreeAdmission.ADMITTED else result


def owned_directory(path: Path) -> bool:
    return (
        path.is_absolute()
        and path.resolve() == path
        and not path.is_symlink()
        and path.is_dir()
        and path.stat().st_uid == os.getuid()
        and not stat.S_IMODE(path.stat().st_mode) & 0o022
    )


def observe_file(path: Path, limit: int) -> release.DownloadedFile | release.Failure:
    try:
        before = path.lstat()
        if (
            not path.is_absolute()
            or path.resolve() != path
            or not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.getuid()
            or before.st_nlink != 1
            or before.st_mode & 0o022
            or not 0 < before.st_size <= limit
        ):
            return release.Failure.ARTIFACT_INVALID
        sha = release.file_digest(path)
        after = path.lstat()
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
        ):
            return release.Failure.ARTIFACT_INVALID
        return release.DownloadedFile(path, sha, before.st_size)
    except OSError:
        return release.Failure.ARTIFACT_INVALID


def read_owned(path: Path, limit: int) -> bytes | release.Failure:
    observed = observe_file(path, limit)
    if isinstance(observed, release.Failure):
        return observed
    raw = path.read_bytes()
    return (
        raw
        if len(raw) == observed.bytes and hashlib.sha256(raw).hexdigest() == observed.sha256
        else release.Failure.ARTIFACT_INVALID
    )


def captured_selection(
    path: Path, expected_sha256: str | None = None
) -> release.MainSelection | release.Failure:
    raw = read_owned(path, release.MAXIMUM_RECORD_BYTES)
    if isinstance(raw, release.Failure):
        return raw
    if expected_sha256 is not None and (
        release.DIGEST.fullmatch(expected_sha256) is None
        or hashlib.sha256(raw).hexdigest() != expected_sha256
    ):
        return release.Failure.ARTIFACT_INVALID
    selected = release.decode_selection(raw)
    if not isinstance(selected, release.MainSelection) or not isinstance(
        selected.catalogEntry, release.MainEntryV2
    ):
        return release.Failure.SELECTOR_INVALID
    return selected


@dataclass(frozen=True)
class LocalRecords:
    assets: Path
    selection: release.MainSelection

    def read(self, url: str, limit: int) -> bytes | release.TransferFailure:
        prefix = release.RELEASE_URL + "/" + self.selection.releaseTag + "/"
        if not url.startswith(prefix):
            return release.TransferFailure.UNAVAILABLE
        name = url.removeprefix(prefix)
        if name not in release.payload_names(self.selection.version, True) | {
            "candidate.json",
            "promotion.json",
            "candidate-v2.json",
            "promotion-v2.json",
        }:
            return release.TransferFailure.UNAVAILABLE
        raw = read_owned(self.assets / name, limit)
        return release.TransferFailure.UNAVAILABLE if isinstance(raw, release.Failure) else raw

    def download(self, url: str, destination: Path, limit: int) -> release.TransferFailure:
        return release.TransferFailure.UNAVAILABLE


@dataclass(frozen=True)
class PublicationBinding:
    identity: release.BuildIdentity
    policy: release.TrackMain | release.PinnedBuild


@dataclass(frozen=True)
class AdmittedPair:
    binding: PublicationBinding
    control_archive: Path
    host_archive: Path
    host_record: Path


def admit_pair(
    selection: release.MainSelection, assets: Path, control: Path, line: str
) -> AdmittedPair | release.Failure:
    if (
        not isinstance(selection.catalogEntry, release.MainEntryV2)
        or not owned_directory(assets)
        or not owned_directory(control)
    ):
        return release.Failure.ARTIFACT_INVALID
    target = release.select_host_target(selection, line)
    if not isinstance(target, release.HostTarget):
        return release.Failure.HOST_TARGET_UNAVAILABLE
    control_name = f"kast-control-v{selection.version}-{release.PLATFORM}.tar.gz"
    host_name = f"kast-ide-hosted-v{selection.version}-idea-{line}.zip"
    source = LocalRecords(assets, selection)
    records: list[release.ComponentRecord] = []
    observed: list[release.DownloadedFile] = []
    for name, archive, record_line in (
        (f"kast-control-release-v{selection.version}.json", control_name, None),
        (target.record.name, host_name, line),
    ):
        raw = source.read(release.asset_url(selection, name), release.MAXIMUM_RECORD_BYTES)
        if isinstance(raw, release.TransferFailure):
            return release.Failure.ARTIFACT_INVALID
        record = release.parse_component(raw, selection.version, archive, record_line)
        if isinstance(record, release.Failure) or (
            record_line is not None and hashlib.sha256(raw).hexdigest() != target.record.sha256
        ):
            return release.Failure.ARTIFACT_INVALID
        if record.source_revision != selection.identity.buildRevision:
            return release.Failure.ARTIFACT_INVALID
        for asset_name, limit in ((name, release.MAXIMUM_RECORD_BYTES), (archive, record.bytes)):
            item = observe_file(assets / asset_name, limit)
            if isinstance(item, release.Failure):
                return item
            if asset_name == archive and (
                item.sha256 != record.sha256 or item.bytes != record.bytes
            ):
                return release.Failure.ARTIFACT_INVALID
            checksum = observe_file(assets / (asset_name + ".sha256"), 512)
            checksum_raw = read_owned(assets / (asset_name + ".sha256"), 512)
            if (
                isinstance(checksum, release.Failure)
                or checksum_raw != f"{item.sha256}  {asset_name}\n".encode()
            ):
                return release.Failure.ARTIFACT_INVALID
            observed.extend((item, checksum))
        records.append(record)
    control_record, host_record = records
    if control_record.contract != host_record.contract:
        return release.Failure.ARTIFACT_INVALID
    admitted = release.admit_main_records(selection, source, tuple(observed))
    if isinstance(admitted, release.Failure):
        return admitted
    if isinstance(admit_control_tree(assets / control_name, control), TreeRejected):
        return release.Failure.ARTIFACT_INVALID
    identity = release.inspect_control(control, selection, control_record)
    if isinstance(identity, release.Failure):
        return identity
    host_admission = release.inspect_host(
        assets / host_name, identity, control_record.contract, target
    )
    if isinstance(host_admission, release.Failure):
        return host_admission
    return AdmittedPair(
        PublicationBinding(identity, selection.policy),
        assets / control_name,
        assets / host_name,
        assets / target.record.name,
    )


def write_binding(pair: AdmittedPair, assets: Path) -> release.Admission | release.Failure:
    if assets != pair.control_archive.parent or not owned_directory(assets):
        return release.Failure.ARTIFACT_INVALID
    destination = assets / BINDING_NAME
    expected = json.dumps(asdict(pair.binding), separators=(",", ":")).encode() + b"\n"
    if os.path.lexists(destination):
        return (
            release.Admission.ADMITTED
            if read_owned(destination, release.MAXIMUM_RECORD_BYTES) == expected
            and stat.S_IMODE(destination.stat().st_mode) == 0o600
            else release.Failure.ARTIFACT_INVALID
        )
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        _ = output.write(expected)
    return release.Admission.ADMITTED


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("action", choices=("target", "admit"))
    _ = parser.add_argument("--selection", type=Path, required=True)
    _ = parser.add_argument("--selection-sha256", required=True)
    _ = parser.add_argument("--idea-line", required=True)
    _ = parser.add_argument("--assets", type=Path)
    _ = parser.add_argument("--control-root", type=Path)
    args = parser.parse_args(arguments)
    failure: release.Failure = release.Failure.ARTIFACT_INVALID
    try:
        selection = captured_selection(args.selection, args.selection_sha256)
        if not isinstance(selection, release.Failure):
            target = release.select_host_target(selection, args.idea_line)
            if isinstance(target, release.HostTarget):
                if args.action == "target":
                    print(target.record.name)
                    return 0
                if args.assets is not None and args.control_root is not None:
                    pair = admit_pair(selection, args.assets, args.control_root, args.idea_line)
                    if isinstance(pair, AdmittedPair):
                        bound = write_binding(pair, args.assets)
                        if bound is release.Admission.ADMITTED:
                            print("Captured local Control/Host pair admitted; no reset invoked.")
                            return 0
                        failure = bound
                    else:
                        failure = pair
            else:
                failure = release.Failure.HOST_TARGET_UNAVAILABLE
        else:
            failure = selection
    except (OSError, ValueError):
        failure = release.Failure.ARTIFACT_INVALID
    print(f"kast-install: captured force pair rejected ({failure.value})", file=sys.stderr)
    return 1
