"""The public bootstrap must authenticate code before executing a new installer."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

import bootstrap_release_auth as auth
import managed_release as release

VERSION = "0.0.123"
BUILD, MAIN, TREE = "a" * 40, "b" * 40, "c" * 40
CONTROL = b"case-owned code payload; archive semantics are a separate admission"


@dataclass(frozen=True)
class Toolchain:
    javaVersion: str = "25.0.2"
    vendor: str = "case-owned vendor"
    releaseSha256: str = "d" * 64


@dataclass(frozen=True)
class Candidate:
    assets: tuple[auth.Artifact, ...]
    schemaVersion: int = 1
    repository: str = auth.REPOSITORY
    sourceRevision: str = BUILD
    sourceTree: str = TREE
    version: str = VERSION
    runId: int = 1234
    runNumber: int = 123
    runAttempt: int = 1
    event: str = "push"
    platform: str = "macos-aarch64"
    toolchain: Toolchain = Toolchain()


@dataclass(frozen=True)
class CandidateV2(Candidate):
    hostTargets: tuple[auth.Target, auth.Target] = field(kw_only=True)
    schemaVersion: int = 2


@dataclass(frozen=True)
class Promotion:
    candidateSha256: str
    schemaVersion: int = 1
    repository: str = auth.REPOSITORY
    buildRevision: str = BUILD
    mergedRevision: str = MAIN
    sourceTree: str = TREE
    version: str = VERSION
    buildRunId: int = 1234
    buildRunAttempt: int = 1
    mainRunId: int = 5678
    mainRunAttempt: int = 1


@dataclass(frozen=True)
class Catalog:
    builds: tuple[auth.Entry, ...]
    schemaVersion: int = 2


type Document = Candidate | Promotion | Catalog | auth.Selection


def encoded(value: Document) -> bytes:
    return json.dumps(asdict(value)).encode()


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


@dataclass(frozen=True)
class Fixture:
    selected: auth.Selection
    catalog: bytes
    records: tuple[bytes, bytes, bytes, bytes]
    control: auth.Artifact


def fixture(control: bytes = CONTROL) -> Fixture:
    control_name = f"kast-control-v{VERSION}-macos-aarch64.tar.gz"
    payloads = {
        name: control if name == control_name else name.encode()
        for name in auth.payload_names(VERSION, True)
    }
    targets = tuple(
        auth.Target(line, line + ".1", line + ".1-IJ", auth.Reference(name, sha(payloads[name])))
        for line, name in (
            ("262", f"kast-host-release-v{VERSION}.json"),
            ("263", f"kast-host-release-v{VERSION}-idea-263.json"),
        )
    )
    hosts = targets[0], targets[1]
    assets = tuple(
        auth.Artifact(name, sha(raw), len(raw)) for name, raw in sorted(payloads.items())
    )
    old_assets = tuple(item for item in assets if item.name in auth.payload_names(VERSION, False))
    legacy = encoded(Candidate(old_assets))
    candidate = encoded(CandidateV2(assets, hostTargets=hosts))
    prior = encoded(Promotion(sha(legacy)))
    promotion = encoded(Promotion(sha(candidate), schemaVersion=2))
    entry = auth.Entry(
        MAIN,
        BUILD,
        TREE,
        "developer-v" + VERSION,
        VERSION,
        ("macos-aarch64",),
        auth.Reference("candidate-v2.json", sha(candidate)),
        auth.Reference("promotion-v2.json", sha(promotion)),
        hosts,
    )
    selected = auth.Selection(
        auth.Identity(entry.releaseTag, VERSION, BUILD, TREE, MAIN), auth.Policy(), entry
    )
    return Fixture(
        selected,
        encoded(Catalog((entry,))),
        (candidate, promotion, legacy, prior),
        auth.Artifact(control_name, sha(control), len(control)),
    )


class BootstrapAuthenticationTests(unittest.TestCase):
    def test_paired_publication_binds_control_bytes_and_full_native_capture(self) -> None:
        case = fixture()
        selected = auth.select_catalog(case.catalog, "developer-v" + VERSION, VERSION, BUILD, "263")
        self.assertEqual(case.selected, selected)
        assert isinstance(selected, auth.Selection)
        result = auth.qualify(selected, *case.records, case.control)
        self.assertEqual(auth.CodeAuthenticated(selected, case.control), result)
        native = release.decode_selection(encoded(selected))
        assert isinstance(native, release.MainSelection), native
        self.assertIsInstance(native.catalogEntry, release.MainEntryV2)
        self.assertEqual(MAIN, native.identity.mainRevision)
        self.assertEqual(BUILD, native.identity.buildRevision)

    def test_latest_pointer_race_and_unknown_target_are_finite(self) -> None:
        case = fixture()
        self.assertEqual(
            auth.Failure.SOURCE_CHANGED,
            auth.select_catalog(case.catalog, "developer-v" + VERSION, VERSION, MAIN, "263"),
        )
        self.assertEqual(
            auth.Failure.HOST_TARGET_UNAVAILABLE,
            auth.select_catalog(case.catalog, "developer-v" + VERSION, VERSION, BUILD, "264"),
        )

    def test_control_name_size_and_actual_digest_are_all_bound(self) -> None:
        case = fixture()
        for control in (
            replace(case.control, sha256="e" * 64),
            replace(case.control, bytes=1),
            replace(case.control, name="other.tar.gz"),
        ):
            with self.subTest(control=control):
                self.assertEqual(
                    auth.Failure.CONTROL_MISMATCH,
                    auth.qualify(case.selected, *case.records, control),
                )

    def test_candidate_alone_and_mismatched_legacy_promotion_do_not_authorize_code(self) -> None:
        case = fixture()
        for records in (
            (case.records[0], b"", case.records[2], case.records[3]),
            (case.records[0], case.records[1], case.records[2], encoded(Promotion("e" * 64))),
            (
                case.records[0],
                case.records[1],
                case.records[2],
                encoded(Promotion(sha(case.records[2]), mainRunId=999)),
            ),
        ):
            with self.subTest(records=records):
                self.assertEqual(
                    auth.Failure.QUALIFICATION_UNPROVEN,
                    auth.qualify(case.selected, *records, case.control),
                )

    def test_extra_malformed_duplicate_and_zero_catalog_facts_reject(self) -> None:
        case = fixture()
        for raw in (
            b"not json",
            case.catalog.replace(b'"schemaVersion": 2', b'"schemaVersion": true'),
            case.catalog.replace(b'"schemaVersion": 2', b'"schemaVersion": 2, "schemaVersion": 2'),
            case.catalog.replace(BUILD.encode(), b"0" * 40),
            case.catalog[:-1] + b',"extra":1}',
            b" " * (auth.MAX_RECORD + 1),
        ):
            with self.subTest(raw=raw[:80]):
                self.assertEqual(
                    auth.Failure.CATALOG_INVALID,
                    auth.select_catalog(raw, "developer-v" + VERSION, VERSION, BUILD, "262"),
                )

    def test_catalog_sdk_pair_or_record_identity_mismatch_cannot_authorize_code(self) -> None:
        case = fixture()
        for target in (
            replace(case.selected.catalogEntry.hostTargets[1], ideBuild="263.999"),
            replace(
                case.selected.catalogEntry.hostTargets[1],
                record=auth.Reference(f"kast-host-release-v{VERSION}-idea-263.json", "e" * 64),
            ),
        ):
            entry = replace(
                case.selected.catalogEntry,
                hostTargets=(case.selected.catalogEntry.hostTargets[0], target),
            )
            selected = replace(case.selected, catalogEntry=entry)
            self.assertEqual(
                auth.Failure.QUALIFICATION_UNPROVEN,
                auth.qualify(selected, *case.records, case.control),
            )

    def test_cli_never_publishes_authority_on_failure_or_overwrites_evidence(self) -> None:
        case = fixture()
        with tempfile.TemporaryDirectory(prefix="kast-bootstrap-auth-") as directory:
            root = Path(directory)
            catalog, output = root / "catalog.json", root / "selection.json"
            _ = catalog.write_bytes(case.catalog)
            arguments = [
                "authenticate",
                "--catalog",
                str(catalog),
                "--tag",
                "developer-v" + VERSION,
                "--version",
                VERSION,
                "--source",
                BUILD,
                "--idea-line",
                "263",
                "--output",
                str(output),
                "--records",
                str(root),
                "--control",
                str(root / case.control.name),
            ]
            for name, raw in zip(
                ("candidate-v2.json", "promotion-v2.json", "candidate.json", "promotion.json"),
                case.records,
                strict=True,
            ):
                _ = (root / name).write_bytes(raw)
            _ = (root / case.control.name).write_bytes(b"changed bytes")
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(1, auth.main(arguments))
            self.assertIn("CONTROL_MISMATCH", errors.getvalue())
            self.assertFalse(output.exists())
            _ = (root / case.control.name).write_bytes(CONTROL)
            self.assertEqual(0, auth.main(arguments))
            self.assertEqual(json.loads(encoded(case.selected)), json.loads(output.read_bytes()))
            before = output.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, auth.main(arguments))
            self.assertEqual(before, output.read_bytes())

    def test_standalone_embedded_authenticator_is_exact_authored_source(self) -> None:
        root = Path(__file__).resolve().parent.parent
        shell = (root / "install.sh").read_text()
        start = (
            "# BEGIN GENERATED BOOTSTRAP RELEASE AUTH\nbootstrap_release_auth() {\n"
            "  python3 - \"$@\" <<'KAST_BOOTSTRAP_RELEASE_AUTH'\n"
        )
        end = "KAST_BOOTSTRAP_RELEASE_AUTH\n}\n# END GENERATED BOOTSTRAP RELEASE AUTH\n"
        self.assertEqual(1, shell.count(start))
        self.assertEqual(1, shell.count(end))
        embedded = shell.partition(start)[2].partition(end)[0]
        self.assertEqual((root / "packaging/bootstrap_release_auth.py").read_text(), embedded)


if __name__ == "__main__":
    _ = unittest.main()
