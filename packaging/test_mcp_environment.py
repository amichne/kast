"""Private filesystem rules; the opt-in case runs real retained Python and pip."""

from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

import mcp_environment as mcp

HERE = Path(__file__).parent
SOURCES = HERE.parent / "agent-tools/skills/kast/scripts"


@dataclass(frozen=True)
class Manifest:
    installationRoot: str
    stateRoot: str
    payloadFiles: tuple[mcp.File, ...]
    schemaVersion: int = 3


def manifest(root: Path) -> None:
    files = tuple(
        mcp.File(
            path.relative_to(root).as_posix(), mcp.digest(path), stat.S_IMODE(path.stat().st_mode)
        )
        for path in sorted((root / "share").rglob("*"))
        if path.is_file()
    )
    _ = (root / "installation.json").write_text(
        json.dumps(asdict(Manifest(str(root), str(root / "state"), files)))
    )


def installation(tmp_path: Path) -> Path:
    root = tmp_path.resolve() / "literal space ; $value" / "installation"
    (root / mcp.PYTHON).parent.mkdir(parents=True)
    _ = (root / mcp.PYTHON).write_text("fixture interpreter, never executed\n")
    (root / mcp.PYTHON).chmod(0o755)
    (root / mcp.SCRIPTS).mkdir(parents=True)
    for name in ("server.py", "server.py.lock"):
        _ = shutil.copyfile(SOURCES / name, root / mcp.SCRIPTS / name)
    for name in ("mcp_environment.py", "mcp-requirements.txt", "mcp-requirements.txt.lock"):
        _ = shutil.copyfile(HERE / name, root / mcp.METADATA / name)
    manifest(root)
    return root


class Installer:
    """Only the process observation is scripted; product decisions remain real."""

    def __init__(self, root: Path, results: tuple[mcp.Exited | mcp.Rejected, ...]) -> None:
        self.root = root
        self.results = list(results)
        self.calls: list[mcp.Command] = []

    def __call__(self, command: mcp.Command) -> mcp.Exited | mcp.Rejected:
        assert self.results, "unexpected process"
        self.calls.append(command)
        assert command.arguments[0] == str(self.root / mcp.PYTHON)
        assert command.environment["PATH"] == ""
        if len(self.calls) == 1:
            assert command.arguments[1:8] == (
                "-I",
                "-B",
                "-m",
                "pip",
                "--isolated",
                "--disable-pip-version-check",
                "install",
            )
            for flag in (
                "--require-hashes",
                "--no-deps",
                "--only-binary=:all:",
                "--no-compile",
                "--no-cache-dir",
                "--ignore-installed",
                "--no-input",
            ):
                assert flag in command.arguments
            assert command.arguments[-2:] == ("--requirement", str(self.root / mcp.REQUIREMENTS))
            site = self.root / "state/.mcp-preparing/site-packages"
            site.mkdir()
            _ = (site / "dependency.py").write_text("VALUE = 42\n")
        else:
            assert len(self.calls) == 2
            assert command.arguments[1:5] == ("-I", "-S", "-B", "-c")
        return self.results.pop(0)

    def complete(self) -> None:
        assert not self.results, "unconsumed process expectations"


def no_process(_command: mcp.Command) -> mcp.Exited:
    pytest.fail("rejection or reuse must not invoke a process")


def prepared(root: Path) -> None:
    runner = Installer(root, (mcp.Exited(0), mcp.Exited(0)))
    assert mcp.prepare(root, runner) == mcp.Prepared(str(root), str(root / "state/mcp"), "CREATED")
    runner.complete()


def test_generated_requirements_binding() -> None:
    expected = mcp.RequirementsLock(
        mcp.digest(SOURCES / "server.py"),
        mcp.digest(SOURCES / "server.py.lock"),
        mcp.digest(HERE / "mcp-requirements.txt"),
    )
    assert json.loads((HERE / "mcp-requirements.txt.lock").read_bytes()) == asdict(expected)
    requirements = (HERE / "mcp-requirements.txt").read_text()
    assert "--hash=sha256:" in requirements
    assert "mcp==1.30.0" in requirements


def test_prepare_reuse_and_offline_serve_plan(tmp_path: Path) -> None:
    root = installation(tmp_path)
    assert mcp.serve_command(root, {}) == mcp.Rejected(mcp.Failure.MISSING)
    assert not (root / "state").exists()
    prepared(root)
    before = (root / "state/mcp/receipt.json").read_bytes()
    assert mcp.prepare(root, no_process) == mcp.Prepared(
        str(root), str(root / "state/mcp"), "REUSED"
    )
    assert (root / "state/mcp/receipt.json").read_bytes() == before
    command = mcp.serve_command(root, {"PATH": "", "KAST_TEST": "preserved"})
    assert isinstance(command, mcp.Command)
    assert command.arguments[0] == str(root / mcp.PYTHON)
    assert command.arguments[1:5] == ("-I", "-S", "-B", "-c")
    assert command.arguments[-2:] == (str(root / mcp.SERVER), str(root / "state/mcp/site-packages"))
    assert "pip" not in command.arguments and "uv" not in command.arguments
    assert command.environment["KAST_TEST"] == "preserved"
    receipt: object = json.loads(before)
    assert mcp.mapping(receipt)
    assert receipt["installationRoot"] == str(root)
    assert receipt["schemaVersion"] == 1


@pytest.mark.parametrize("failure", (mcp.Failure.TIMEOUT, mcp.Failure.PROCESS))
def test_preserves_process_failure_and_partial_state(tmp_path: Path, failure: mcp.Failure) -> None:
    root = installation(tmp_path)
    rejected = mcp.Rejected(failure)
    runner = Installer(root, (rejected,))
    assert mcp.prepare(root, runner) is rejected
    runner.complete()
    assert not (root / "state/mcp").exists()
    assert (root / "state/.mcp-preparing/site-packages/dependency.py").is_file()
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.PARTIAL)
    assert mcp.serve_command(root, {}) == mcp.Rejected(mcp.Failure.PARTIAL)


@pytest.mark.parametrize(
    "install_exit,import_exit,failure",
    (
        (1, 0, mcp.Failure.INSTALL),
        (0, 1, mcp.Failure.IMPORT),
    ),
)
def test_rejects_unsuccessful_process(
    tmp_path: Path, install_exit: int, import_exit: int, failure: mcp.Failure
) -> None:
    root = installation(tmp_path)
    results = (
        (mcp.Exited(install_exit),) if install_exit else (mcp.Exited(0), mcp.Exited(import_exit))
    )
    runner = Installer(root, results)
    assert mcp.prepare(root, runner) == mcp.Rejected(failure)
    runner.complete()
    assert not (root / "state/mcp").exists()


@pytest.mark.parametrize(
    "relative",
    (
        mcp.PYTHON,
        mcp.HELPER,
        mcp.REQUIREMENTS,
        mcp.EXPORT_LOCK,
        mcp.SERVER,
        mcp.SERVER_LOCK,
    ),
)
def test_tampered_payload_rejects_before_state_effects(tmp_path: Path, relative: Path) -> None:
    root = installation(tmp_path)
    with (root / relative).open("ab") as stream:
        _ = stream.write(b"tampered")
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.INSTALLATION)
    assert not (root / "state").exists()


def test_lock_changes_cannot_reuse_stale_export(tmp_path: Path) -> None:
    root = installation(tmp_path)
    _ = (root / mcp.SERVER_LOCK).write_text("new admitted server lock")
    manifest(root)
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.INSTALLATION)
    assert not (root / "state").exists()


@pytest.mark.parametrize("change", ("missing", "changed", "extra", "directory", "link", "hardlink"))
def test_dependency_inventory_is_exact(tmp_path: Path, change: str) -> None:
    root = installation(tmp_path)
    prepared(root)
    site = root / "state/mcp/site-packages"
    dependency = site / "dependency.py"
    if change == "missing":
        dependency.unlink()
    elif change == "changed":
        _ = dependency.write_text("changed")
    elif change == "extra":
        _ = (site / "foreign.py").write_text("foreign")
    elif change == "directory":
        (site / "foreign").mkdir()
    elif change == "link":
        dependency.unlink()
        dependency.symlink_to(root / mcp.SERVER)
    else:
        os.link(dependency, site / "alias.py")
    before = (root / "state/mcp/receipt.json").read_bytes()
    assert mcp.serve_command(root, {}) == mcp.Rejected(mcp.Failure.STATE)
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.STATE)
    assert (root / "state/mcp/receipt.json").read_bytes() == before


@pytest.mark.parametrize("name", ("state", "state/mcp", "state/.mcp-preparing"))
def test_linked_state_is_not_followed(tmp_path: Path, name: str) -> None:
    root = installation(tmp_path)
    foreign = tmp_path.resolve() / "foreign"
    foreign.mkdir()
    marker = foreign / "protected"
    _ = marker.write_text("keep")
    path = root / name
    path.parent.mkdir(exist_ok=True)
    path.symlink_to(foreign, target_is_directory=True)
    assert isinstance(mcp.prepare(root, no_process), mcp.Rejected)
    assert isinstance(mcp.serve_command(root, {}), mcp.Rejected)
    assert marker.read_text() == "keep"
    assert {p.name for p in foreign.iterdir()} == {"protected"}


def test_foreign_empty_destination_is_never_replaced(tmp_path: Path) -> None:
    root = installation(tmp_path)
    (root / "state/mcp").mkdir(parents=True)
    identity = (root / "state/mcp").stat().st_ino
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.STATE)
    assert (root / "state/mcp").stat().st_ino == identity
    stage = root / "state/stage"
    stage.mkdir()
    assert mcp.publish(stage, root / "state/mcp") == mcp.Rejected(mcp.Failure.CONCURRENT)
    assert stage.is_dir()
    assert (root / "state/mcp").stat().st_ino == identity


def test_replaced_stage_preserves_foreign_directory(tmp_path: Path) -> None:
    root = installation(tmp_path)
    runner = Installer(root, (mcp.Exited(0),))

    def replace_stage(command: mcp.Command) -> mcp.Exited | mcp.Rejected:
        result = runner(command)
        stage = root / "state/.mcp-preparing"
        _ = stage.rename(root / "state/displaced")
        _ = shutil.copytree(root / "state/displaced", stage)
        return result

    assert mcp.prepare(root, replace_stage) == mcp.Rejected(mcp.Failure.CONCURRENT)
    runner.complete()
    assert not (root / "state/.mcp-preparing/receipt.json").exists()
    assert not (root / "state/mcp").exists()


@pytest.mark.parametrize("change", ("missing", "malformed", "wrong-version", "linked"))
def test_receipt_rejects_without_repair(tmp_path: Path, change: str) -> None:
    root = installation(tmp_path)
    prepared(root)
    receipt = root / "state/mcp/receipt.json"
    original = receipt.read_bytes()
    if change == "missing":
        receipt.unlink()
    elif change == "malformed":
        _ = receipt.write_bytes(b"{incomplete")
    elif change == "wrong-version":
        _ = receipt.write_bytes(original.replace(b'"schemaVersion":1', b'"schemaVersion":true'))
    else:
        receipt.unlink()
        receipt.symlink_to(root / mcp.EXPORT_LOCK)
    assert mcp.prepare(root, no_process) == mcp.Rejected(mcp.Failure.STATE)
    assert mcp.serve_command(root, {}) == mcp.Rejected(mcp.Failure.STATE)


def test_failed_outcome_json_is_finite_and_explicit() -> None:
    assert json.loads(mcp.encoded(mcp.Rejected(mcp.Failure.INSTALL))) == {
        "status": "MCP_REJECTED",
        "failure": "MCP_WHEEL_INSTALL_FAILED",
    }


def test_real_private_python_empty_path(tmp_path: Path) -> None:
    """Explicit opt-in downloads locked wheels into a disposable installation."""
    private = os.environ.get("KAST_TEST_PRIVATE_PYTHON_ROOT")
    if private is None:
        pytest.skip("set KAST_TEST_PRIVATE_PYTHON_ROOT to the pinned extracted python directory")
    root = installation(tmp_path)
    shutil.rmtree(root / mcp.METADATA / "private-python")
    _ = shutil.copytree(Path(private), root / mcp.METADATA / "private-python", symlinks=False)
    shutil.rmtree(root / mcp.SCRIPTS)
    _ = shutil.copytree(SOURCES, root / mcp.SCRIPTS, ignore=shutil.ignore_patterns("__pycache__"))
    manifest(root)
    env = {"PATH": "", "HOME": str(tmp_path), "PIP_INDEX_URL": "http://127.0.0.1:1"}
    command = [str(root / mcp.PYTHON), "-I", "-S", "-B", str(root / mcp.HELPER)]
    version = subprocess.run(
        [str(root / mcp.PYTHON), "-I", "-B", "-m", "pip", "--version"],
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    assert version.stdout.startswith("pip 26.0.1 ")
    result = subprocess.run(
        [*command, "prepare", "--install-root", str(root)],
        env=env,
        check=False,
        capture_output=True,
        text=True,
        timeout=360,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)["disposition"] == "CREATED"
    result = subprocess.run(
        [*command, "prepare", "--install-root", str(root)],
        env=env,
        check=False,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)["disposition"] == "REUSED"
    # Real canonical MCP startup, no tool invocation and no native Host claim.
    result = subprocess.run(
        [*command, "serve", "--install-root", str(root)],
        env=env,
        input="",
        check=False,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == ""
    assert not tuple((root / "state/mcp").rglob("*.pyc"))
    assert not tuple((root / mcp.SCRIPTS).rglob("*.pyc"))
