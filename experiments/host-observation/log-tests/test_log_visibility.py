from __future__ import annotations

import hashlib
import json
import os
import stat
import subprocess
import sys
from pathlib import Path
from typing import cast

import pytest

import log_visibility as logs


def accepted[T](value: T | logs.Issue) -> T:
    assert not isinstance(value, logs.Issue), value
    return value


def bytes_for(root: Path, result: logs.CaptureManifest) -> bytes:
    return b"".join((root / segment.file).read_bytes() for segment in result.segments)


def codes(result: logs.CaptureManifest) -> set[logs.Failure]:
    return {issue.code for issue in result.issues}


def test_snapshot_preserves_multiline_exception(tmp_path: Path) -> None:
    source = tmp_path / "idea log $literal.log"
    original = (
        b'INFO kast_semantic_read {"readId":"read-17"}\nException\n\tat owner.method(File.kt:2)\n'
    )
    source.write_bytes(original)
    output = tmp_path / "capture"
    result = accepted(logs.snapshot((source,), output, 1024, "read-17"))
    assert len(result.segments) == 1
    assert result.status == "CAPTURED"
    assert bytes_for(output, result) == original
    segment = result.segments[0]
    assert segment.sha256 == hashlib.sha256(original).hexdigest()
    assert segment.start == 0 and segment.end == len(original)
    assert segment.source == str(source)
    assert segment.read_id_matches == (original.index(b"read-17"),)
    assert not segment.starts_midline and (not segment.ends_midline)
    assert stat.S_IMODE(output.stat().st_mode) == 448
    assert stat.S_IMODE((output / segment.file).stat().st_mode) == 384
    assert stat.S_IMODE((output / "manifest.json").stat().st_mode) == 384
    assert (
        logs.CaptureManifest.model_validate_json((output / "manifest.json").read_bytes()) == result
    )


def test_empty_is_distinct_from_missing(tmp_path: Path) -> None:
    empty = tmp_path / "empty.log"
    empty.touch()
    captured = accepted(logs.snapshot((empty,), tmp_path / "empty", 10))
    missing = accepted(logs.snapshot((tmp_path / "absent.log",), tmp_path / "missing", 10))
    assert captured.status == "CAPTURED" and len(captured.sources) == 1
    assert missing.status == "QUALIFIED" and (not missing.sources)
    assert codes(missing) == {logs.Failure.MISSING}


def test_tail_cap_keeps_byte_offsets_and_midline_qualification(tmp_path: Path) -> None:
    source = tmp_path / "idea.log"
    source.write_bytes(b"before\nException\n  stack-frame")
    result = accepted(logs.snapshot((source,), tmp_path / "capture", 5))
    assert bytes_for(tmp_path / "capture", result) == b"frame"
    assert codes(result) == {logs.Failure.PREFIX_OMITTED}
    assert result.segments[0].start == 25
    assert result.segments[0].starts_midline and result.segments[0].ends_midline


def test_total_budget_qualifies_sources_not_copied(tmp_path: Path) -> None:
    first, second = (tmp_path / "first", tmp_path / "second")
    first.write_bytes(b"first")
    second.write_bytes(b"second")
    result = accepted(logs.snapshot((first, second), tmp_path / "capture", 5))
    assert bytes_for(tmp_path / "capture", result) == b"first"
    assert len(result.sources) == 2
    assert result.issues == (logs.Issue(code=logs.Failure.PREFIX_OMITTED, path=str(second)),)


def test_existing_output_is_not_changed(tmp_path: Path) -> None:
    output = tmp_path / "capture"
    output.mkdir()
    marker = output / "owned-by-user"
    marker.write_bytes(b"keep")
    rejected = logs.snapshot((), output, 100)
    assert isinstance(rejected, logs.Issue)
    assert rejected.code is logs.Failure.OUTPUT_EXISTS
    assert marker.read_bytes() == b"keep"


def test_symlink_and_fifo_are_rejected_without_blocking(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.write_bytes(b"keep")
    link = tmp_path / "link"
    link.symlink_to(source)
    fifo = tmp_path / "pipe"
    os.mkfifo(fifo)
    result = accepted(logs.snapshot((link, fifo), tmp_path / "capture", 100))
    assert codes(result) == {logs.Failure.SYMLINK_REJECTED, logs.Failure.NOT_REGULAR_FILE}


def test_permissions_failure_is_explicit(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = tmp_path / "idea.log"
    source.write_bytes(b"private")
    original = logs.open_regular

    def denied(path: Path):
        if path == source:
            return logs.io_failure(PermissionError(), path)
        return original(path)

    monkeypatch.setattr(logs, "open_regular", denied)
    result = accepted(logs.snapshot((source,), tmp_path / "capture", 100))
    assert codes(result) == {logs.Failure.PERMISSION_DENIED}
    assert not result.segments


def test_incremental_append_preserves_exception_continuations(tmp_path: Path) -> None:
    source = tmp_path / "idea.log"
    source.write_bytes(b"before\n")
    reader = accepted(logs.Reader.start(source, 7))
    capture = accepted(logs.Capture.start(tmp_path / "capture", 100, None))
    try:
        with source.open("ab") as stream:
            stream.write(b"Exception\n  first")
        reader = accepted(logs.poll(reader, capture))
        with source.open("ab") as stream:
            stream.write(b" frame\n  second frame\n")
        reader = accepted(logs.poll(reader, capture))
    finally:
        reader.stream.close()
    result = accepted(capture.finish("watch"))
    assert not result.issues
    assert bytes_for(tmp_path / "capture", result) == b"Exception\n  first frame\n  second frame\n"
    assert result.segments[0].ends_midline and result.segments[1].starts_midline


def test_rotation_drains_old_inode_and_marks_possible_gap(tmp_path: Path) -> None:
    source = tmp_path / "idea.log"
    source.write_bytes(b"before\n")
    reader = accepted(logs.Reader.start(source, 7))
    capture = accepted(logs.Capture.start(tmp_path / "capture", 100, None))
    rotated = source.with_suffix(".log.1")
    try:
        source.rename(rotated)
        with rotated.open("ab") as stream:
            stream.write(b"old tail\n")
        source.write_bytes(b"new log\n")
        reader = accepted(logs.poll(reader, capture))
    finally:
        reader.stream.close()
    result = accepted(capture.finish("watch"))
    assert bytes_for(tmp_path / "capture", result) == b"old tail\nnew log\n"
    assert codes(result) == {logs.Failure.ROTATED}
    assert result.segments[0].inode != result.segments[1].inode


@pytest.mark.parametrize("replacement", [b"short\n", b"a longer rewritten file\n"])
def test_copy_truncation_or_rewrite_is_never_silent(tmp_path: Path, replacement: bytes) -> None:
    source = tmp_path / "idea.log"
    source.write_bytes(b"before\n")
    reader = accepted(logs.Reader.start(source, 7))
    capture = accepted(logs.Capture.start(tmp_path / "capture", 100, None))
    try:
        source.write_bytes(replacement)
        reader = accepted(logs.poll(reader, capture))
    finally:
        reader.stream.close()
    result = accepted(capture.finish("watch"))
    assert bytes_for(tmp_path / "capture", result) == replacement
    assert codes(result) == {logs.Failure.TRUNCATED_OR_REWRITTEN}


def test_read_id_index_cap_keeps_all_raw_bytes(tmp_path: Path) -> None:
    source = tmp_path / "idea.log"
    raw = b"x" * 5000
    source.write_bytes(raw)
    result = accepted(logs.snapshot((source,), tmp_path / "capture", 6000, "x"))
    assert bytes_for(tmp_path / "capture", result) == raw
    assert len(result.segments[0].read_id_matches) == 4096
    assert codes(result) == {logs.Failure.CORRELATION_INDEX_LIMIT}


def test_debug_roundtrip_preserves_unrelated_bytes_and_modes(tmp_path: Path) -> None:
    path = tmp_path / "idea custom.vmoptions"
    before = b"# keep\r\n-Xmx4096m\r\n-Didea.log.debug.categories=org.example\r\n"
    path.write_bytes(before)
    path.chmod(416)
    root = tmp_path / "receipt"
    result = accepted(logs.debug_enable(path, root))
    assert result.status == "CONFIGURED" and result.loaded == "NOT_EXAMINED"
    assert path.read_bytes() == before.replace(
        b"org.example", b"org.example,io.github.amichne.kast"
    )
    assert stat.S_IMODE(path.stat().st_mode) == 416
    assert stat.S_IMODE(root.stat().st_mode) == 448
    assert stat.S_IMODE((root / "original.vmoptions").stat().st_mode) == 384
    assert accepted(logs.debug_restore(root)).status == "RESTORED"
    assert path.read_bytes() == before
    assert accepted(logs.debug_restore(root)).status == "ALREADY_RESTORED"


def test_debug_adds_newline_without_erasing_last_option(tmp_path: Path) -> None:
    path = tmp_path / "idea.vmoptions"
    path.write_bytes(b"-Xmx2g")
    accepted(logs.debug_enable(path, tmp_path / "receipt"))
    assert path.read_bytes() == b"-Xmx2g\n-Didea.log.debug.categories=io.github.amichne.kast\n"


@pytest.mark.parametrize(
    "body",
    [
        b"-Didea.log.debug.categories=first\n-Didea.log.debug.categories=second\n",
        b"-Didea.log.debug.categories=io.github.amichne.kast\n",
        b"-Xmx2g\x00",
        b"\xff",
    ],
)
def test_invalid_or_duplicate_options_fail_before_receipt(tmp_path: Path, body: bytes) -> None:
    path = tmp_path / "idea.vmoptions"
    path.write_bytes(body)
    root = tmp_path / "receipt"
    rejected = logs.debug_enable(path, root)
    assert isinstance(rejected, logs.Issue)
    assert rejected.code is logs.Failure.OPTIONS_REJECTED
    assert path.read_bytes() == body and (not root.exists())


def test_restore_conflict_preserves_new_user_settings(tmp_path: Path) -> None:
    path = tmp_path / "idea.vmoptions"
    path.write_bytes(b"-Xmx2g\n")
    root = tmp_path / "receipt"
    accepted(logs.debug_enable(path, root))
    changed = path.read_bytes() + b"-Dother.setting=user\n"
    path.write_bytes(changed)
    rejected = logs.debug_restore(root)
    assert isinstance(rejected, logs.Issue)
    assert rejected.code is logs.Failure.RESTORE_CONFLICT
    assert path.read_bytes() == changed


def test_changed_backup_rejects_restore(tmp_path: Path) -> None:
    path = tmp_path / "idea.vmoptions"
    path.write_bytes(b"-Xmx2g\n")
    root = tmp_path / "receipt"
    accepted(logs.debug_enable(path, root))
    (root / "original.vmoptions").write_bytes(b"tampered")
    current = path.read_bytes()
    rejected = logs.debug_restore(root)
    assert isinstance(rejected, logs.Issue)
    assert rejected.code is logs.Failure.RECEIPT_REJECTED
    assert path.read_bytes() == current


def test_discovery_uses_source_owned_host_profile(tmp_path: Path) -> None:
    idea, install, codex = (tmp_path / "idea", tmp_path / "install", tmp_path / "codex")
    idea.mkdir()
    (idea / "idea.log").write_bytes(b"idea")
    result = logs.discover(idea, install, codex)
    profile = hashlib.sha256(str(codex).encode()).hexdigest()[:16]
    assert result.expected_control_log == str(install / "state/broker" / profile / "service.log")
    assert len(result.sources) == 1 and result.issues[0].code is logs.Failure.MISSING


def test_real_cli_keeps_literal_spaces_and_metacharacters(tmp_path: Path) -> None:
    source = tmp_path / "idea $(literal) log"
    source.write_bytes(b"multiline\n\tstack\n")
    output = tmp_path / "evidence ; literal"
    command = [
        sys.executable,
        str(Path(logs.__file__).resolve()),
        "snapshot",
        "--log",
        str(source),
        "--out",
        str(output),
    ]
    child = subprocess.run(command, capture_output=True, check=False, timeout=10)
    assert child.returncode == 0, child.stderr
    manifest = logs.CaptureManifest.model_validate_json(child.stdout)
    assert bytes_for(output, manifest) == source.read_bytes()
    second = subprocess.run(command, capture_output=True, check=False, timeout=10)
    assert second.returncode == 2
    assert logs.Issue.model_validate_json(second.stderr).code is logs.Failure.OUTPUT_EXISTS


def test_stream_emits_segment_and_issue_as_typed_json(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = tmp_path / "idea.log"
    path.write_bytes(b"invalid utf8 \xff\n  frame\n")
    reader = accepted(logs.Reader.start(path, 0))
    capture = accepted(logs.Capture.start(tmp_path / "capture", 100, None, stream=True))
    try:
        accepted(capture.take(reader))
        capture.issue(logs.Failure.ROTATED, path)
    finally:
        reader.stream.close()
    lines = capsys.readouterr().out.splitlines()
    event = logs.SegmentEvent.model_validate_json(lines[0])
    assert event.text == "invalid utf8 �\n  frame\n"
    assert logs.IssueEvent.model_validate_json(lines[1]).issue.code is logs.Failure.ROTATED
    assert (tmp_path / "capture" / event.segment.file).read_bytes() == path.read_bytes()


def test_real_watch_cli_emits_terminal_manifest(tmp_path: Path) -> None:
    path = tmp_path / "idea.log"
    path.write_bytes(b"existing\n")
    child = subprocess.run(
        [
            sys.executable,
            str(Path(logs.__file__).resolve()),
            "watch",
            "--log",
            str(path),
            "--out",
            str(tmp_path / "capture"),
            "--seconds",
            "0.01",
            "--stream",
        ],
        capture_output=True,
        timeout=10,
        check=False,
    )
    assert child.returncode == 0, child.stderr
    lines = child.stdout.splitlines()
    started = logs.StartedEvent.model_validate_json(lines[0])
    assert started.sources[0].initial_offset == 9
    result = logs.ManifestEvent.model_validate_json(lines[1])
    assert result.manifest.sources[0].initial_offset == len(b"existing\n")
    assert not result.manifest.segments
    document = cast(dict[str, object], json.loads(lines[1]))
    assert document["type"] == "MANIFEST"


def test_real_watch_stream_captures_append_and_reports_cap(tmp_path: Path) -> None:
    path = tmp_path / "idea.log"
    path.write_bytes(b"old\n")
    output = tmp_path / "capture"
    with subprocess.Popen(
        [
            sys.executable,
            str(Path(logs.__file__).resolve()),
            "watch",
            "--log",
            str(path),
            "--out",
            str(output),
            "--seconds",
            "2",
            "--interval",
            "0.1",
            "--max-bytes",
            "8",
            "--stream",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as child:
        assert child.stdout is not None
        started = logs.StartedEvent.model_validate_json(child.stdout.readline())
        assert started.sources[0].initial_offset == 4
        with path.open("ab") as source:
            source.write(b"Exception\n  frame\n")
        stdout, stderr = child.communicate(timeout=5)
    assert child.returncode == 2, stderr
    lines = stdout.splitlines()
    event = logs.SegmentEvent.model_validate_json(lines[0])
    assert event.text == "Exceptio"
    assert logs.IssueEvent.model_validate_json(lines[1]).issue.code is logs.Failure.BYTE_LIMIT
    final = logs.ManifestEvent.model_validate_json(lines[2]).manifest
    assert final.status == "QUALIFIED"
    assert bytes_for(output, final) == b"Exceptio"


def test_interrupt_keeps_manifest_and_closes_sources(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    path = tmp_path / "idea.log"
    path.write_bytes(b"old\n")

    def interrupt(_delay: float) -> None:
        raise KeyboardInterrupt

    monkeypatch.setattr(logs.time, "sleep", interrupt)
    result = accepted(logs.watch((path,), tmp_path / "capture", 100, 1, 0.1))
    assert codes(result) == {logs.Failure.INTERRUPTED}
    assert (tmp_path / "capture" / "manifest.json").is_file()


def test_restore_conflict_preserves_later_file_mode(tmp_path: Path) -> None:
    path = tmp_path / "idea.vmoptions"
    path.write_bytes(b"-Xmx2g\n")
    path.chmod(0o600)
    root = tmp_path / "receipt"
    accepted(logs.debug_enable(path, root))
    changed = path.read_bytes()
    path.chmod(0o640)
    rejected = logs.debug_restore(root)
    assert isinstance(rejected, logs.Issue)
    assert rejected.code is logs.Failure.RESTORE_CONFLICT
    assert path.read_bytes() == changed
    assert stat.S_IMODE(path.stat().st_mode) == 0o640
