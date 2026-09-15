"""Owned installed coordinator lifecycle; real Codex frames share the existing acceptance path."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import select
import stat
import subprocess
import time


class AcceptanceFailure(Exception):
    pass


class HostObservationPhase(str, Enum):
    COORDINATOR_ONLY = "pending"
    FRONTEND_PREPARED = "prepared"


class HostCheck(str, Enum):
    VALIDATED = "VALIDATED"
    UNQUALIFIED = "UNQUALIFIED"


@dataclass(frozen=True)
class ServiceObservation:
    phase: HostObservationPhase
    statusSha256: str
    publicSocketAndOwnership: HostCheck
    publicEndpointKind: str


@dataclass(frozen=True)
class HostLifecycleReceipt:
    beforeAttachment: ServiceObservation
    afterDetach: ServiceObservation
    initialize: HostCheck
    threadStart: HostCheck
    parentClosure: HostCheck
    serviceDisable: HostCheck
    stockDesktopUi: HostCheck
    ordinaryDaemonDiscovery: HostCheck


def sha256_file(path):
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def bounded_tail(path: Path, maximum_bytes: int = 4096) -> str:
    with path.open("rb") as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - maximum_bytes))
        return stream.read(maximum_bytes).decode("utf-8", errors="replace")


def receive_response(process: subprocess.Popen[str], request_id: int, timeout: float) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        readable, _, _ = select.select(
            [process.stdout], [], [], max(0.0, deadline - time.monotonic())
        )
        if not readable:
            break
        line = process.stdout.readline()
        if not line:
            break
        try:
            document = json.loads(line)
        except json.JSONDecodeError as failure:
            raise AcceptanceFailure("facade stdout was not JSONL") from failure
        if document.get("id") == request_id:
            return document
    raise AcceptanceFailure(f"response {request_id} was not observed")


def send(process: subprocess.Popen[str], document: dict) -> None:
    process.stdin.write(json.dumps(document, separators=(",", ":")) + "\n")
    process.stdin.flush()


def drain_jsonl_until_exit(process: subprocess.Popen[str], timeout: float) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        readable, _, _ = select.select(
            [process.stdout], [], [], max(0.0, deadline - time.monotonic())
        )
        if not readable:
            break
        line = process.stdout.readline()
        if line:
            try:
                json.loads(line)
            except json.JSONDecodeError as failure:
                raise AcceptanceFailure("facade stdout was not JSONL") from failure
            continue
        if process.poll() is not None:
            return process.returncode
    raise AcceptanceFailure("facade did not close stdout within the teardown bound")


def exercise_private_service(kast: Path, environment: dict[str, str], home: Path, project: Path, product: Path, phase: HostObservationPhase) -> ServiceObservation:
    private = environment.get("KAST_APP_SERVER_PUBLIC_ENDPOINT") == "private"
    kind = "private" if private else "codex-control"
    codex_home = Path(environment.get("CODEX_HOME", str(home / ".codex")))
    socket = product / "state/run/c.sock" if private else codex_home / "app-server-control/app-server-control.sock"
    if socket.is_symlink() or not socket.exists() or not stat.S_ISSOCK(socket.lstat().st_mode):
        raise AcceptanceFailure("selected Kast service socket is unavailable")
    if stat.S_IMODE(socket.lstat().st_mode) != 0o600:
        raise AcceptanceFailure("selected socket permissions are not private")
    status = subprocess.run([str(kast), "app-server", "status"], cwd=project,
                            env=environment, check=True, capture_output=True, text=True, timeout=15)
    evidence = home / f"status-{phase.name.lower()}.json"
    encoded = status.stdout.encode("utf-8")
    if len(encoded) > 256 * 1024:
        raise AcceptanceFailure("passive status exceeded bounded fixture evidence capacity")
    evidence.write_bytes(encoded)
    evidence.chmod(0o600)
    document = json.loads(status.stdout)
    if (document.get("coordinator", {}).get("state") != "ready"
            or document.get("service", {}).get("ownership") != "matched"
            or document.get("host", {}).get("attachment") != phase.value
            or document.get("registry", {}).get("state") != "registered"
            or document.get("protocol") != ("unobserved" if private else "ready")
            or document.get("catalog") != ("unobserved" if private else "ready")):
        raise AcceptanceFailure(f"passive status did not match {phase.name.lower()} ownership and registration; bounded evidence: {evidence}")
    if not private and (document.get("lifecycle") != "alive"
            or document.get("publicEndpoint") != {"kind": kind, "path": str(socket), "ownership": "kast"}
            or document.get("upstream", {}).get("state") != "ready"):
        raise AcceptanceFailure("canonical endpoint lacks lifecycle, ownership, protocol or upstream proof")
    return ServiceObservation(phase, sha256_file(evidence), HostCheck.VALIDATED, kind)


def qualify_installed_lifecycle(isolation, kast, facade, environment, home, project, product) -> HostLifecycleReceipt:
    private = environment.get("KAST_APP_SERVER_PUBLIC_ENDPOINT") == "private"
    discovery = HostCheck.UNQUALIFIED
    try:
        enabled = subprocess.run([str(kast), "app-server", "enable"], cwd=project, env=environment, capture_output=True, text=True, timeout=90)
        if enabled.returncode != 0:
            service_logs = list(product.glob("state/broker/*/service.log"))
            evidence = bounded_tail(service_logs[0]) if len(service_logs) == 1 else "no unique child service log"
            raise AcceptanceFailure("persistent service enable failed: " + enabled.stderr[-2048:] + "; " + evidence)
        before = exercise_private_service(kast, environment, home, project, product,
            HostObservationPhase.COORDINATOR_ONLY if private else HostObservationPhase.FRONTEND_PREPARED)
        if not private:
            codex = environment.get("CODEX_EXECUTABLE")
            if not codex:
                raise AcceptanceFailure("ordinary discovery requires the admitted Codex executable")
            version = subprocess.run([codex, "app-server", "daemon", "version"], cwd=project,
                env=environment, check=True, capture_output=True, text=True, timeout=15)
            authority = json.loads(version.stdout)
            if not authority.get("appServerVersion") or authority.get("cliVersion") != authority.get("appServerVersion"):
                raise AcceptanceFailure("ordinary daemon discovery did not reach the admitted Codex version")
            discovery = HostCheck.VALIDATED
        stderr_path = home / "facade.stderr"
        with stderr_path.open("w+", encoding="utf-8") as stderr_log:
            process = isolation.spawn(
                [str(facade), "-c", "features.code_mode_host=true", "app-server", "--analytics-default-enabled"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=stderr_log,
                text=True,
                bufsize=1,
                env=environment,
                cwd=project,
            )
            try:
                send(
                    process,
                    {
                        "id": 0,
                        "method": "initialize",
                        "params": {
                            "clientInfo": {
                                "name": "kast-installed-host-acceptance",
                                "version": "1",
                            }
                        },
                    },
                )
                initialized = receive_response(process, 0, 45.0)
                if not isinstance(initialized.get("result", {}).get("userAgent"), str):
                    raise AcceptanceFailure("initialize did not return Codex authority")
                send(process, {"method": "initialized"})
                send(
                    process,
                    {
                        "id": 1,
                        "method": "thread/start",
                        "params": {"cwd": str(project)},
                    },
                )
                started = receive_response(process, 1, 45.0)
                result = started.get("result", {})
                thread = result.get("thread", {})
                if (
                    not isinstance(thread.get("id"), str)
                    or result.get("cwd") != str(project)
                ):
                    raise AcceptanceFailure("thread/start did not retain the exact project")
                process.stdin.close()
                if drain_jsonl_until_exit(process, 20.0) != 0:
                    raise AcceptanceFailure(
                        "facade did not complete cleanly after parent stdio closed"
                    )
            except (AcceptanceFailure, OSError, subprocess.SubprocessError) as failure:
                stderr_log.flush()
                tail = bounded_tail(stderr_path).strip()
                detail = f"; bounded stderr tail: {tail}" if tail else ""
                raise AcceptanceFailure(f"{failure}{detail}") from failure
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
        # Parent stdio termination must preserve the coordinator and its prepared host.
        private_service = exercise_private_service(kast, environment, home, project, product, HostObservationPhase.FRONTEND_PREPARED)
    finally:
        disabled = subprocess.run([str(kast), "app-server", "disable"], cwd=project, env=environment, capture_output=True, text=True, timeout=40)
        if disabled.returncode != 0:
            raise AcceptanceFailure("temporary service cleanup failed: " + disabled.stderr[-4096:])
    return HostLifecycleReceipt(before, private_service, HostCheck.VALIDATED, HostCheck.VALIDATED, HostCheck.VALIDATED, HostCheck.VALIDATED, HostCheck.UNQUALIFIED, discovery)
