"""Local process-adapter simulation, not Docker, VM, or compiler qualification.

The fake CLI persists case-owned container state between real child processes.
It extracts real copy streams and executes real Git commands. Container policies
and readiness decisions remain production code; image and runtime facts are
explicitly simulated. Unknown simulator operations fail the fixture independently.
"""

import json
import os
import subprocess
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import pytest

import dev_container
from dev_container import Architecture, Failure, Rejected, Runtime, Workspace

# This program is an external dependency double, never a container implementation.
SIMULATOR = r"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile

root = Path(os.environ["KAST_SIM_ROOT"])
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\n")
state_path = root / "state.json"
state = json.loads(state_path.read_text()) if state_path.exists() else None
control = json.loads((root / "control.json").read_text())
work = root / "work"
ready = root / "ready"

def save():
    state_path.write_text(json.dumps(state))

def fail(stage):
    if control.get("fail") == stage:
        raise SystemExit(71)

def unsupported():
    with (root / "errors.jsonl").open("a") as log:
        log.write(json.dumps(args) + "\n")
    raise SystemExit(90)

def resources(options):
    return {
        "NanoCpus": int(options["--cpus"]) * 1000000000,
        "Memory": int(options["--memory"].removesuffix("g")) * 1024**3,
        "MemorySwap": int(options["--memory-swap"].removesuffix("g")) * 1024**3,
        "PidsLimit": int(options["--pids-limit"]),
    }

if args[:2] == ["container", "ls"]:
    if state:
        print(state["Name"])
elif args[:2] == ["container", "inspect"] and state and args[2:] == [state["Name"]]:
    print(json.dumps([state]))
elif args[:2] == ["image", "ls"]:
    print("sha256:simulated-image")
elif args[:2] == ["image", "inspect"]:
    print(json.dumps([{
        "Id": "sha256:simulated-image", "Architecture": "arm64", "Os": "linux",
        "Config": {"User": "developer", "Labels": {"io.kast.dev-container": "1"}},
    }]))
elif args[0] == "create":
    if state is not None:
        unsupported()
    fail("create")
    options = dict(arg.split("=", 1) for arg in args if arg.startswith("--") and "=" in arg)
    labels = dict(
        arg.removeprefix("--label=").split("=", 1)
        for arg in args if arg.startswith("--label=")
    )
    state = {
        "Name": args[args.index("--name") + 1], "Image": args[-3], "Mounts": [],
        "Config": {"User": options["--user"], "Labels": labels},
        "HostConfig": {
            "Privileged": False, "CapDrop": [options["--cap-drop"]], "CapAdd": [],
            "SecurityOpt": [options["--security-opt"]], "NetworkMode": options["--network"],
            **resources(options),
        },
        "State": {"Running": False},
    }
    # Creation must use a fresh empty Docker config rather than host proxy config.
    config = Path(os.environ["DOCKER_CONFIG"])
    if not config.is_dir() or list(config.iterdir()):
        unsupported()
    work.mkdir()
    save()
elif args[0] in ("start", "stop") and state and args[1:] == [state["Name"]]:
    fail(args[0])
    state["State"]["Running"] = args[0] == "start"
    save()
elif args[0] == "update" and state and args[-1] == state["Name"]:
    fail("update")
    state["HostConfig"].update(resources(dict(arg.split("=", 1) for arg in args[1:-1])))
    save()
elif args[0] == "exec" and state:
    command = args[1:]
    if command[0] in ("-i", "-it"):
        command = command[1:]
    if command[0] != state["Name"] or not state["State"]["Running"]:
        unsupported()
    command = command[1:]
    if command == ["tar", "--no-same-owner", "-xf", "-", "-C", "/work"]:
        fail("copy")
        with tarfile.open(fileobj=sys.stdin.buffer, mode="r|*") as archive:
            archive.extractall(work, filter="data")
    elif command[0] == "git":
        fail("baseline")
        result = subprocess.run(command, cwd=work, check=False)
        raise SystemExit(result.returncode)
    elif command == [
        "python3", "/opt/kast/packaging/composed_bundle.py", "trust",
        "--root", "/opt/kast", "--workspace", "/work",
    ]:
        fail("trust")
        (root / "trusted").write_text("/work\n")
    elif command == ["test", "-f", "/home/developer/.kast-workspace-ready"]:
        raise SystemExit(0 if ready.is_file() else 1)
    elif command == [
        "python3", "-c", "from pathlib import Path; "
        "Path('/home/developer/.kast-workspace-ready').write_text('1\\n')",
    ]:
        fail("ready")
        ready.write_text("1\n")
    elif command[0] == "literal-probe":
        (root / "literal.json").write_text(json.dumps(command[1:]))
        raise SystemExit(23)
    else:
        unsupported()
else:
    unsupported()
"""


@dataclass(frozen=True)
class Simulation:
    root: Path
    runtime: Runtime
    workspace: Workspace

    def open(
        self,
        *,
        image: str = "sha256:simulated-image",
        cpus: int = 4,
        memory: int = 8,
        offline: bool = False,
    ) -> Rejected | None:
        return dev_container.open_workspace(
            self.runtime, self.workspace, image, cpus, memory, offline
        )

    def control(self, stage: str | None) -> None:
        _ = (self.root / "control.json").write_text(json.dumps({"fail": stage}))

    def calls(self) -> list[list[str]]:
        return [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]

    def git(self, *args: str, copied: bool = False) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=self.root / "work" if copied else self.workspace.root,
            env=self.runtime.environment,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()


@pytest.fixture
def simulation(tmp_path: Path) -> Iterator[Simulation]:
    script = tmp_path / "fake_docker.py"
    _ = script.write_text(SIMULATOR)
    repository = tmp_path / "repo with spaces"
    repository.mkdir()
    home = tmp_path / "home"
    home.mkdir()
    environment = {
        "PATH": os.defpath,
        "HOME": str(home),
        "KAST_SIM_ROOT": str(tmp_path),
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
    }
    runtime = Runtime((sys.executable, str(script)), Architecture.ARM64, environment)
    _ = subprocess.run(["git", "init", "--quiet", str(repository)], env=environment, check=True)
    admitted = dev_container.workspace(repository, Architecture.ARM64)
    assert isinstance(admitted, Workspace)
    fixture = Simulation(tmp_path, runtime, admitted)
    fixture.control(None)
    _ = (repository / ".gitignore").write_text("secret.txt\nbuild/\n")
    _ = (repository / "tracked.txt").write_text("original\n")
    _ = (repository / "deleted.txt").write_text("deleted\n")
    _ = fixture.git("add", ".")
    _ = fixture.git(
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        "-m",
        "host history",
    )
    _ = (repository / "tracked.txt").write_text("host working edit\n")
    (repository / "deleted.txt").unlink()
    _ = (repository / "untracked ; literal.txt").write_text("untracked\n")
    _ = (repository / "secret.txt").write_text("must not copy\n")
    yield fixture
    errors = tmp_path / "errors.jsonl"
    assert not errors.exists(), (
        "Unsupported simulator operations: " + errors.read_text() if errors.exists() else ""
    )


def test_create_copy_baseline_stop_reopen_preserves_private_edits(simulation: Simulation) -> None:
    assert simulation.open() is None
    copied = simulation.root / "work"
    assert (copied / "tracked.txt").read_text() == "host working edit\n"
    assert (copied / "untracked ; literal.txt").read_text() == "untracked\n"
    assert not (copied / "secret.txt").exists()
    assert not (copied / "deleted.txt").exists()
    assert simulation.git("rev-list", "--count", "HEAD", copied=True) == "1"
    assert simulation.git("log", "-1", "--format=%s", copied=True) == "Copied workspace baseline"
    assert simulation.git("status", "--porcelain", copied=True) == ""
    assert (simulation.root / "ready").read_text() == "1\n"
    assert (simulation.root / "trusted").read_text() == "/work\n"
    commands = [call for call in simulation.calls() if call[0] == "exec"]
    assert commands[-2][3:5] == ["/opt/kast/packaging/composed_bundle.py", "trust"]
    assert ".kast-workspace-ready" in commands[-1][-1]
    _ = (copied / "tracked.txt").write_text("container-only edit\n")
    _ = (simulation.workspace.root / "tracked.txt").write_text("new host edit\n")
    stopped = dev_container.invoke(
        [*simulation.runtime.docker, "stop", simulation.workspace.name],
        simulation.runtime.environment,
    )
    assert not isinstance(stopped, Rejected)
    assert simulation.open() is None
    assert (copied / "tracked.txt").read_text() == "container-only edit\n"
    assert (simulation.workspace.root / "tracked.txt").read_text() == "new host edit\n"
    assert len([call for call in simulation.calls() if call[0] == "create"]) == 1
    assert len([call for call in simulation.calls() if "tar" in call]) == 1


@pytest.mark.parametrize("policy", ["image", "network"])
def test_reopen_rejects_changed_policy_without_mutating_copy(
    simulation: Simulation, policy: str
) -> None:
    assert simulation.open() is None
    before = simulation.calls()
    result = (
        simulation.open(image="sha256:other")
        if policy == "image"
        else simulation.open(offline=True)
    )
    assert isinstance(result, Rejected) and result.reason is Failure.POLICY_MISMATCH
    assert [call[:2] for call in simulation.calls()[len(before) :]] == [
        ["container", "ls"],
        ["container", "inspect"],
    ]
    assert (simulation.root / "work/tracked.txt").read_text() == "host working edit\n"


def test_reopen_updates_resources_before_start(simulation: Simulation) -> None:
    assert simulation.open() is None
    before = len(simulation.calls())
    assert simulation.open(cpus=2, memory=4) is None
    calls = simulation.calls()[before:]
    assert [call[0] for call in calls] == ["container", "container", "update", "start", "exec"]
    state = json.loads((simulation.root / "state.json").read_text())
    assert state["HostConfig"]["NanoCpus"] == 2_000_000_000
    assert state["HostConfig"]["Memory"] == 4 * 1024**3
    assert state["HostConfig"]["MemorySwap"] == 4 * 1024**3


@pytest.mark.parametrize("stage", ["start", "copy", "baseline", "trust", "ready"])
def test_partial_initialization_never_becomes_ready_or_recopies_on_reopen(
    simulation: Simulation, stage: str
) -> None:
    simulation.control(stage)
    result = simulation.open()
    assert isinstance(result, Rejected) and result.reason is Failure.PROCESS_FAILED
    assert not (simulation.root / "ready").exists()
    protected = simulation.root / "work/protected.txt"
    _ = protected.write_text("preserve after failure\n")
    simulation.control(None)
    before = len(simulation.calls())
    reopened = simulation.open()
    assert isinstance(reopened, Rejected) and reopened.reason is Failure.PROCESS_FAILED
    assert "readiness is unproven" in reopened.detail
    assert protected.read_text() == "preserve after failure\n"
    assert not any("tar" in call for call in simulation.calls()[before:])


def test_main_preserves_literal_arguments_and_child_exit_code(
    simulation: Simulation, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(dev_container, "runtime", lambda: simulation.runtime)
    literal = ["space argument", "$(touch SHOULD_NOT_EXIST)", "; exit 0", "*.kt", "--flag=x"]
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "kast-container",
            "shell",
            "--repository",
            str(simulation.workspace.root),
            "--",
            "literal-probe",
            *literal,
        ],
    )
    assert dev_container.main() == 23
    assert json.loads((simulation.root / "literal.json").read_text()) == literal
    assert not (simulation.root / "work/SHOULD_NOT_EXIST").exists()


@pytest.mark.parametrize("stage", ["update", "start"])
def test_failed_reopen_stops_before_ready_check(simulation: Simulation, stage: str) -> None:
    assert simulation.open() is None
    simulation.control(stage)
    before = len(simulation.calls())
    result = simulation.open(cpus=2)
    assert isinstance(result, Rejected) and result.reason is Failure.PROCESS_FAILED
    calls = simulation.calls()[before:]
    assert calls[-1][0] == stage
    assert not any(call[0] == "exec" for call in calls)
    assert (simulation.root / "work/tracked.txt").read_text() == "host working edit\n"


def test_failed_creation_does_not_start_or_copy(simulation: Simulation) -> None:
    simulation.control("create")
    result = simulation.open()
    assert isinstance(result, Rejected) and result.reason is Failure.PROCESS_FAILED
    assert [call[0] for call in simulation.calls()] == ["container", "create"]
    assert not (simulation.root / "work").exists()
    assert not (simulation.root / "ready").exists()
