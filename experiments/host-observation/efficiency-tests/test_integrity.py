"""Real private files and independent usage fixtures exercise integrity failures."""

import hashlib
import json
import stat
import subprocess
from pathlib import Path

import pytest

import agent_efficiency_integrity
from agent_efficiency import SourceOverlayDocument, source_overlay_document
from agent_efficiency_integrity import (
    OriginalFile,
    PreservationCleanupFailed,
    PreservationFailure,
    PreservationRejected,
    PreservedDeletion,
    PreservedDelta,
    PreservedFile,
    WorkspaceSnapshot,
    read_journal,
)
from agent_efficiency_subject import GitSubject, subject_digest


def repository(root: Path) -> None:
    _ = subprocess.run(["git", "init", "--quiet", str(root)], check=True)


def test_setup_failure_restores_prior_files_and_removes_created_files(tmp_path: Path) -> None:
    repository(tmp_path)
    source = tmp_path / "Order.kt"
    _ = source.write_text("class Order")
    snapshot = WorkspaceSnapshot.capture(tmp_path)
    try:
        _ = source.write_text("broken setup")
        _ = (tmp_path / "rogue.kt").write_text("class Rogue")
        assert snapshot.examine().changed == ("Order.kt",)
        assert snapshot.examine().created == ("rogue.kt",)
    finally:
        snapshot.restore()
    assert source.read_text() == "class Order"
    assert not (tmp_path / "rogue.kt").exists()


def test_existing_untracked_file_and_symlink_are_preserved(tmp_path: Path) -> None:
    repository(tmp_path)
    source = tmp_path / "owned.kt"
    _ = source.write_text("class Owned")
    link = tmp_path / "alias.kt"
    link.symlink_to("owned.kt")
    snapshot = WorkspaceSnapshot.capture(tmp_path)
    link.unlink()
    _ = link.write_text("replacement")
    _ = source.write_text("changed")
    snapshot.restore()
    assert link.is_symlink()
    assert link.readlink() == Path("owned.kt")
    assert source.read_text() == "class Owned"


def test_preserve_delta_survives_restore(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    root.mkdir()
    repository(root)
    source = root / "Order.kt"
    _ = source.write_bytes(b"original")
    removed = root / "removed.kt"
    _ = removed.write_bytes(b"remove me")
    snapshot = WorkspaceSnapshot.capture(root)
    accepted = b"accepted\x00\xff\n"
    _ = source.write_bytes(accepted)
    source.chmod(0o751)
    removed.unlink()
    _ = (root / "created.kt").write_bytes(b"new")
    destination = tmp_path / "private-overlay"

    outcome = snapshot.preserve_delta(destination, maximum_files=3, maximum_bytes=32)
    snapshot.restore()

    assert source.read_bytes() == b"original"
    assert removed.read_bytes() == b"remove me"
    assert not (root / "created.kt").exists()
    assert isinstance(outcome, PreservedDelta)
    assert len(outcome.entries) == 3
    changed = outcome.entries[0]
    assert isinstance(changed, PreservedFile)
    assert changed.relative_path == "Order.kt"
    assert changed.byte_count == len(accepted)
    assert changed.mode == 0o751
    assert changed.sha256 == hashlib.sha256(accepted).hexdigest()
    assert changed.artifact_path.read_bytes() == accepted
    assert stat.S_IMODE(destination.stat().st_mode) == 0o700
    assert stat.S_IMODE(changed.artifact_path.stat().st_mode) == 0o600
    created = outcome.entries[1]
    assert isinstance(created, PreservedFile)
    assert created.relative_path == "created.kt"
    assert created.artifact_path.read_bytes() == b"new"
    assert created.sha256 == hashlib.sha256(b"new").hexdigest()
    assert created.byte_count == 3
    assert outcome.entries[2] == PreservedDeletion("removed.kt")
    assert "contents" not in PreservedFile.__dataclass_fields__


def test_unchanged_link_does_not_enter_the_overlay(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    root.mkdir()
    repository(root)
    private = tmp_path / "private"
    _ = private.write_bytes(b"PRIVATE TARGET")
    (root / "old-link").symlink_to(private)
    _ = (root / "source.kt").write_bytes(b"old")
    snapshot = WorkspaceSnapshot.capture(root)
    _ = (root / "source.kt").write_bytes(b"new")
    outcome = snapshot.preserve_delta(tmp_path / "overlay", maximum_files=1, maximum_bytes=3)
    assert isinstance(outcome, PreservedDelta)
    assert tuple(entry.relative_path for entry in outcome.entries) == ("source.kt",)
    assert private.read_bytes() == b"PRIVATE TARGET"


def test_cleanup_failure_keeps_the_primary_rejection(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "workspace"
    root.mkdir()
    repository(root)
    _ = (root / "source.kt").write_bytes(b"old")
    snapshot = WorkspaceSnapshot.capture(root)
    _ = (root / "source.kt").write_bytes(b"oversized")

    def unavailable(_path: Path) -> None:
        raise PermissionError("Controlled cleanup denial")

    monkeypatch.setattr(agent_efficiency_integrity.shutil, "rmtree", unavailable)
    destination = tmp_path / "overlay"
    outcome = snapshot.preserve_delta(destination, maximum_files=1, maximum_bytes=1)
    assert outcome == PreservationCleanupFailed(
        PreservationRejected(PreservationFailure.BYTE_LIMIT), destination
    )
    assert destination.is_dir()


def test_overlay_wire_keeps_tags_hashes_and_relative_artifact_paths(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    root.mkdir()
    repository(root)
    _ = (root / "source.kt").write_bytes(b"old")
    _ = (root / "removed.kt").write_bytes(b"removed")
    snapshot = WorkspaceSnapshot.capture(root)
    body = b"PRIVATE_SOURCE_BODY"
    _ = (root / "source.kt").write_bytes(body)
    (root / "source.kt").chmod(0o640)
    (root / "removed.kt").unlink()
    owned = tmp_path / "trial"
    owned.mkdir()
    outcome = snapshot.preserve_delta(
        owned / "source-artifacts", maximum_files=2, maximum_bytes=len(body)
    )
    assert isinstance(outcome, PreservedDelta)
    document = source_overlay_document(outcome, owned, GitSubject())
    encoded = document.model_dump_json()
    value = json.loads(encoded)
    assert value["version"] == 1
    assert value["subject_sha256"] == subject_digest(GitSubject())
    assert value["outcome"] == {
        "kind": "PRESERVED",
        "entries": [
            {"kind": "DELETION", "relative_path": "removed.kt"},
            {
                "kind": "FILE",
                "relative_path": "source.kt",
                "byte_count": len(body),
                "mode": 0o640,
                "sha256": hashlib.sha256(body).hexdigest(),
                "artifact_path": "source-artifacts/00000001.source",
            },
        ],
    }
    assert "PRIVATE_SOURCE_BODY" not in encoded
    assert str(owned) not in encoded
    assert (owned / value["outcome"]["entries"][1]["artifact_path"]).read_bytes() == body
    assert SourceOverlayDocument.model_validate_json(encoded).outcome.kind == "PRESERVED"


def test_overlay_wire_keeps_the_capture_failure_during_cleanup_failure(tmp_path: Path) -> None:
    original = PreservationRejected(PreservationFailure.BYTE_LIMIT)
    outcome = PreservationCleanupFailed(original, tmp_path / "source-artifacts")
    encoded = source_overlay_document(outcome, tmp_path, GitSubject()).model_dump_json()
    assert json.loads(encoded)["outcome"] == {
        "kind": "CLEANUP_FAILED",
        "reason": "BYTE_LIMIT",
        "directory": "source-artifacts",
    }


@pytest.mark.parametrize(
    ("case", "reason"),
    [
        ("symlink", PreservationFailure.SYMLINK),
        ("parent-link", PreservationFailure.SYMLINK),
        ("oversized", PreservationFailure.BYTE_LIMIT),
        ("file-count", PreservationFailure.FILE_LIMIT),
        ("unreadable", PreservationFailure.INPUT_UNREADABLE),
        ("escape", PreservationFailure.UNSAFE_PATH),
        ("existing", PreservationFailure.DESTINATION_EXISTS),
        ("overlap", PreservationFailure.DESTINATION_OVERLAP),
        ("destination-link", PreservationFailure.SYMLINK),
        ("invalid-budget", PreservationFailure.INVALID_BUDGET),
        ("special", PreservationFailure.UNSUPPORTED_FILE),
    ],
)
def test_preserve_delta_rejects_without_source_or_private_target_changes(
    tmp_path: Path, case: str, reason: PreservationFailure
) -> None:
    root = tmp_path / "workspace"
    root.mkdir()
    repository(root)
    source = root / "source.kt"
    _ = source.write_bytes(b"old")
    snapshot = WorkspaceSnapshot.capture(root)
    _ = source.write_bytes(b"changed")
    private = tmp_path / "private"
    private.mkdir()
    target = private / "secret"
    _ = target.write_bytes(b"PRIVATE TARGET")
    destination = tmp_path / "overlay"
    maximum_files = 2
    maximum_bytes = 32
    if case == "symlink":
        source.unlink()
        source.symlink_to(target)
    elif case == "parent-link":
        (root / "linked").symlink_to(private, target_is_directory=True)
        snapshot.files["linked/secret"] = OriginalFile(b"prior", 0o600)
    elif case == "oversized":
        _ = source.write_bytes(b"x" * 33)
    elif case == "file-count":
        _ = (root / "a.kt").write_bytes(b"new")
        maximum_files = 1
    elif case == "unreadable":
        source.chmod(0)
    elif case == "escape":
        snapshot.files["../private/secret"] = OriginalFile(b"prior", 0o600)
    elif case == "existing":
        destination.mkdir()
        _ = (destination / "keep").write_bytes(b"keep")
    elif case == "overlap":
        destination = root / "overlay"
    elif case == "destination-link":
        destination = tmp_path / "alias" / "overlay"
        destination.parent.symlink_to(private, target_is_directory=True)
    elif case == "invalid-budget":
        maximum_files = -1
    elif case == "special":
        source.unlink()
        source.mkdir()

    outcome = snapshot.preserve_delta(
        destination, maximum_files=maximum_files, maximum_bytes=maximum_bytes
    )

    assert outcome == PreservationRejected(reason)
    assert target.read_bytes() == b"PRIVATE TARGET"
    assert sorted(p.name for p in private.iterdir()) == ["secret"]
    if case == "existing":
        assert (destination / "keep").read_bytes() == b"keep"
    else:
        assert not destination.exists()
    if case == "unreadable":
        source.chmod(0o600)
    if source.is_file() and not source.is_symlink():
        assert source.read_bytes() == (b"x" * 33 if case == "oversized" else b"changed")
    if case == "symlink":
        assert source.is_symlink()


def test_missing_terminal_report_keeps_finalized_usage(tmp_path: Path) -> None:
    journal = tmp_path / "journal.jsonl"
    entries = [
        {"type": "provider_request"},
        {"type": "tool_call"},
        {
            "type": "assistant_end",
            "usage": {
                "type": "usage",
                "input": 10,
                "output": 7,
                "cacheRead": 4,
                "cacheWrite": 0,
                "totalTokens": 21,
                "catalogCostUsd": 0.001,
            },
        },
        {"type": "provider_request"},
    ]
    _ = journal.write_text("\n".join(json.dumps(entry) for entry in entries) + "\n")
    totals = read_journal(journal)
    assert totals.qualification == "FINALIZED_PREFIX_ONLY"
    assert (totals.input_tokens, totals.output_tokens, totals.cache_read_tokens) == (14, 7, 4)
    assert (totals.provider_requests, totals.finalized_responses, totals.tool_calls) == (2, 1, 1)
    assert totals.known_catalog_cost_usd == 0.001


def test_malformed_tail_preserves_known_prefix(tmp_path: Path) -> None:
    journal = tmp_path / "journal.jsonl"
    _ = journal.write_text('{"type":"provider_request"}\n{"broken":')
    totals = read_journal(journal)
    assert totals.qualification == "MALFORMED_JOURNAL"
    assert totals.provider_requests == 1
    assert read_journal(tmp_path / "missing").qualification == "NO_JOURNAL"
