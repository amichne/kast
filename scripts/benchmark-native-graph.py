#!/usr/bin/env python3
"""Benchmark Kast's native graph through the live macOS IntelliJ IDEA plugin."""

import argparse
import collections
import datetime
import json
import math
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


SCOPES = ("symbol", "file", "package", "module")
OPERATIONS = ("summary", "neighbors", "topology", "communities")


class BenchmarkError(Exception):
    def __init__(self, code, message, help_text=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.help_text = help_text


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise BenchmarkError("CLI_USAGE", message, f"Run {self.prog} --help.")


def toon_string(value):
    return json.dumps(str(value), ensure_ascii=False)


def emit_result(name, values):
    print(f"{name}:")
    for key, value in values.items():
        if isinstance(value, bool):
            rendered = str(value).lower()
        elif isinstance(value, (int, float)):
            rendered = str(value)
        else:
            rendered = toon_string(value)
        print(f"  {key}: {rendered}")


def fail(error):
    values = {"code": error.code, "message": error.message}
    if error.help_text:
        values["help"] = error.help_text
    emit_result("error", values)
    return 2 if error.code == "CLI_USAGE" else 1


def parse_args(argv):
    parser = ArgumentParser(
        description=(
            "Build and traverse Kast's native graph through the live host IntelliJ "
            "IDEA plugin. Every run writes JSON evidence under build/benchmarks."
        )
    )
    parser.add_argument(
        "workspace",
        nargs="?",
        default=".",
        help="Kotlin repository to bootstrap in host IntelliJ IDEA (default: current directory)",
    )
    parser.add_argument(
        "--source-root",
        action="append",
        default=[],
        help="Relative or absolute subtree to benchmark; repeatable (default: workspace)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Maximum tracked Kotlin files to build; 0 means all (default: 0)",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=3,
        help="Repetitions for each native graph traversal (default: 3)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="Per-request timeout in seconds (default: 60)",
    )
    parser.add_argument(
        "--ready-timeout",
        type=float,
        default=300.0,
        help="Golden-path INDEXING to READY timeout in seconds (default: 300)",
    )
    parser.add_argument(
        "--kast",
        default="~/.local/share/kast/current/bin/kast",
        help="Current Kast CLI path",
    )
    parser.add_argument(
        "--database",
        help="Exact plugin source-index.db; normally discovered from the IDEA log",
    )
    parser.add_argument(
        "--idea-log",
        help="Exact host IDEA idea.log; normally the newest IntelliJIdea*/idea.log",
    )
    parser.add_argument(
        "--output-root",
        default=str(Path(__file__).resolve().parents[1] / "build" / "benchmarks"),
        help="Directory that receives timestamped run artifacts",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the harness's dependency-free behavioral checks",
    )
    return parser.parse_args(argv)


def load_json(path, code):
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise BenchmarkError(code, f"Cannot read {path}: {error}") from error


def validate_plugin_only(receipt, platform_name=sys.platform):
    if platform_name != "darwin":
        raise BenchmarkError(
            "MACOS_REQUIRED",
            "This benchmark is intentionally restricted to macOS host IntelliJ IDEA.",
        )
    components = receipt.get("components")
    if (
        receipt.get("profile") != "macos-idea"
        or not str(receipt.get("platform", "")).startswith("macos-")
        or sorted(components or []) != ["cli", "idea-plugin"]
    ):
        raise BenchmarkError(
            "PLUGIN_ONLY_REQUIRED",
            "The active Kast install must contain exactly the CLI and IDEA plugin.",
            "Refresh Kast with the macos-idea profile; do not install a headless backend.",
        )


def run_json(command, timeout):
    started = time.perf_counter()
    try:
        process = subprocess.run(
            [str(part) for part in command],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise BenchmarkError(
            "COMMAND_FAILED", f"Cannot run {' '.join(map(str, command))}: {error}"
        ) from error
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    try:
        payload = json.loads(process.stdout)
    except json.JSONDecodeError as error:
        detail = process.stderr.strip() or process.stdout.strip() or str(error)
        raise BenchmarkError("COMMAND_OUTPUT_INVALID", detail) from error
    return payload, process, elapsed_ms


def runtime_snapshot(payload, workspace, allowed_states):
    if payload.get("workspaceRoot") not in (None, str(workspace)):
        raise BenchmarkError(
            "WORKSPACE_MISMATCH", "Kast returned a different workspace root."
        )
    selected = payload.get("selected") or {}
    descriptor = selected.get("descriptor") or {}
    runtime_status = selected.get("runtimeStatus") or {}
    if (
        descriptor.get("workspaceRoot") != str(workspace)
        or runtime_status.get("workspaceRoot") not in (None, str(workspace))
    ):
        raise BenchmarkError(
            "WORKSPACE_MISMATCH", "Kast selected a different workspace root."
        )
    if descriptor.get("backendName") != "idea":
        raise BenchmarkError("HEADLESS_REFUSED", "The selected backend is not IntelliJ IDEA.")
    state = runtime_status.get("state")
    if state not in allowed_states:
        raise BenchmarkError(
            "IDEA_RUNTIME_STATE_INVALID", f"Unexpected IDEA runtime state: {state}"
        )
    return descriptor, runtime_status


def runtime_ready(runtime_status):
    return (
        runtime_status.get("state") == "READY"
        and runtime_status.get("referenceIndexReady") is True
    )


def bootstrap_idea(kast, workspace, request_timeout, ready_timeout):
    started = time.perf_counter()
    up_command = [
        kast,
        "--output",
        "json",
        "developer",
        "runtime",
        "up",
        "--workspace-root",
        workspace,
        "--backend",
        "idea",
        "--accept-indexing",
    ]
    up, up_process, up_ms = run_json(up_command, ready_timeout)
    if up_process.returncode != 0:
        raise BenchmarkError(
            str(up.get("code") or "IDEA_BOOTSTRAP_FAILED"),
            str(up.get("message") or "The macOS IDEA golden-path bootstrap failed."),
        )
    descriptor, runtime_status = runtime_snapshot(
        up, workspace, {"INDEXING", "READY"}
    )
    disposition = up.get("launchDisposition")
    if disposition not in {
        "REUSED_OPEN_PROJECT",
        "OPENED_IN_RUNNING_IDEA",
        "LAUNCHED_IDEA",
    }:
        raise BenchmarkError(
            "LAUNCH_DISPOSITION_INVALID",
            f"Golden-path bootstrap returned an invalid launch disposition: {disposition}",
        )
    transitions = [
        {
            "elapsedMs": round(up_ms, 3),
            "state": runtime_status["state"],
            "referenceIndexReady": bool(runtime_status.get("referenceIndexReady")),
        }
    ]
    previous = (
        runtime_status["state"],
        bool(runtime_status.get("referenceIndexReady")),
    )
    final_status = up
    deadline = started + ready_timeout
    while not runtime_ready(runtime_status):
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            raise BenchmarkError(
                "IDEA_READY_TIMEOUT",
                f"Exact-root IDEA runtime did not reach READY within {ready_timeout:g}s.",
            )
        time.sleep(min(2.0, remaining))
        status_command = [
            kast,
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace,
            "--backend",
            "idea",
        ]
        final_status, status_process, _ = run_json(status_command, request_timeout)
        if status_process.returncode != 0:
            raise BenchmarkError(
                "IDEA_STATUS_FAILED", "Cannot inspect the bootstrapped IDEA runtime."
            )
        descriptor, runtime_status = runtime_snapshot(
            final_status, workspace, {"INDEXING", "READY", "DEGRADED"}
        )
        if runtime_status["state"] == "DEGRADED":
            raise BenchmarkError(
                "IDEA_RUNTIME_DEGRADED",
                str(runtime_status.get("message") or "IDEA indexing degraded."),
            )
        current = (
            runtime_status["state"],
            bool(runtime_status.get("referenceIndexReady")),
        )
        if current != previous:
            transitions.append(
                {
                    "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
                    "state": current[0],
                    "referenceIndexReady": current[1],
                }
            )
            previous = current
    return {
        "up": up,
        "status": final_status,
        "descriptor": descriptor,
        "launchDisposition": disposition,
        "initialState": transitions[0]["state"],
        "readyMs": round((time.perf_counter() - started) * 1000.0, 3),
        "transitions": transitions,
    }


def idea_log_offsets(explicit=None):
    if explicit:
        paths = [Path(explicit).expanduser().resolve()]
    else:
        root = Path.home() / "Library" / "Logs" / "JetBrains"
        paths = list(root.glob("IntelliJIdea*/idea.log"))
    return {path: path.stat().st_size for path in paths if path.is_file()}


def newest_idea_log(explicit=None):
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not path.is_file():
            raise BenchmarkError("IDEA_LOG_MISSING", f"IDEA log does not exist: {path}")
        return path
    root = Path.home() / "Library" / "Logs" / "JetBrains"
    candidates = list(root.glob("IntelliJIdea*/idea.log"))
    if not candidates:
        raise BenchmarkError(
            "IDEA_LOG_MISSING",
            "No host IntelliJ IDEA idea.log was found.",
            "Pass --idea-log with the active host IDEA log.",
        )
    return max(candidates, key=lambda path: path.stat().st_mtime_ns)


def latest_logged_database(idea_log, workspace):
    try:
        with idea_log.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - 16 * 1024 * 1024))
            text = stream.read().decode("utf-8", errors="replace")
    except OSError:
        return None
    for line in reversed(text.splitlines()):
        marker = line.find("{")
        if marker < 0 or "sourceIndexDatabasePath" not in line:
            continue
        try:
            event = json.loads(line[marker:])
        except json.JSONDecodeError:
            continue
        if event.get("workspaceRoot") != str(workspace):
            continue
        detail = event.get("detail") or {}
        candidate = event.get("sourceIndexDatabasePath") or detail.get(
            "sourceIndexDatabasePath"
        )
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    return None


def database_candidates(receipt, workspace):
    install_root = Path(receipt["roots"]["install"])
    state_root = install_root / "state" / "workspaces"
    name_prefix = f"{workspace.name}--"
    return sorted(
        path
        for path in state_root.glob("**/cache/source-index.db")
        if path.parent.parent.name.startswith(name_prefix)
    )


def discover_database(explicit, receipt, idea_log, workspace):
    if explicit:
        database = Path(explicit).expanduser().resolve()
        if not database.is_file():
            raise BenchmarkError(
                "DATABASE_MISSING", f"source-index.db does not exist: {database}"
            )
        return database
    logged = latest_logged_database(idea_log, workspace)
    if logged:
        return logged
    candidates = database_candidates(receipt, workspace)
    if len(candidates) == 1:
        return candidates[0]
    raise BenchmarkError(
        "DATABASE_AMBIGUOUS",
        f"Found {len(candidates)} plugin databases for {workspace}.",
        "Pass --database with the sourceIndexDatabasePath from the Kast IDEA trace.",
    )


def tracked_kotlin_files(workspace, source_roots, limit):
    roots = []
    for raw_root in source_roots or [str(workspace)]:
        root = Path(raw_root).expanduser()
        if not root.is_absolute():
            root = workspace / root
        root = root.resolve()
        try:
            root.relative_to(workspace)
        except ValueError as error:
            raise BenchmarkError(
                "SOURCE_ROOT_OUTSIDE_WORKSPACE",
                f"Source root must be inside the workspace: {root}",
            ) from error
        if not root.exists():
            raise BenchmarkError("SOURCE_ROOT_MISSING", f"Source root does not exist: {root}")
        roots.append(root)

    process = subprocess.run(
        ["git", "-C", str(workspace), "ls-files", "-z", "--", "*.kt"],
        capture_output=True,
        check=False,
    )
    if process.returncode == 0:
        candidates = [
            (workspace / item.decode()).resolve()
            for item in process.stdout.split(b"\0")
            if item
        ]
    else:
        candidates = list(workspace.rglob("*.kt"))
    selected = sorted(
        {
            path
            for path in candidates
            if path.is_file() and any(path == root or root in path.parents for root in roots)
        }
    )
    if limit:
        selected = selected[:limit]
    if not selected:
        raise BenchmarkError(
            "NO_KOTLIN_FILES", "No tracked Kotlin files matched the selected source roots."
        )
    return selected


def rpc_request(socket_path, method, params, request_id, timeout):
    request = json.dumps(
        {
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
            "id": request_id,
        },
        separators=(",", ":"),
    ).encode()
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(timeout)
            client.connect(str(socket_path))
            client.sendall(request + b"\n")
            with client.makefile("rb") as response:
                line = response.readline()
    except OSError as error:
        raise BenchmarkError(
            "IDEA_RPC_FAILED", f"IDEA plugin request failed at {socket_path}: {error}"
        ) from error
    if not line:
        raise BenchmarkError("IDEA_RPC_MISSING", "IDEA plugin returned no JSON-RPC response.")
    try:
        return json.loads(line)
    except json.JSONDecodeError as error:
        raise BenchmarkError("IDEA_RPC_INVALID", f"Invalid IDEA response: {error}") from error


def timing_stats(values):
    if not values:
        return {"count": 0}
    ordered = sorted(values)

    def percentile(fraction):
        index = max(0, math.ceil(len(ordered) * fraction) - 1)
        return round(ordered[index], 3)

    return {
        "count": len(values),
        "totalMs": round(sum(values), 3),
        "minMs": round(ordered[0], 3),
        "p50Ms": percentile(0.50),
        "p95Ms": percentile(0.95),
        "maxMs": round(ordered[-1], 3),
    }


def response_error_code(response):
    error = response.get("error") or {}
    data = error.get("data") or {}
    return str(data.get("code") or error.get("code") or "RPC_ERROR")


def build_graph(workspace, files, socket_path, timeout, artifact):
    timings = []
    failures = collections.Counter()
    symbol_count = 0
    edge_count = 0
    final_generation = None
    with artifact.open("w") as output:
        for request_id, path in enumerate(files, start=1):
            started = time.perf_counter()
            response = rpc_request(
                socket_path,
                "raw/semantic-graph",
                {"filePaths": [str(path)], "removedFilePaths": []},
                request_id,
                timeout,
            )
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            timings.append(elapsed_ms)
            result = response.get("result")
            ok = result is not None and "error" not in response
            if ok:
                symbol_count += int(result.get("symbolCount", 0))
                edge_count += int(result.get("edgeOccurrenceCount", 0))
                final_generation = result.get("generation", final_generation)
            else:
                failures[response_error_code(response)] += 1
            output.write(
                json.dumps(
                    {
                        "file": str(path.relative_to(workspace)),
                        "elapsedMs": round(elapsed_ms, 3),
                        "ok": ok,
                        "response": response,
                    },
                    separators=(",", ":"),
                )
                + "\n"
            )
            if request_id % 25 == 0 or request_id == len(files):
                print(
                    f"native graph build: {request_id}/{len(files)} files",
                    file=sys.stderr,
                    flush=True,
                )
    return {
        "requestedFiles": len(files),
        "successfulFiles": len(files) - sum(failures.values()),
        "failedFiles": sum(failures.values()),
        "failureCodes": dict(sorted(failures.items())),
        "reportedSymbolCount": symbol_count,
        "reportedEdgeOccurrenceCount": edge_count,
        "finalGeneration": final_generation,
        "timings": timing_stats(timings),
    }


def graph_seeds(database):
    queries = {
        "symbol": (
            "SELECT symbols.stable_key FROM semantic_edge_occurrences edges "
            "JOIN semantic_symbols symbols ON symbols.id = edges.source_id "
            "GROUP BY symbols.id ORDER BY COUNT(*) DESC, symbols.stable_key LIMIT 1",
            "SELECT stable_key FROM semantic_symbols ORDER BY stable_key LIMIT 1",
        ),
        "file": (
            "SELECT files.path FROM semantic_file_quotient quotient "
            "JOIN semantic_files files ON files.id = quotient.source_container_id "
            "GROUP BY files.id ORDER BY SUM(quotient.weight) DESC, files.path LIMIT 1",
            "SELECT path FROM semantic_files ORDER BY path LIMIT 1",
        ),
        "package": (
            "SELECT source_container FROM semantic_package_quotient "
            "GROUP BY source_container ORDER BY SUM(weight) DESC, source_container LIMIT 1",
            "SELECT package_name FROM semantic_files WHERE package_name IS NOT NULL "
            "ORDER BY package_name LIMIT 1",
        ),
        "module": (
            "SELECT source_container FROM semantic_module_quotient "
            "GROUP BY source_container ORDER BY SUM(weight) DESC, source_container LIMIT 1",
            "SELECT module_name FROM semantic_files WHERE module_name IS NOT NULL "
            "ORDER BY module_name LIMIT 1",
        ),
    }
    seeds = {}
    try:
        connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    except sqlite3.Error as error:
        raise BenchmarkError("DATABASE_UNAVAILABLE", str(error)) from error
    with connection:
        for scope, choices in queries.items():
            seed = None
            for query in choices:
                try:
                    row = connection.execute(query).fetchone()
                except sqlite3.Error:
                    continue
                if row and row[0] is not None:
                    seed = str(row[0])
                    break
            seeds[scope] = seed
    return seeds


def graph_command(kast, workspace, database, scope, operation, generation, seed):
    command = [
        kast,
        "--output",
        "json",
        "agent",
        "graph",
        "--workspace-root",
        workspace,
        "--backend",
        "idea",
        "--database",
        database,
        "--scope",
        scope,
        "--operation",
        operation,
    ]
    if generation is not None:
        command.extend(["--generation", str(generation)])
    if operation == "neighbors":
        command.extend(["--symbol", seed])
    return command


def traverse_graph(kast, workspace, database, iterations, timeout, output_dir):
    seeds = graph_seeds(database)
    reports = []
    generation = None
    output_dir.mkdir()
    for scope in SCOPES:
        for operation in OPERATIONS:
            seed = seeds[scope]
            if operation == "neighbors" and seed is None:
                reports.append(
                    {
                        "scope": scope,
                        "operation": operation,
                        "status": "skipped",
                        "reason": "scope has no nodes",
                    }
                )
                continue
            timings = []
            last_payload = None
            stderr = ""
            status = "ok"
            for _ in range(iterations):
                command = graph_command(
                    kast,
                    workspace,
                    database,
                    scope,
                    operation,
                    generation,
                    seed,
                )
                payload, process, elapsed_ms = run_json(command, timeout)
                timings.append(elapsed_ms)
                last_payload = payload
                stderr = process.stderr.strip()
                if process.returncode != 0 or payload.get("ok") is False:
                    status = "failed"
                    break
                if generation is None:
                    generation = payload.get("result", {}).get("generation")
                    if generation is None:
                        raise BenchmarkError(
                            "GRAPH_GENERATION_MISSING",
                            "Native graph summary did not return a generation.",
                        )
            artifact = output_dir / f"{scope}-{operation}.json"
            artifact.write_text(json.dumps(last_payload, indent=2) + "\n")
            result = (last_payload or {}).get("result") or {}
            reports.append(
                {
                    "scope": scope,
                    "operation": operation,
                    "status": status,
                    "seed": seed if operation == "neighbors" else None,
                    "timings": timing_stats(timings),
                    "measurements": result.get("measurements"),
                    "stderr": stderr or None,
                    "artifact": str(artifact),
                }
            )
    return generation, seeds, reports


def capture_idea_logs(idea_log, workspace, start_offset, run_artifact, indexing_artifact):
    try:
        size = idea_log.stat().st_size
        offset = start_offset if size >= start_offset else 0
        with idea_log.open("rb") as source:
            source.seek(offset)
            run_data = source.read()
        relevant_lines = []
        project_markers = (str(workspace), f"[{workspace.name}]", f"={workspace.name},")
        with idea_log.open(errors="replace") as source:
            for line in source:
                lower = line.lower()
                if ("index" in lower or "kast" in lower) and any(
                    marker in line for marker in project_markers
                ):
                    relevant_lines.append(line)
    except OSError as error:
        raise BenchmarkError("IDEA_LOG_CAPTURE_FAILED", str(error)) from error
    run_artifact.write_bytes(run_data)
    indexing_artifact.write_text("".join(relevant_lines))
    text = run_data.decode("utf-8", errors="replace")
    lines = text.splitlines()
    return {
        "source": str(idea_log),
        "runArtifact": str(run_artifact),
        "indexingArtifact": str(indexing_artifact),
        "bytes": len(run_data),
        "lines": len(lines),
        "indexingEvidenceLines": len(relevant_lines),
        "indexingLines": sum("index" in line.lower() for line in lines),
        "kastLines": sum("kast" in line.lower() for line in lines),
        "graphLines": sum(
            "semantic.graph" in line.lower()
            or "semantic-graph" in line.lower()
            or "raw/semantic-graph" in line.lower()
            for line in lines
        ),
    }


def git_metadata(workspace):
    def git(*args):
        process = subprocess.run(
            ["git", "-C", str(workspace), *args],
            capture_output=True,
            text=True,
            check=False,
        )
        return process.stdout.strip() if process.returncode == 0 else None

    return {
        "commit": git("rev-parse", "HEAD"),
        "dirty": bool(git("status", "--porcelain")),
    }


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def run_benchmark(args):
    if args.limit < 0:
        raise BenchmarkError("CLI_USAGE", "--limit must be zero or greater.")
    if args.iterations < 1:
        raise BenchmarkError("CLI_USAGE", "--iterations must be at least one.")
    if args.timeout <= 0:
        raise BenchmarkError("CLI_USAGE", "--timeout must be greater than zero.")
    if args.ready_timeout <= 0:
        raise BenchmarkError("CLI_USAGE", "--ready-timeout must be greater than zero.")

    workspace = Path(args.workspace).expanduser().resolve()
    if not workspace.is_dir():
        raise BenchmarkError("WORKSPACE_MISSING", f"Workspace does not exist: {workspace}")
    kast = Path(args.kast).expanduser().absolute()
    if not kast.is_file():
        raise BenchmarkError("KAST_MISSING", f"Kast CLI does not exist: {kast}")
    receipt_path = kast.parent.parent / "receipt.json"
    receipt = load_json(receipt_path, "INSTALL_RECEIPT_INVALID")
    validate_plugin_only(receipt)
    log_offsets = idea_log_offsets(args.idea_log)

    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    run_dir = Path(args.output_root).expanduser().resolve() / workspace.name / timestamp
    run_dir.mkdir(parents=True)
    started_at = datetime.datetime.now(datetime.timezone.utc)
    started = time.perf_counter()

    bootstrap = bootstrap_idea(
        kast, workspace, args.timeout, args.ready_timeout
    )
    descriptor = bootstrap["descriptor"]
    if descriptor.get("backendVersion") != receipt.get("activeVersion"):
        raise BenchmarkError(
            "PLUGIN_VERSION_MISMATCH",
            "The live IDEA plugin and current Kast CLI versions do not match.",
            "Refresh the plugin before benchmarking.",
        )

    ready_command = [
        kast,
        "--output",
        "json",
        "ready",
        "--workspace-root",
        workspace,
        "--backend",
        "idea",
        "--for",
        "agent",
    ]
    ready, ready_process, ready_ms = run_json(ready_command, args.timeout)
    if ready_process.returncode != 0 or not ready.get("ok"):
        raise BenchmarkError(
            "IDEA_PLUGIN_NOT_READY",
            "The bootstrapped host IDEA plugin failed final agent readiness.",
        )
    backend = ready.get("agentEnvironment", {}).get("backend", {})
    if backend.get("kind") != "idea":
        raise BenchmarkError(
            "HEADLESS_REFUSED", f"Readiness selected a non-IDEA backend: {backend.get('kind')}"
        )

    status = bootstrap["status"]
    selected = status.get("selected") or {}
    capabilities = selected.get("capabilities", {}).get("readCapabilities", [])
    if "SEMANTIC_GRAPH" not in capabilities:
        raise BenchmarkError(
            "SEMANTIC_GRAPH_UNAVAILABLE",
            "The live IDEA plugin does not expose SEMANTIC_GRAPH.",
        )
    socket_path = Path(descriptor["socketPath"])
    if not socket_path.exists():
        raise BenchmarkError("IDEA_SOCKET_MISSING", f"IDEA socket is missing: {socket_path}")

    idea_log = newest_idea_log(args.idea_log)
    log_start = log_offsets.get(idea_log, 0)
    database = discover_database(args.database, receipt, idea_log, workspace)
    files = tracked_kotlin_files(workspace, args.source_root, args.limit)
    write_json(
        run_dir / "preflight.json",
        {
            "receipt": receipt,
            "up": bootstrap["up"],
            "ready": ready,
            "status": status,
            "timings": {
                "bootstrapReadyMs": bootstrap["readyMs"],
                "agentReadyMs": round(ready_ms, 3),
            },
        },
    )

    build = build_graph(
        workspace,
        files,
        socket_path,
        args.timeout,
        run_dir / "semantic-graph-build.jsonl",
    )
    generation, seeds, traversals = traverse_graph(
        kast,
        workspace,
        database,
        args.iterations,
        args.timeout,
        run_dir / "traversal",
    )
    idea_evidence = capture_idea_logs(
        idea_log,
        workspace,
        log_start,
        run_dir / "idea-run.log",
        run_dir / "idea-indexing.log",
    )
    finished_at = datetime.datetime.now(datetime.timezone.utc)
    partial = build["failedFiles"] > 0 or any(
        report["status"] == "failed" for report in traversals
    )
    summary = {
        "schemaVersion": 1,
        "status": "partial" if partial else "complete",
        "startedAt": started_at.isoformat(),
        "finishedAt": finished_at.isoformat(),
        "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
        "workspace": str(workspace),
        "git": git_metadata(workspace),
        "installation": {
            "version": receipt.get("activeVersion"),
            "profile": receipt.get("profile"),
            "platform": receipt.get("platform"),
            "components": receipt.get("components"),
            "backend": descriptor,
        },
        "inputs": {
            "sourceRoots": args.source_root or ["."],
            "limit": args.limit,
            "iterations": args.iterations,
            "timeoutSeconds": args.timeout,
            "readyTimeoutSeconds": args.ready_timeout,
            "database": str(database),
            "kotlinFiles": len(files),
        },
        "bootstrap": {
            "launchDisposition": bootstrap["launchDisposition"],
            "initialState": bootstrap["initialState"],
            "readyMs": bootstrap["readyMs"],
            "transitions": bootstrap["transitions"],
        },
        "graphBuild": build,
        "nativeGraph": {
            "generation": generation,
            "seeds": seeds,
            "traversals": traversals,
        },
        "ideaLog": idea_evidence,
        "artifacts": {
            "runDirectory": str(run_dir),
            "preflight": str(run_dir / "preflight.json"),
            "build": str(run_dir / "semantic-graph-build.jsonl"),
            "traversalDirectory": str(run_dir / "traversal"),
        },
    }
    summary_path = run_dir / "summary.json"
    write_json(summary_path, summary)
    emit_result(
        "benchmark",
        {
            "status": summary["status"],
            "workspace": workspace,
            "files": len(files),
            "graphFailures": build["failedFiles"],
            "generation": generation,
            "launchDisposition": bootstrap["launchDisposition"],
            "readyMs": bootstrap["readyMs"],
            "summary": summary_path,
        },
    )
    return 0


def self_test():
    good_receipt = {
        "profile": "macos-idea",
        "platform": "macos-arm64",
        "components": ["cli", "idea-plugin"],
    }
    validate_plugin_only(good_receipt, "darwin")
    try:
        validate_plugin_only({**good_receipt, "components": ["cli", "headless"]}, "darwin")
    except BenchmarkError as error:
        assert error.code == "PLUGIN_ONLY_REQUIRED"
    else:
        raise AssertionError("headless install was accepted")
    assert timing_stats([5.0, 1.0, 3.0])["p50Ms"] == 3.0
    workspace = Path("/repo")
    indexing = {
        "workspaceRoot": str(workspace),
        "launchDisposition": "LAUNCHED_IDEA",
        "selected": {
            "descriptor": {
                "workspaceRoot": str(workspace),
                "backendName": "idea",
                "socketPath": "/tmp/idea.sock",
            },
            "runtimeStatus": {
                "workspaceRoot": str(workspace),
                "state": "INDEXING",
                "referenceIndexReady": False,
            },
        },
    }
    ready = json.loads(json.dumps(indexing))
    ready.pop("launchDisposition")
    ready["selected"]["runtimeStatus"].update(
        {"state": "READY", "referenceIndexReady": True}
    )

    class FakeProcess:
        returncode = 0

    commands = []
    responses = iter((indexing, ready))
    original_run_json = globals()["run_json"]
    original_sleep = time.sleep
    try:
        globals()["run_json"] = lambda command, timeout: (
            commands.append(command) or next(responses),
            FakeProcess(),
            5.0,
        )
        time.sleep = lambda _: None
        bootstrap = bootstrap_idea(Path("/kast"), workspace, 1.0, 5.0)
    finally:
        globals()["run_json"] = original_run_json
        time.sleep = original_sleep
    assert commands[0][3:6] == ["developer", "runtime", "up"]
    assert "--accept-indexing" in commands[0]
    assert bootstrap["initialState"] == "INDEXING"
    assert bootstrap["status"]["selected"]["runtimeStatus"]["state"] == "READY"

    with tempfile.TemporaryDirectory() as directory:
        socket_path = Path(directory) / "idea.sock"
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(str(socket_path))
        listener.listen(1)

        def serve():
            connection, _ = listener.accept()
            with connection:
                request = connection.makefile("rb").readline()
                parsed = json.loads(request)
                assert parsed["method"] == "raw/semantic-graph"
                connection.sendall(b'{"jsonrpc":"2.0","id":1,"result":{"generation":7}}\n')
            listener.close()

        thread = threading.Thread(target=serve)
        thread.start()
        response = rpc_request(socket_path, "raw/semantic-graph", {}, 1, 1.0)
        thread.join()
        assert response["result"]["generation"] == 7

    emit_result("selfTest", {"ok": True, "checks": 5})
    return 0


def main(argv=None):
    try:
        args = parse_args(argv)
        return self_test() if args.self_test else run_benchmark(args)
    except BenchmarkError as error:
        return fail(error)
    except (AssertionError, KeyError, OSError, sqlite3.Error) as error:
        return fail(BenchmarkError("BENCHMARK_FAILED", str(error)))


if __name__ == "__main__":
    raise SystemExit(main())
