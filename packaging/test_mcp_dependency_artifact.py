"""Producer process contract and real materialized inventory, without downloads."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

import mcp_dependency_artifact as producer
import mcp_environment as mcp


class Wheels:
    def __init__(self, target: mcp.DependencyTarget, result: mcp.Exited | mcp.Rejected) -> None:
        self.target = target
        self.result = result
        self.calls = 0

    def __call__(self, command: mcp.Command) -> mcp.Exited | mcp.Rejected:
        self.calls += 1
        assert self.calls == 1, "unexpected process"
        assert command.arguments[:9] == (
            "/producer tools/uv",
            "--no-config",
            "--no-cache",
            "pip",
            "install",
            "--python-version",
            "3.12.12",
            "--python-platform",
            self.target.triple,
        )
        assert command.arguments[9:18] == (
            "--require-hashes",
            "--no-deps",
            "--only-binary",
            ":all:",
            "--link-mode",
            "copy",
            "--index-url",
            "https://pypi.org/simple",
            "--target",
        )
        assert command.arguments[20:] == (
            str(producer.HERE / "mcp-requirements.txt"),
            "--python",
            sys.executable,
        )
        assert command.arguments[19] == "--requirements"
        assert command.environment["PATH"] == ""
        assert command.environment["UV_PYTHON_DOWNLOADS"] == "never"
        site = Path(command.arguments[18])
        site.mkdir()
        _ = (site / ".lock").write_bytes(b"")
        (site / ".lock").chmod(0o666)
        for name, value in (
            ("native.so", b"native fixture"),
            ("dependency.dist-info/licenses/LICENSE", b"license fixture"),
        ):
            path = site / name
            path.parent.mkdir(parents=True, exist_ok=True)
            _ = path.write_bytes(value)
            path.chmod(0o644)
        return self.result

    def complete(self) -> None:
        assert self.calls == 1, "unconsumed process expectation"


@pytest.mark.parametrize("target", tuple(mcp.DependencyTarget))
def test_materialized_artifact_binds_target_bytes_and_licenses(
    tmp_path: Path, target: mcp.DependencyTarget
) -> None:
    metadata = tmp_path.resolve()
    runner = Wheels(target, mcp.Exited(0))
    result = producer.produce(metadata, target, uv=Path("/producer tools/uv"), runner=runner)
    runner.complete()
    assert isinstance(result, mcp.DependencyArtifact)
    artifact = metadata / "mcp-dependencies"
    assert {path.name for path in metadata.iterdir()} == {"mcp-dependencies"}
    assert {path.name for path in artifact.iterdir()} == {"site-packages", "artifact.json"}
    assert (artifact / "site-packages/native.so").read_bytes() == b"native fixture"
    assert (artifact / "site-packages/dependency.dist-info/licenses/LICENSE").read_bytes() == (
        b"license fixture"
    )
    expected = mcp.Inventory(
        (
            mcp.File(
                "dependency.dist-info/licenses/LICENSE",
                "sha256:" + hashlib.sha256(b"license fixture").hexdigest(),
                0o644,
            ),
            mcp.File("native.so", "sha256:" + hashlib.sha256(b"native fixture").hexdigest(), 0o644),
        ),
        ("dependency.dist-info", "dependency.dist-info/licenses"),
    )
    assert result.inventory == expected
    document: object = json.loads((artifact / "artifact.json").read_bytes())
    assert mcp.mapping(document)
    assert set(document) == {"target", "export", "inventory", "pythonVersion", "schemaVersion"}
    assert document["target"] == target.value
    assert document["pythonVersion"] == "3.12.12"
    assert document["schemaVersion"] == 1
    assert producer.produce(metadata, target, uv=Path("/uv"), runner=runner) == mcp.Rejected(
        mcp.Failure.CONCURRENT
    )
    runner.complete()


@pytest.mark.parametrize(
    "result,expected",
    (
        (mcp.Exited(1), mcp.Rejected(mcp.Failure.INSTALL)),
        (mcp.Rejected(mcp.Failure.TIMEOUT), mcp.Rejected(mcp.Failure.TIMEOUT)),
        (mcp.Rejected(mcp.Failure.PROCESS), mcp.Rejected(mcp.Failure.PROCESS)),
    ),
)
def test_producer_failure_preserved_without_publication(
    tmp_path: Path, result: mcp.Exited | mcp.Rejected, expected: mcp.Rejected
) -> None:
    target = mcp.DependencyTarget.LINUX_X86_64
    runner = Wheels(target, result)
    assert (
        producer.produce(tmp_path.resolve(), target, uv=Path("/producer tools/uv"), runner=runner)
        == expected
    )
    runner.complete()
    assert not tuple(tmp_path.iterdir())


def test_empty_materialization_rejected(tmp_path: Path) -> None:
    calls: list[mcp.Command] = []

    def empty(command: mcp.Command) -> mcp.Exited:
        assert not calls
        calls.append(command)
        Path(command.arguments[18]).mkdir()
        return mcp.Exited(0)

    assert producer.produce(
        tmp_path.resolve(), mcp.DependencyTarget.MACOS_ARM64, uv=Path("/uv"), runner=empty
    ) == mcp.Rejected(mcp.Failure.STATE)
    assert len(calls) == 1
    assert not tuple(tmp_path.iterdir())


def test_concurrent_publication_preserves_foreign_bytes(tmp_path: Path) -> None:
    metadata = tmp_path.resolve()
    target = mcp.DependencyTarget.MACOS_ARM64
    runner = Wheels(target, mcp.Exited(0))
    destination = metadata / "mcp-dependencies"

    def arrive(command: mcp.Command) -> mcp.Exited | mcp.Rejected:
        result = runner(command)
        destination.mkdir()
        _ = (destination / "protected").write_text("keep")
        return result

    assert producer.produce(
        metadata, target, uv=Path("/producer tools/uv"), runner=arrive
    ) == mcp.Rejected(mcp.Failure.CONCURRENT)
    runner.complete()
    assert (destination / "protected").read_text() == "keep"
    assert {path.name for path in metadata.iterdir()} == {"mcp-dependencies"}


@pytest.mark.parametrize("change", ("bytes", "symlink"))
def test_unexpected_producer_lock_is_not_admitted(tmp_path: Path, change: str) -> None:
    metadata = tmp_path.resolve() / "metadata"
    metadata.mkdir()
    protected = tmp_path / "protected"
    _ = protected.write_text("keep")
    target = mcp.DependencyTarget.LINUX_X86_64
    runner = Wheels(target, mcp.Exited(0))

    def tamper(command: mcp.Command) -> mcp.Exited | mcp.Rejected:
        result = runner(command)
        lock = Path(command.arguments[18]) / ".lock"
        if change == "bytes":
            _ = lock.write_text("not a transient empty lock")
        else:
            lock.unlink()
            lock.symlink_to(protected)
        return result

    assert producer.produce(
        metadata, target, uv=Path("/producer tools/uv"), runner=tamper
    ) == mcp.Rejected(mcp.Failure.STATE)
    runner.complete()
    assert protected.read_text() == "keep"
    assert not tuple(metadata.iterdir())
