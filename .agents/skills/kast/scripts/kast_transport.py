#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
READINESS_STATE = "READY"
CONTROL_PLANE_OPERATIONS = {
    "workspace-status",
    "workspace-ensure",
    "daemon-start",
    "daemon-stop",
}
HTTP_OPERATIONS = {
    "health": ("GET", "/api/v1/health"),
    "runtime-status": ("GET", "/api/v1/runtime/status"),
    "capabilities": ("GET", "/api/v1/capabilities"),
    "symbol-resolve": ("POST", "/api/v1/symbol/resolve"),
    "references": ("POST", "/api/v1/references"),
    "diagnostics": ("POST", "/api/v1/diagnostics"),
    "rename": ("POST", "/api/v1/rename"),
    "edits-apply": ("POST", "/api/v1/edits/apply"),
}
CLI_OPERATION_WORDS = {
    "workspace-status": ["workspace", "status"],
    "workspace-ensure": ["workspace", "ensure"],
    "daemon-start": ["daemon", "start"],
    "daemon-stop": ["daemon", "stop"],
    "capabilities": ["capabilities"],
    "symbol-resolve": ["symbol", "resolve"],
    "references": ["references"],
    "diagnostics": ["diagnostics"],
    "rename": ["rename"],
    "edits-apply": ["edits", "apply"],
}


class TransportError(Exception):
    def __init__(self, code: str, message: str, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "status": "error",
            "code": self.code,
            "message": self.message,
        }
        if self.details:
            payload["details"] = self.details
        return payload


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "resolve-tooling":
            payload = resolve_tooling(
                repo_root=resolve_repo_root(args.repo_root),
                workspace_root=normalize_workspace_root(args.workspace_root),
                operation=args.operation,
            )
            write_json(payload)
            return 0

        if args.command == "invoke":
            payload = invoke_transport(
                transport=args.transport,
                repo_root=resolve_repo_root(args.repo_root),
                workspace_root=normalize_workspace_root(args.workspace_root),
                operation=args.operation,
                request_file=args.request_file,
                wait_timeout_ms=args.wait_timeout_ms,
            )
            write_json(payload)
            return 0
    except TransportError as error:
        write_json(error.to_payload(), stream=sys.stderr)
        return 1

    raise AssertionError(f"Unhandled command: {args.command}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    resolve_parser = subparsers.add_parser("resolve-tooling")
    resolve_parser.add_argument("--repo-root", default=None)
    resolve_parser.add_argument("--workspace-root", default=".")
    resolve_parser.add_argument("--operation", default="workspace-status")

    invoke_parser = subparsers.add_parser("invoke")
    invoke_parser.add_argument("--repo-root", default=None)
    invoke_parser.add_argument("--transport", required=True)
    invoke_parser.add_argument("--workspace-root", default=".")
    invoke_parser.add_argument("--operation", required=True)
    invoke_parser.add_argument("--request-file", default=None)
    invoke_parser.add_argument("--wait-timeout-ms", type=int, default=60_000)
    return parser


def write_json(payload: dict[str, Any], stream: Any = sys.stdout) -> None:
    json.dump(payload, stream, indent=2, sort_keys=True)
    stream.write("\n")


def resolve_repo_root(repo_root: str | None) -> Path:
    if repo_root:
        candidate = Path(repo_root).expanduser().resolve()
        if not candidate.joinpath("settings.gradle.kts").is_file():
            raise TransportError(
                code="REPO_ROOT_INVALID",
                message="The provided repo root does not look like the Kast repository",
                details={"repoRoot": str(candidate)},
            )
        return candidate

    current = Path(__file__).resolve()
    for candidate in [current.parent, *current.parents]:
        if candidate.joinpath("settings.gradle.kts").is_file():
            return candidate
    raise TransportError(
        code="REPO_ROOT_NOT_FOUND",
        message="Could not resolve the Kast repository root from the script location",
    )


def normalize_workspace_root(workspace_root: str) -> Path:
    return Path(workspace_root).expanduser().resolve()


def resolve_tooling(repo_root: Path, workspace_root: Path, operation: str) -> dict[str, Any]:
    state = inspect_state(repo_root, workspace_root)
    auto_transport = choose_auto_transport(state, operation)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "status": "ok",
        "repoRoot": str(repo_root),
        "workspaceRoot": str(workspace_root),
        "descriptorDirectory": str(state["descriptorDirectory"]),
        "tooling": state["tooling"],
        "runtimes": state["runtimes"],
        "auto": {
            "operation": operation,
            "selectedTransport": auto_transport,
        },
    }


def inspect_state(repo_root: Path, workspace_root: Path) -> dict[str, Any]:
    descriptor_directory = Path(
        os.environ.get("KAST_INSTANCE_DIR", workspace_root / ".kast" / "instances"),
    ).expanduser().resolve()
    cli_tool = resolve_repo_tool(
        {
            "jar": repo_root / "analysis-cli" / "build" / "libs" / "analysis-cli-all.jar",
            "wrapper": repo_root / "analysis-cli" / "build" / "scripts" / "analysis-cli",
        },
    )
    standalone_tool = resolve_repo_tool(
        {
            "jar": repo_root / "backend-standalone" / "build" / "libs" / "backend-standalone-all.jar",
            "wrapper": repo_root / "backend-standalone" / "build" / "scripts" / "backend-standalone",
        },
    )

    runtimes = inspect_runtimes(descriptor_directory, workspace_root)
    tooling = {
        "cli": cli_tool,
        "standaloneLauncher": standalone_tool,
    }
    return {
        "descriptorDirectory": descriptor_directory,
        "tooling": tooling,
        "runtimes": runtimes,
    }


def resolve_repo_tool(candidates: dict[str, Path]) -> dict[str, Any]:
    candidate_entries: list[dict[str, Any]] = []
    selected_command: list[str] | None = None
    selected_kind: str | None = None
    for kind, path in candidates.items():
        exists = path.is_file()
        candidate_entries.append(
            {
                "kind": kind,
                "path": str(path),
                "exists": exists,
            },
        )
        if selected_command is None and exists:
            selected_kind = kind
            selected_command = ["java", "-jar", str(path)] if kind == "jar" else [str(path)]
    return {
        "available": selected_command is not None,
        "selectedKind": selected_kind,
        "selectedCommand": selected_command,
        "candidates": candidate_entries,
    }


def inspect_runtimes(descriptor_directory: Path, workspace_root: Path) -> dict[str, Any]:
    candidates: list[dict[str, Any]] = []
    if descriptor_directory.is_dir():
        for path in sorted(descriptor_directory.glob("*.json")):
            descriptor = read_json_file(path)
            if normalize_descriptor_workspace(descriptor) != str(workspace_root):
                continue
            candidates.append(inspect_candidate(path, descriptor))

    return {
        "all": candidates,
        "intellij": classify_backend(candidates, "intellij"),
        "standalone": classify_backend(candidates, "standalone"),
    }


def read_json_file(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text())
    except FileNotFoundError as exc:
        raise TransportError(
            code="FILE_NOT_FOUND",
            message="JSON file does not exist",
            details={"path": str(path)},
        ) from exc
    except json.JSONDecodeError as exc:
        raise TransportError(
            code="INVALID_JSON",
            message="JSON file could not be decoded",
            details={"path": str(path), "error": str(exc)},
        ) from exc


def normalize_descriptor_workspace(descriptor: dict[str, Any]) -> str:
    return str(Path(descriptor["workspaceRoot"]).expanduser().resolve())


def inspect_candidate(path: Path, descriptor: dict[str, Any]) -> dict[str, Any]:
    pid = int(descriptor["pid"])
    pid_alive = is_process_alive(pid)
    runtime_status: dict[str, Any] | None = None
    capabilities: dict[str, Any] | None = None
    error_message: str | None = None
    reachable = False
    ready = False

    if pid_alive:
        try:
            runtime_status = http_request(descriptor, "GET", "/api/v1/runtime/status")
            reachable = True
            ready = is_ready(runtime_status)
            try:
                capabilities = http_request(descriptor, "GET", "/api/v1/capabilities")
            except TransportError:
                capabilities = None
        except TransportError as error:
            error_message = error.message
    else:
        error_message = f"Process {pid} is not alive"

    return {
        "descriptorPath": str(path),
        "descriptor": descriptor,
        "pidAlive": pid_alive,
        "reachable": reachable,
        "ready": ready,
        "runtimeStatus": runtime_status,
        "capabilities": capabilities,
        "errorMessage": error_message,
    }


def classify_backend(candidates: list[dict[str, Any]], backend_name: str) -> dict[str, Any]:
    backend_candidates = [candidate for candidate in candidates if candidate["descriptor"]["backendName"] == backend_name]
    ready_candidates = [candidate for candidate in backend_candidates if candidate["ready"]]
    selected = ready_candidates[0] if len(ready_candidates) == 1 else None
    return {
        "candidates": backend_candidates,
        "readyCandidates": ready_candidates,
        "selected": selected,
        "readyCount": len(ready_candidates),
    }


def choose_auto_transport(state: dict[str, Any], operation: str) -> str:
    cli_available = state["tooling"]["cli"]["available"]
    if operation in CONTROL_PLANE_OPERATIONS:
        if cli_available:
            return "cli"
        raise TransportError(
            code="CLI_REQUIRED",
            message="The requested operation requires the repo-local analysis-cli transport",
            details={"operation": operation},
        )

    if cli_available:
        return "cli"

    intellij_ready = state["runtimes"]["intellij"]["readyCount"]
    standalone_ready = state["runtimes"]["standalone"]["readyCount"]
    if intellij_ready == 1:
        return "http-intellij"
    if intellij_ready > 1:
        raise TransportError(
            code="AMBIGUOUS_RUNTIME",
            message="More than one ready IntelliJ runtime matches the workspace",
            details={"backendName": "intellij", "count": intellij_ready},
        )
    if standalone_ready == 1:
        return "http-standalone"
    if standalone_ready > 1:
        raise TransportError(
            code="AMBIGUOUS_RUNTIME",
            message="More than one ready standalone runtime matches the workspace",
            details={"backendName": "standalone", "count": standalone_ready},
        )
    raise TransportError(
        code="NO_TRANSPORT_AVAILABLE",
        message="No deterministic Kast transport is currently available for this workspace",
        details={"operation": operation},
    )


def invoke_transport(
    transport: str,
    repo_root: Path,
    workspace_root: Path,
    operation: str,
    request_file: str | None,
    wait_timeout_ms: int,
) -> dict[str, Any]:
    state = inspect_state(repo_root, workspace_root)
    effective_transport = choose_auto_transport(state, operation) if transport == "auto" else transport

    if effective_transport == "cli":
        result = invoke_cli(repo_root, workspace_root, operation, request_file, wait_timeout_ms)
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "ok",
            "transport": effective_transport,
            "operation": operation,
            "result": result,
        }

    if effective_transport in {"http-intellij", "http-standalone"}:
        backend_name = "intellij" if effective_transport == "http-intellij" else "standalone"
        result = invoke_http(state, backend_name, workspace_root, operation, request_file)
        return {
            "schemaVersion": SCHEMA_VERSION,
            "status": "ok",
            "transport": effective_transport,
            "operation": operation,
            "result": result,
        }

    raise TransportError(
        code="TRANSPORT_UNKNOWN",
        message="Unknown transport",
        details={"transport": effective_transport},
    )


def invoke_cli(
    repo_root: Path,
    workspace_root: Path,
    operation: str,
    request_file: str | None,
    wait_timeout_ms: int,
) -> dict[str, Any]:
    cli_tool = inspect_state(repo_root, workspace_root)["tooling"]["cli"]
    if not cli_tool["available"]:
        raise TransportError(
            code="CLI_NOT_AVAILABLE",
            message="No runnable repo-local analysis-cli entrypoint is available",
            details={"tooling": cli_tool},
        )
    if operation not in CLI_OPERATION_WORDS:
        raise TransportError(
            code="OPERATION_UNSUPPORTED",
            message="The requested operation is not supported by analysis-cli",
            details={"operation": operation},
        )

    command = [
        *cli_tool["selectedCommand"],
        *CLI_OPERATION_WORDS[operation],
        f"--workspace-root={workspace_root}",
        f"--wait-timeout-ms={wait_timeout_ms}",
    ]
    if request_file is not None:
        command.append(f"--request-file={normalize_request_file(request_file)}")
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        error_payload = parse_json_text(result.stderr or result.stdout)
        raise TransportError(
            code=error_payload.get("code", "CLI_FAILED"),
            message=error_payload.get("message", "analysis-cli failed"),
            details={"stderr": error_payload, "command": command},
        )
    return parse_json_text(result.stdout)


def invoke_http(
    state: dict[str, Any],
    backend_name: str,
    workspace_root: Path,
    operation: str,
    request_file: str | None,
) -> dict[str, Any]:
    if operation == "workspace-status":
        return build_http_workspace_status(state, backend_name, workspace_root)
    if operation == "workspace-ensure":
        candidate = require_single_ready_candidate(state, backend_name)
        return build_http_runtime_envelope(candidate, workspace_root, ensured=True)
    if operation in {"daemon-start", "daemon-stop"}:
        raise TransportError(
            code="CLI_REQUIRED",
            message="Direct HTTP transports cannot manage standalone daemon lifecycle",
            details={"operation": operation, "backendName": backend_name},
        )
    if operation not in HTTP_OPERATIONS:
        raise TransportError(
            code="OPERATION_UNSUPPORTED",
            message="The requested operation is not supported by the direct HTTP transport",
            details={"operation": operation, "backendName": backend_name},
        )

    candidate = require_single_ready_candidate(state, backend_name)
    method, route = HTTP_OPERATIONS[operation]
    body: dict[str, Any] | None = None
    if method == "POST":
        if request_file is None:
            raise TransportError(
                code="REQUEST_FILE_REQUIRED",
                message="This operation requires --request-file",
                details={"operation": operation},
            )
        body = read_json_file(normalize_request_file(request_file))
    return http_request(candidate["descriptor"], method, route, body=body)


def build_http_workspace_status(state: dict[str, Any], backend_name: str, workspace_root: Path) -> dict[str, Any]:
    backend = state["runtimes"][backend_name]
    if backend["readyCount"] > 1:
        raise TransportError(
            code="AMBIGUOUS_RUNTIME",
            message="More than one ready runtime matches the requested backend",
            details={"backendName": backend_name, "count": backend["readyCount"]},
        )
    return {
        "workspaceRoot": str(workspace_root),
        "backendName": backend_name,
        "selected": backend["selected"],
        "candidates": backend["candidates"],
        "schemaVersion": SCHEMA_VERSION,
    }


def build_http_runtime_envelope(candidate: dict[str, Any], workspace_root: Path, ensured: bool) -> dict[str, Any]:
    return {
        "workspaceRoot": str(workspace_root),
        "started": False,
        "selected": candidate,
        "ensured": ensured,
        "schemaVersion": SCHEMA_VERSION,
    }


def require_single_ready_candidate(state: dict[str, Any], backend_name: str) -> dict[str, Any]:
    backend = state["runtimes"][backend_name]
    if backend["readyCount"] == 0:
        raise TransportError(
            code="RUNTIME_NOT_READY",
            message="No ready runtime matches the requested backend",
            details={"backendName": backend_name},
        )
    if backend["readyCount"] > 1:
        raise TransportError(
            code="AMBIGUOUS_RUNTIME",
            message="More than one ready runtime matches the requested backend",
            details={"backendName": backend_name, "count": backend["readyCount"]},
        )
    return backend["selected"]


def normalize_request_file(request_file: str) -> Path:
    path = Path(request_file).expanduser().resolve()
    if not path.is_absolute():
        raise TransportError(
            code="REQUEST_FILE_INVALID",
            message="Request files must resolve to absolute paths",
            details={"requestFile": request_file},
        )
    return path


def is_process_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def is_ready(runtime_status: dict[str, Any]) -> bool:
    return (
        runtime_status.get("state") == READINESS_STATE
        and bool(runtime_status.get("healthy"))
        and bool(runtime_status.get("active"))
        and not bool(runtime_status.get("indexing"))
    )


def http_request(
    descriptor: dict[str, Any],
    method: str,
    route: str,
    body: dict[str, Any] | None = None,
) -> dict[str, Any]:
    url = f"http://{descriptor['host']}:{descriptor['port']}{route}"
    data: bytes | None = None
    headers = {"Accept": "application/json"}
    token = descriptor.get("token")
    if token:
        headers["X-Kast-Token"] = token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url=url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return parse_json_text(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        payload = parse_json_text(error.read().decode("utf-8"))
        raise TransportError(
            code=payload.get("code", "HTTP_ERROR"),
            message=payload.get("message", f"HTTP {error.code} for {route}"),
            details={"route": route, "response": payload},
        ) from error
    except urllib.error.URLError as error:
        raise TransportError(
            code="HTTP_UNREACHABLE",
            message=f"Could not reach Kast runtime at {url}",
            details={"route": route, "error": str(error.reason)},
        ) from error


def parse_json_text(text: str) -> dict[str, Any]:
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        raise TransportError(
            code="INVALID_JSON",
            message="Expected JSON output but received something else",
            details={"error": str(error), "text": text},
        ) from error
