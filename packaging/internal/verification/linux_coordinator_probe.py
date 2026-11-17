"""Exercise the owned Linux coordinator with the exact component-qualified native product."""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

# The installed-product probe loads the existing packaging and producer modules.
from product_lifecycle_probe import Probe, product, warm_coordinator_call

TARGET = "linux-x86_64"


@dataclass(frozen=True)
class Evidence:
    sourceRevision: str
    coordinator: int
    generation: str
    host: int
    workspaceLifetime: str
    survivedCallerGroup: bool = True
    coordinatorExited: bool = True
    hostExited: bool = True


def sealed_inputs(args: argparse.Namespace, repo: Path, work: Path) -> product.LocalProductRecord:
    from product_producer import seal_target

    tree = subprocess.check_output(
        ["git", "rev-parse", "HEAD^{tree}"], cwd=repo, text=True, timeout=10
    ).strip()
    target = seal_target(
        repo,
        args.directory,
        args.receipt,
        args.java_release,
        args.revision,
        tree,
        args.version,
        product.LocalProductProducer(),
        TARGET,
        TARGET,
        control=args.control,
    ).target
    record = product.LocalProductRecord(
        4,
        product.REPOSITORY,
        args.revision,
        tree,
        args.version,
        product.LocalProductProducer(),
        (target,),
    )
    inputs = work / "inputs"
    payload = inputs / "payload"
    payload.mkdir(parents=True)
    for asset in record.assets():
        source = args.directory / asset.name
        if asset == target.qualification.receipt:
            source = args.receipt
        os.link(source, payload / asset.name)
    _ = (inputs / "build-record.json").write_text(json.dumps(asdict(record)) + "\n")
    return record


def fetch_interpreter(work: Path) -> tuple[Path, Path]:
    from private_interpreter import DEPENDENCY_URL, PINS

    pin = next(item for item in PINS if item.target == TARGET)
    source = product.HttpReleaseSource()
    for name, url, limit in (
        ("private-python.tar.gz", pin.url, 268_435_456),
        ("defusedxml.whl", DEPENDENCY_URL, 1_048_576),
    ):
        if isinstance(source.download(url, work / name, limit), product.TransferFailure):
            raise ValueError("Pinned interpreter input unavailable")
    return work / "private-python.tar.gz", work / "defusedxml.whl"


def fixture(repo: Path, workspace: Path) -> None:
    experiments = repo / "experiments/host-observation"
    _ = shutil.copytree(experiments / "static-callback-fixture", workspace)
    _ = shutil.copytree(
        experiments / "composed-host-fixture-overlay", workspace, dirs_exist_ok=True
    )
    _ = shutil.copytree(repo / "gradle/wrapper", workspace / "gradle/wrapper")
    _ = shutil.copy2(repo / "gradlew", workspace / "gradlew")


def lifecycle(probe: Probe, workspace: Path, record: product.LocalProductRecord) -> Evidence:
    project = probe.parallel_first_queries(workspace)
    probe.warm_coordinator_reuse(workspace, project)
    peer = warm_coordinator_call((probe.evidence / "coordinator-warm-second.stderr").read_bytes())
    host = probe.observe_host("owned-host", workspace, project, record.targets[0].runtime.sha256)
    try:
        probe.mcp(workspace, project)
    finally:
        _ = probe.run(
            "retire", workspace, ("--internal-install", "managed-retire"), launcher=probe.control
        )
    probe.stopped_endpoints(host)
    for pid in (peer.coordinator, host.pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            continue
        raise ValueError(f"Owned process {pid} remains after retirement")
    return Evidence(
        record.sourceRevision, peer.coordinator, str(peer.generation), host.pid, project
    )


def execute(args: argparse.Namespace) -> None:
    if (platform.system(), platform.machine()) != ("Linux", "x86_64"):
        raise ValueError("Linux x86-64 evidence requires a native Linux x86-64 runner")
    repo = Path(__file__).resolve().parents[3]
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True)
    work = Path(tempfile.mkdtemp(prefix="kast-ci-")).resolve()
    record = sealed_inputs(args, repo, work)
    python, dependency = fetch_interpreter(work)
    home = work / "home"
    home.mkdir(mode=0o700)
    root = home / ".local/share/kast"
    workspace = work / "workspace"
    fixture(repo, workspace)
    gradle = work / "gradle"
    gradle.mkdir(mode=0o700)
    environment = {
        "PATH": os.defpath,
        "HOME": str(home),
        "KAST_INSTALL_ROOT": str(root),
        "TMPDIR": str(work),
        "GRADLE_USER_HOME": str(gradle),
        "KAST_OUTPUT_TOON": "0",
    }
    probe = Probe(root, home, evidence, python, dependency, environment)
    prior = dict(os.environ)
    os.environ.clear()
    os.environ.update(environment)
    try:
        with contextlib.redirect_stdout(sys.stderr):
            probe.install(work / "inputs", record)
        result = lifecycle(probe, workspace, record)
        _ = (evidence / "coordinator-lifecycle.json").write_text(json.dumps(asdict(result)) + "\n")
    finally:
        os.environ.clear()
        os.environ.update(prior)
        identity = hashlib.sha256(os.fsencode(root / "installation")).hexdigest()[:32]
        unit = f"io.github.amichne.kast.semantic.{identity}.service"
        with (evidence / "coordinator-journal.log").open("wb") as output:
            _ = subprocess.run(
                ["/usr/bin/journalctl", "--user", "--unit", unit, "--no-pager", "-n", "200"],
                env={"XDG_RUNTIME_DIR": f"/run/user/{os.getuid()}"},
                stdout=output,
                stderr=subprocess.STDOUT,
                timeout=15,
                check=False,
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("directory", "receipt", "control", "java-release", "evidence"):
        _ = parser.add_argument(f"--{name}", required=True, type=Path)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--revision", required=True)
    execute(parser.parse_args())


if __name__ == "__main__":
    main()
