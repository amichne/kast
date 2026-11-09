"""Admit only the qualified archive from the selected producer attempt."""

import json
from pathlib import Path

import pytest

from composed_oci import Bundle, Rejection, admit_bundle, admit_producer

REVISION = "a" * 40
SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"


def run_document() -> dict[str, object]:
    return {
        "id": 19,
        "path": ".github/workflows/composed-linux.yml",
        "event": "workflow_dispatch",
        "status": "completed",
        "conclusion": "success",
        "head_repository": {"full_name": "amichne/kast"},
        "head_sha": REVISION,
        "run_attempt": 2,
    }


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("id", 20),
        ("conclusion", "failure"),
        ("path", ".github/workflows/ci.yml"),
        ("event", "pull_request"),
        ("head_repository", {"full_name": "fork/kast"}),
    ],
)
def test_reject_unqualified_producer(field: str, value: object) -> None:
    assert admit_producer(run_document() | {field: value}, 19, "amichne/kast") == (
        Rejection.PRODUCER
    )


@pytest.fixture
def archive(tmp_path: Path) -> Path:
    name = "kast-composed-setup-v0.0.0-linux-x86_64.tar.gz"
    path = tmp_path / name
    _ = path.write_bytes(b"abc")
    _ = (tmp_path / "assembly.json").write_text(
        json.dumps(
            {
                "sourceRevision": REVISION,
                "version": "0.0.0",
                "bundle": {"archive": f"/producer/{name}", "sha256": SHA256, "bytes": 3},
            }
        )
    )
    _ = (tmp_path / "qualification.json").write_text(
        json.dumps(
            {"sourceRevision": REVISION, "qualification": "COMPLETE", "bundleSha256": SHA256}
        )
    )
    return path


def test_admit_exact_attempt_and_bytes(archive: Path) -> None:
    producer = admit_producer(run_document(), 19, "amichne/kast")
    assert not isinstance(producer, Rejection)
    assert producer.artifact == f"kast-composed-linux-{REVISION}-19-2"
    assert admit_bundle(producer, archive.parent) == Bundle(REVISION, "0.0.0", SHA256)


def test_reject_changed_archive(archive: Path) -> None:
    producer = admit_producer(run_document(), 19, "amichne/kast")
    assert not isinstance(producer, Rejection)
    _ = archive.write_bytes(b"xyz")
    assert admit_bundle(producer, archive.parent) == Rejection.DIGEST


def test_reject_qualification_from_another_revision(archive: Path) -> None:
    producer = admit_producer(run_document(), 19, "amichne/kast")
    assert not isinstance(producer, Rejection)
    _ = (archive.parent / "qualification.json").write_text(
        json.dumps(
            {"sourceRevision": "b" * 40, "qualification": "COMPLETE", "bundleSha256": SHA256}
        )
    )
    assert admit_bundle(producer, archive.parent) == Rejection.RECEIPT
