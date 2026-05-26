#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


class RpcFailure(RuntimeError):
    pass


class RpcClient:
    def __init__(self, host: str, port: int, timeout_seconds: float = 300.0) -> None:
        self.host = host
        self.port = port
        self.timeout_seconds = timeout_seconds
        self._next_id = 1

    def request(self, method: str, params: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
        request_id = self._next_id
        self._next_id += 1
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        started = time.monotonic_ns()
        with socket.create_connection((self.host, self.port), timeout=self.timeout_seconds) as sock:
            sock.settimeout(self.timeout_seconds)
            with sock.makefile("rwb") as stream:
                stream.write(json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n")
                stream.flush()
                raw = stream.readline()
        duration_ms = (time.monotonic_ns() - started) / 1_000_000

        if not raw:
            raise RpcFailure(f"{method}: no response")
        response = json.loads(raw.decode("utf-8"))
        if "error" in response:
            raise RpcFailure(f"{method}: {response['error']}")
        return response["result"], duration_ms


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a repeatable kast standalone profiling workload.")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--target-label", required=True)
    parser.add_argument("--ready-timeout-seconds", type=float, default=600.0)
    parser.add_argument("--start-monotonic-ns", type=int, required=True)
    parser.add_argument("--include-refresh", choices=["true", "false"], default="false")
    parser.add_argument("--profile-mode", default="default")
    parser.add_argument("--profile-run-index", type=int, default=0)
    return parser.parse_args()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def append_jsonl(path: Path, value: Any, profile_mode: str, profile_run_index: int) -> None:
    if isinstance(value, dict):
        value = {
            "profileMode": profile_mode,
            "profileRunIndex": profile_run_index,
            **value,
        }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, sort_keys=True) + "\n")


def read_json(path: Path) -> Any | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def git_head(workspace: Path) -> str | None:
    if not (workspace / ".git").exists():
        return None
    try:
        return subprocess.check_output(
            ["git", "-C", str(workspace), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def count_files(workspace: Path, suffixes: tuple[str, ...]) -> int:
    ignored_parts = {".git", ".gradle", "build", "out"}
    count = 0
    for path in workspace.rglob("*"):
        if any(part in ignored_parts for part in path.parts):
            continue
        if path.is_file() and path.name.endswith(suffixes):
            count += 1
    return count


def wrapper_distribution(workspace: Path) -> str | None:
    wrapper = workspace / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not wrapper.exists():
        return None
    for line in wrapper.read_text(encoding="utf-8").splitlines():
        if line.startswith("distributionUrl="):
            return line.split("=", 1)[1]
    return None


def sample_kotlin_files(workspace: Path, limit: int = 3) -> list[Path]:
    ignored_parts = {".git", ".gradle", "build", "out"}
    files = [
        path
        for path in workspace.rglob("*.kt")
        if path.is_file() and not any(part in ignored_parts for part in path.parts)
    ]
    files.sort()
    if len(files) <= limit:
        return files
    return [files[0], files[len(files) // 2], files[-1]]


def symbol_offset(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    for needle in ("Service", "compute", "Entry", "class ", "fun "):
        index = text.find(needle)
        if index >= 0:
            return index + (0 if needle.endswith(" ") else max(0, len(needle) // 2))
    return 0


def result_summary(result: Any) -> dict[str, Any]:
    if not isinstance(result, dict):
        return {"type": type(result).__name__}
    summary: dict[str, Any] = {}
    if "state" in result:
        summary["state"] = result.get("state")
        summary["indexing"] = result.get("indexing")
        summary["referenceIndexReady"] = result.get("referenceIndexReady")
        summary["sourceModuleCount"] = len(result.get("sourceModuleNames", []))
    if "modules" in result:
        modules = result.get("modules", [])
        summary["moduleCount"] = len(modules)
        summary["fileCount"] = sum(module.get("fileCount", 0) for module in modules)
        summary["returnedFileCount"] = sum(len(module.get("files", [])) for module in modules)
    if "matches" in result:
        summary["matchCount"] = len(result.get("matches", []))
        summary["truncated"] = result.get("truncated")
    if "symbols" in result:
        summary["symbolCount"] = len(result.get("symbols", []))
    if "diagnostics" in result:
        summary["diagnosticCount"] = len(result.get("diagnostics", []))
    if "references" in result:
        summary["referenceCount"] = len(result.get("references", []))
    if "refreshedFiles" in result:
        summary["refreshedFileCount"] = len(result.get("refreshedFiles", []))
        summary["removedFileCount"] = len(result.get("removedFiles", []))
        summary["fullRefresh"] = result.get("fullRefresh")
    return summary


def wait_for_health(
    client: RpcClient,
    timeout_seconds: float,
    start_monotonic_ns: int,
    events_path: Path,
    profile_mode: str,
    profile_run_index: int,
) -> tuple[dict[str, Any] | None, float | None]:
    deadline = time.monotonic() + timeout_seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            result, duration_ms = client.request("health")
            elapsed_ms = (time.monotonic_ns() - start_monotonic_ns) / 1_000_000
            append_jsonl(
                events_path,
                {
                    "operation": "wait.health",
                    "method": "health",
                    "ok": True,
                    "durationMillis": duration_ms,
                    "elapsedSinceDaemonStartMillis": elapsed_ms,
                    "summary": result_summary(result),
                },
                profile_mode,
                profile_run_index,
            )
            return result, elapsed_ms
        except Exception as exc:  # noqa: BLE001 - this is a polling boundary
            last_error = str(exc)
            time.sleep(1)
    append_jsonl(
        events_path,
        {
            "operation": "wait.health",
            "method": "health",
            "ok": False,
            "error": last_error,
        },
        profile_mode,
        profile_run_index,
    )
    return None, None


def wait_for_ready(
    client: RpcClient,
    timeout_seconds: float,
    start_monotonic_ns: int,
    events_path: Path,
    profile_mode: str,
    profile_run_index: int,
) -> tuple[dict[str, Any] | None, float | None, bool]:
    deadline = time.monotonic() + timeout_seconds
    last_status = None
    while time.monotonic() < deadline:
        try:
            result, duration_ms = client.request("runtime/status")
            last_status = result
            elapsed_ms = (time.monotonic_ns() - start_monotonic_ns) / 1_000_000
            append_jsonl(
                events_path,
                {
                    "operation": "wait.ready",
                    "method": "runtime/status",
                    "ok": True,
                    "durationMillis": duration_ms,
                    "elapsedSinceDaemonStartMillis": elapsed_ms,
                    "summary": result_summary(result),
                },
                profile_mode,
                profile_run_index,
            )
            if not result.get("indexing", True):
                return result, elapsed_ms, False
        except Exception as exc:  # noqa: BLE001 - this is a polling boundary
            append_jsonl(
                events_path,
                {
                    "operation": "wait.ready",
                    "method": "runtime/status",
                    "ok": False,
                    "error": str(exc),
                },
                profile_mode,
                profile_run_index,
            )
        time.sleep(2)
    return last_status, None, True


def run_rpc(
    client: RpcClient,
    events_path: Path,
    operation: str,
    method: str,
    params: dict[str, Any] | None = None,
    required: bool = True,
    profile_mode: str = "default",
    profile_run_index: int = 0,
) -> dict[str, Any] | None:
    try:
        result, duration_ms = client.request(method, params)
        append_jsonl(
            events_path,
            {
                "operation": operation,
                "method": method,
                "ok": True,
                "durationMillis": duration_ms,
                "summary": result_summary(result),
            },
            profile_mode,
            profile_run_index,
        )
        return result
    except Exception as exc:  # noqa: BLE001 - records the failed operation in the artifact
        append_jsonl(
            events_path,
            {
                "operation": operation,
                "method": method,
                "ok": False,
                "error": str(exc),
            },
            profile_mode,
            profile_run_index,
        )
        if required:
            raise
        return None


def workload(
    client: RpcClient,
    workspace: Path,
    events_path: Path,
    include_refresh: bool,
    profile_mode: str,
    profile_run_index: int,
) -> dict[str, Any]:
    final_status = run_rpc(
        client,
        events_path,
        "status.final",
        "runtime/status",
        profile_mode=profile_mode,
        profile_run_index=profile_run_index,
    )
    modules_result = run_rpc(
        client,
        events_path,
        "workspace.files.modules",
        "raw/workspace-files",
        {"includeFiles": False},
        profile_mode=profile_mode,
        profile_run_index=profile_run_index,
    )
    run_rpc(
        client,
        events_path,
        "workspace.files.sample",
        "raw/workspace-files",
        {"includeFiles": True, "maxFilesPerModule": 5},
        profile_mode=profile_mode,
        profile_run_index=profile_run_index,
    )
    run_rpc(
        client,
        events_path,
        "workspace.search.compute",
        "raw/workspace-search",
        {"pattern": "compute", "maxResults": 200, "fileGlob": "**/*.kt"},
        required=False,
        profile_mode=profile_mode,
        profile_run_index=profile_run_index,
    )
    run_rpc(
        client,
        events_path,
        "workspace.symbol.service",
        "raw/workspace-symbol",
        {"pattern": "Service", "maxResults": 200},
        required=False,
        profile_mode=profile_mode,
        profile_run_index=profile_run_index,
    )

    samples = sample_kotlin_files(workspace)
    for index, sample in enumerate(samples):
        file_path = str(sample.resolve())
        position = {"filePath": file_path, "offset": symbol_offset(sample)}
        run_rpc(
            client,
            events_path,
            f"kotlin.fileOutline.{index}",
            "raw/file-outline",
            {"filePath": file_path},
            required=False,
            profile_mode=profile_mode,
            profile_run_index=profile_run_index,
        )
        run_rpc(
            client,
            events_path,
            f"kotlin.diagnostics.{index}",
            "raw/diagnostics",
            {"filePaths": [file_path]},
            required=False,
            profile_mode=profile_mode,
            profile_run_index=profile_run_index,
        )
        run_rpc(
            client,
            events_path,
            f"kotlin.resolve.{index}",
            "raw/resolve",
            {"position": position},
            required=False,
            profile_mode=profile_mode,
            profile_run_index=profile_run_index,
        )
        if index == len(samples) // 2:
            run_rpc(
                client,
                events_path,
                f"kotlin.references.{index}",
                "raw/references",
                {"position": position, "includeDeclaration": True},
                required=False,
                profile_mode=profile_mode,
                profile_run_index=profile_run_index,
            )

    if include_refresh:
        run_rpc(
            client,
            events_path,
            "workspace.refresh.full",
            "raw/workspace-refresh",
            {"filePaths": []},
            required=False,
            profile_mode=profile_mode,
            profile_run_index=profile_run_index,
        )

    return {
        "finalStatus": final_status,
        "workspaceFiles": modules_result,
        "sampledKotlinFiles": [str(path.resolve()) for path in samples],
    }


def main() -> int:
    args = parse_args()
    args.results.mkdir(parents=True, exist_ok=True)
    events_path = args.results / "rpc-latencies.jsonl"
    client = RpcClient(args.host, args.port)

    health, time_to_health_ms = wait_for_health(
        client,
        timeout_seconds=args.ready_timeout_seconds,
        start_monotonic_ns=args.start_monotonic_ns,
        events_path=events_path,
        profile_mode=args.profile_mode,
        profile_run_index=args.profile_run_index,
    )
    if health is None:
        write_json(args.results / "summary.json", {
            "targetLabel": args.target_label,
            "profileMode": args.profile_mode,
            "profileRunIndex": args.profile_run_index,
            "error": "kast standalone did not answer health before timeout",
        })
        return 1

    ready_status, time_to_ready_ms, ready_timed_out = wait_for_ready(
        client,
        timeout_seconds=args.ready_timeout_seconds,
        start_monotonic_ns=args.start_monotonic_ns,
        events_path=events_path,
        profile_mode=args.profile_mode,
        profile_run_index=args.profile_run_index,
    )
    workload_result = workload(
        client,
        workspace=args.workspace,
        events_path=events_path,
        include_refresh=args.include_refresh == "true",
        profile_mode=args.profile_mode,
        profile_run_index=args.profile_run_index,
    )

    workspace_files = workload_result.get("workspaceFiles") or {}
    modules = workspace_files.get("modules", []) if isinstance(workspace_files, dict) else []
    current_run = {
        "profileMode": args.profile_mode,
        "profileRunIndex": args.profile_run_index,
        "startup": {
            "timeToHealthMillis": time_to_health_ms,
            "timeToReadyMillis": time_to_ready_ms,
            "readyTimedOut": ready_timed_out,
            "readyStatus": result_summary(ready_status or {}),
        },
        "sampledKotlinFiles": workload_result.get("sampledKotlinFiles", []),
        "artifacts": {
            "rpcLatencies": str(events_path),
            "telemetry": str(args.results / "telemetry" / f"standalone-spans-{args.profile_run_index}-{args.profile_mode}.jsonl"),
            "profiling": str(args.results / "profiling" / f"startup-workload-{args.profile_run_index}-{args.profile_mode}.html"),
            "jfr": str(args.results / "jfr" / f"startup-workload-{args.profile_run_index}-{args.profile_mode}.jfr"),
            "diagnosticsDir": str(args.results / "diagnostics" / f"{args.profile_run_index}-{args.profile_mode}"),
            "logsDir": str(args.results / "logs" / f"{args.profile_run_index}-{args.profile_mode}"),
        },
    }
    existing_summary = read_json(args.results / "summary.json")
    existing_profile_runs = []
    if isinstance(existing_summary, dict):
        existing_profile_runs = [
            run
            for run in existing_summary.get("profileRuns", [])
            if isinstance(run, dict)
            and (
                run.get("profileMode") != args.profile_mode
                or run.get("profileRunIndex") != args.profile_run_index
            )
        ]
    profile_runs = [*existing_profile_runs, current_run]
    profile_modes = {run["profileMode"] for run in profile_runs if "profileMode" in run}

    summary = {
        "targetLabel": args.target_label,
        "workspace": str(args.workspace.resolve()),
        "profileMode": args.profile_mode,
        "profileRunIndex": args.profile_run_index,
        "profileModes": sorted(profile_modes),
        "profileRuns": profile_runs,
        "gitHead": git_head(args.workspace),
        "wrapperDistributionUrl": wrapper_distribution(args.workspace),
        "javaVersion": os.popen("java -version 2>&1").read().strip().splitlines(),
        "counts": {
            "buildGradleFiles": count_files(args.workspace, ("build.gradle", "build.gradle.kts")),
            "kotlinFiles": count_files(args.workspace, (".kt", ".kts")),
            "javaFiles": count_files(args.workspace, (".java",)),
            "workspaceModules": len(modules),
            "workspaceKotlinFiles": sum(module.get("fileCount", 0) for module in modules),
        },
        "startup": {
            "timeToHealthMillis": time_to_health_ms,
            "timeToReadyMillis": time_to_ready_ms,
            "readyTimedOut": ready_timed_out,
            "readyStatus": result_summary(ready_status or {}),
        },
        "sampledKotlinFiles": workload_result.get("sampledKotlinFiles", []),
        "artifacts": {
            "rpcLatencies": str(events_path),
            "telemetryDir": str(args.results / "telemetry"),
            "profilingDir": str(args.results / "profiling"),
            "jfrDir": str(args.results / "jfr"),
            "diagnosticsDir": str(args.results / "diagnostics"),
            "logsDir": str(args.results / "logs"),
        },
    }
    write_json(args.results / "summary.json", summary)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
