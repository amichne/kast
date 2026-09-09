#!/usr/bin/env python3
"""Exercise the staged Kast App Server façade against an installed Codex CLI."""

from __future__ import annotations

import json
from enum import Enum
import hashlib
import os
import stat
from pathlib import Path
import select
import shutil
import subprocess
import sys
from acceptance_environment import AcceptanceEnvironment, admitted_tools
import time


class AcceptanceFailure(Exception):
    pass


class HostObservationPhase(Enum):
    COORDINATOR_ONLY = "pending"
    FRONTEND_PREPARED = "prepared"


def canonical_json(document: object) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def bounded_tail(path: Path, maximum_bytes: int = 4096) -> str:
    with path.open("rb") as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - maximum_bytes))
        return stream.read(maximum_bytes).decode("utf-8", errors="replace")


def codex_protocol_digest(root: Path) -> str:
    digest = hashlib.sha256()
    paths = sorted(path for path in root.rglob("*.json") if path.is_file())
    if not paths:
        raise AcceptanceFailure("installed Codex generated no protocol schemas")
    for path in paths:
        relative = path.relative_to(root).as_posix().encode()
        digest.update(relative)
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def installed_catalog_evidence(kast: Path, environment: dict[str, str]) -> dict:
    execution = subprocess.run(
        [str(kast), "--schema"],
        check=True,
        capture_output=True,
        text=True,
        timeout=20,
        env=environment,
    )
    try:
        contract = json.loads(execution.stdout)
        bootstrap = contract["serverProjection"]["hostedBootstrap"]
        policy = bootstrap["policy"]
        selected_names = environment.get(
            "KAST_APP_SERVER_TOOLS",
            "query,source_read,semantic_query,impact_analyze,diagnostic_check,"
            "change_plan,change_apply,change_recover",
        ).split(",")
        if not selected_names or len(selected_names) != len(set(selected_names)):
            raise AcceptanceFailure("configured Kast tool selection was invalid")
        selected = set(selected_names)
        tools = [tool for tool in bootstrap["tools"] if tool["name"] in selected]
        if {tool["name"] for tool in tools} != selected:
            raise AcceptanceFailure("configured Kast tool selection was unavailable")
        namespace = {
            "type": "namespace",
            "name": "kast",
            "description": "Compiler-grounded Kotlin source intelligence from Kast.",
            "tools": [
                {
                    "type": "function",
                    "name": tool["name"],
                    "description": tool["description"],
                    "inputSchema": tool["inputSchema"],
                    "deferLoading": tool["deferLoading"],
                }
                for tool in tools
            ],
        }
    except (KeyError, TypeError, json.JSONDecodeError) as failure:
        raise AcceptanceFailure("installed Kast catalog projection was invalid") from failure
    projection = {"developerInstructions": policy, "namespace": namespace}
    return {
        "kastContractSha256": sha256_bytes(canonical_json(contract)),
        "catalogProjectionSha256": sha256_bytes(canonical_json(projection)),
        "catalogToolNames": [tool["name"] for tool in tools],
    }


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


def executable(candidate: str | None, name: str) -> Path:
    selected = candidate or shutil.which(name)
    if selected is None:
        raise AcceptanceFailure(f"installed {name} executable is unavailable")
    # Preserve the installed launcher directory: a Node shim may need its sibling node.
    path = Path(os.path.abspath(selected))
    if not path.is_file() or not os.access(path, os.X_OK):
        raise AcceptanceFailure(f"installed {name} executable is unavailable")
    return path


def exercise_private_service(kast: Path, environment: dict[str, str], home: Path, project: Path, product: Path, phase: HostObservationPhase) -> dict:
    socket = product / "state/run/c.sock"
    ordinary_socket = home / ".codex/app-server-control/app-server-control.sock"
    if ordinary_socket.exists():
        raise AcceptanceFailure("Kast occupied the ordinary Codex daemon socket")
    if not socket.exists() or not stat.S_ISSOCK(socket.stat().st_mode):
        raise AcceptanceFailure("private Kast service socket is unavailable")
    status = subprocess.run([str(kast), "app-server", "status"], cwd=project,
                            env=environment, check=True, capture_output=True, text=True, timeout=15)
    evidence = home / f"status-{phase.name.lower()}.json"
    encoded = status.stdout.encode("utf-8")
    if len(encoded) > 256 * 1024:
        evidence.write_text(json.dumps({"outcome": "REJECTED", "reason": "STATUS_EVIDENCE_TOO_LARGE"}) + "\n")
        raise AcceptanceFailure("passive status exceeded bounded fixture evidence capacity")
    evidence.write_bytes(encoded)
    evidence.chmod(0o600)
    document = json.loads(status.stdout)
    if (document.get("coordinator", {}).get("state") != "ready"
            or document.get("service", {}).get("ownership") != "matched"
            or document.get("host", {}).get("attachment") != phase.value
            or document.get("registry", {}).get("state") != "registered"
            or document.get("protocol") != "unobserved"
            or document.get("catalog") != "unobserved"):
        raise AcceptanceFailure(f"passive status did not match {phase.name.lower()} ownership and registration; bounded evidence: {evidence}")
    return {"socketPath": str(socket), "ordinaryDaemonSocket": "ABSENT", "phase": phase.name, "statusEvidence": str(evidence), "qualification": document}


def main() -> int:
    if len(sys.argv) != 4:
        raise AcceptanceFailure(
            "expected staged-product, project-root, and report-file arguments"
        )
    product = Path(sys.argv[1]).resolve()
    project = Path(sys.argv[2]).resolve()
    report = Path(sys.argv[3]).resolve()
    codex = executable(os.environ.get("KAST_ACCEPTANCE_CODEX_EXECUTABLE"), "codex")
    tools = admitted_tools()
    tools["codex"] = codex
    with AcceptanceEnvironment(tools) as isolation:
        product = isolation.stage_product(product)
        facade = executable(str(product / "bin/kast-codex"), "kast-codex")
        kast = executable(str(product / "bin/kast"), "kast")
        home = Path(isolation.environment["HOME"])
        project = isolation.root / "workspace"
        (project / "settings.gradle.kts").write_text('rootProject.name = "installed-host-fixture"\n')
        environment = dict(isolation.environment)
        environment.update({
            "KAST_REAL_CODEX_EXECUTABLE": str(codex),
            "CODEX_EXECUTABLE": str(codex),
            "KAST_ENABLE_APP_SERVER": "1",
        })
        version = subprocess.run(
            [str(codex), "--version"], check=True, capture_output=True,
            text=True, timeout=10, env=environment, cwd=project,
        ).stdout.strip()
        expected_version = os.environ.get("KAST_CODEX_ACCEPTANCE_VERSION")
        if expected_version is not None and version != f"codex-cli {expected_version}":
            raise AcceptanceFailure("installed Codex version did not match the admitted authority")
        catalog_evidence = installed_catalog_evidence(kast, environment)
        schemas = home / "generated-codex-schema"
        subprocess.run(
            [
                str(codex),
                "app-server",
                "generate-json-schema",
                "--experimental",
                "--out",
                str(schemas),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
            env=environment,
        )
        protocol_digest = codex_protocol_digest(schemas)
        try:
            enabled = subprocess.run([str(kast), "app-server", "enable"], cwd=project, env=environment, capture_output=True, text=True, timeout=90)
            if enabled.returncode != 0:
                service_logs = list(product.glob("state/broker/*/service.log"))
                evidence = bounded_tail(service_logs[0]) if len(service_logs) == 1 else "no unique child service log"
                raise AcceptanceFailure("persistent service enable failed: " + enabled.stderr[-2048:] + "; " + evidence)
            private_service = exercise_private_service(kast, environment, home, project, product, HostObservationPhase.COORDINATOR_ONLY)
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

        document = {
            "schemaVersion": 1,
            "taskId": "HOST-08",
            "outcome": "COMPLETE",
            "facadeRole": "app-server-stdio",
            "desktopStartupArguments": "VALIDATED",
            "codexVersion": version,
            "initialize": "VALIDATED",
            "threadStart": "VALIDATED",
            "parentClosure": "CLEAN",
            "persistentServiceAfterDetach": "VALIDATED",
            "desktopCompatibility": "UNQUALIFIED",
            "desktopDiscovery": "NOT_REQUIRED",
            "stdoutProtocol": "JSONL_ONLY",
            "codexProtocolSha256": protocol_digest,
            "privateService": private_service,
            "codexExecutableSha256": sha256_file(codex),
            "kastExecutableSha256": sha256_file(kast),
            "kastFacadeSha256": sha256_file(facade),
            **catalog_evidence,
        }
        report.parent.mkdir(parents=True, exist_ok=True)
        temporary_report = report.with_suffix(report.suffix + ".tmp")
        temporary_report.write_text(
            json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n"
        )
        temporary_report.replace(report)
        print("installed-codex-host: private service and real Codex stdio handshake passed")
        isolation.mark_passed()
        return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AcceptanceFailure, OSError, subprocess.SubprocessError) as failure:
        print(f"installed-codex-host: {failure}", file=sys.stderr)
        raise SystemExit(1)
