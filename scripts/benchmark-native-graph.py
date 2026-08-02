#!/usr/bin/env python3
"""Benchmark Kast native graph refresh and traversal through one headless runtime."""

import argparse
import collections
import datetime
import json
import math
import sqlite3
import subprocess
import sys
import tempfile
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
            "Refresh and traverse Kast native graph evidence through the exact-root "
            "headless runtime. Every run writes JSON evidence under build/benchmarks."
        )
    )
    parser.add_argument(
        "workspace",
        nargs="?",
        default=".",
        help="Kotlin repository to benchmark (default: current directory)",
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
        help="Maximum tracked Kotlin files to refresh; 0 means all (default: 0)",
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
        help="Public exact-root readiness timeout in seconds (default: 300)",
    )
    parser.add_argument(
        "--kast",
        default="~/.local/share/kast/current/bin/kast",
        help="Current public Kast CLI path",
    )
    parser.add_argument(
        "--kastctl",
        default=None,
        help=(
            "Private diagnostic CLI path (default: "
            "~/.local/share/kast/current/libexec/kastctl beside --kast)"
        ),
    )
    parser.add_argument(
        "--database",
        help="Exact headless source-index.db; normally discovered from the install receipt",
    )
    parser.add_argument(
        "--output-root",
        default=str(Path(__file__).resolve().parents[1] / "build" / "benchmarks"),
        help="Directory that receives timestamped run artifacts",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the harness dependency-free behavioral checks",
    )
    return parser.parse_args(argv)


def load_json(path, code):
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise BenchmarkError(code, f"Cannot read {path}: {error}") from error


def validate_headless_install(receipt):
    components = sorted(receipt.get("components") or [])
    backends = receipt.get("backends") or []
    if components != ["cli", "headless-backend", "manifest"]:
        raise BenchmarkError(
            "HEADLESS_INSTALL_REQUIRED",
            "The active Kast install must contain the CLI, manifest, and headless backend.",
            "Refresh Kast from a current headless release bundle.",
        )
    if len(backends) != 1 or backends[0].get("name") != "headless":
        raise BenchmarkError(
            "HEADLESS_INSTALL_REQUIRED",
            "The active Kast receipt must declare exactly one headless backend.",
        )


def resolve_commands(args):
    kast = Path(args.kast).expanduser().absolute()
    if not kast.is_file():
        raise BenchmarkError("KAST_MISSING", f"Kast agent CLI does not exist: {kast}")
    if args.kastctl:
        kastctl = Path(args.kastctl).expanduser().absolute()
    else:
        kastctl = kast.parent.parent / "libexec" / "kastctl"
    if not kastctl.is_file():
        raise BenchmarkError(
            "KASTCTL_MISSING", f"Kast diagnostic CLI does not exist: {kastctl}"
        )
    return kast, kastctl


def run_process(command, timeout, cwd=None):
    started = time.perf_counter()
    try:
        process = subprocess.run(
            [str(part) for part in command],
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise BenchmarkError(
            "COMMAND_FAILED", f"Cannot run {' '.join(map(str, command))}: {error}"
        ) from error
    return process, (time.perf_counter() - started) * 1000.0


def run_json(command, timeout, cwd=None):
    process, elapsed_ms = run_process(command, timeout, cwd)
    try:
        payload = json.loads(process.stdout)
    except json.JSONDecodeError as error:
        detail = process.stderr.strip() or process.stdout.strip() or str(error)
        raise BenchmarkError("COMMAND_OUTPUT_INVALID", detail) from error
    return payload, process, elapsed_ms


def runtime_snapshot(payload, workspace):
    if payload.get("workspaceRoot") not in (None, str(workspace)):
        raise BenchmarkError("WORKSPACE_MISMATCH", "Kast returned a different workspace root.")
    selected = payload.get("selected") or {}
    descriptor = selected.get("descriptor") or {}
    runtime_status = selected.get("runtimeStatus") or {}
    if descriptor.get("workspaceRoot") != str(workspace):
        raise BenchmarkError("WORKSPACE_MISMATCH", "Kast selected a different workspace root.")
    if descriptor.get("backendName") != "headless":
        raise BenchmarkError(
            "HEADLESS_AUTHORITY_REQUIRED", "The selected backend is not headless."
        )
    if runtime_status.get("state") != "READY" or runtime_status.get("healthy") is not True:
        raise BenchmarkError(
            "HEADLESS_RUNTIME_NOT_READY",
            f"Unexpected headless runtime state: {runtime_status.get('state')}",
        )
    if runtime_status.get("referenceIndexReady") is not True:
        raise BenchmarkError(
            "REFERENCE_INDEX_NOT_READY", "Headless reference indexing is not ready."
        )
    return descriptor, runtime_status


def bootstrap_headless(kast, kastctl, workspace, ready_timeout):
    started = time.perf_counter()
    process, public_up_ms = run_process([kast, "up"], ready_timeout, workspace)
    if process.returncode != 0:
        raise BenchmarkError(
            "HEADLESS_BOOTSTRAP_FAILED",
            process.stderr.strip() or process.stdout.strip() or "`kast up` failed.",
        )
    status, status_process, status_ms = run_json(
        [
            kastctl,
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace,
            "--backend",
            "headless",
        ],
        ready_timeout,
    )
    if status_process.returncode != 0:
        raise BenchmarkError(
            "HEADLESS_STATUS_FAILED", "Cannot inspect the demanded headless runtime."
        )
    descriptor, runtime_status = runtime_snapshot(status, workspace)
    return {
        "status": status,
        "descriptor": descriptor,
        "runtimeStatus": runtime_status,
        "publicUpMs": round(public_up_ms, 3),
        "statusMs": round(status_ms, 3),
        "readyMs": round((time.perf_counter() - started) * 1000.0, 3),
    }


def database_candidates(receipt, workspace):
    roots = receipt.get("roots") or {}
    data = roots.get("data")
    if not data:
        raise BenchmarkError("INSTALL_RECEIPT_INVALID", "Install receipt has no data root.")
    state_root = Path(data) / "workspaces"
    workspace = workspace.resolve()
    candidates = []
    for database in state_root.glob("**/cache/source-index.db"):
        metadata = database.parent.parent / "workspace.json"
        try:
            prepared_root = Path(load_json(metadata, "WORKSPACE_METADATA_INVALID")["workspaceRoot"])
        except (BenchmarkError, KeyError, TypeError):
            continue
        if prepared_root.is_absolute() and prepared_root.resolve() == workspace:
            candidates.append(database)
    return sorted(candidates)


def discover_database(explicit, receipt, workspace):
    if explicit:
        database = Path(explicit).expanduser().resolve()
        if not database.is_file():
            raise BenchmarkError(
                "DATABASE_MISSING", f"source-index.db does not exist: {database}"
            )
        return database
    candidates = database_candidates(receipt, workspace)
    if len(candidates) == 1:
        return candidates[0]
    raise BenchmarkError(
        "DATABASE_AMBIGUOUS",
        f"Found {len(candidates)} headless databases for {workspace}.",
        "Pass --database with the exact source-index.db path.",
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


def build_graph(kastctl, workspace, files, timeout, artifact):
    timings = []
    failures = collections.Counter()
    symbol_count = 0
    edge_count = 0
    final_generation = None
    with artifact.open("w") as output:
        for request_id, path in enumerate(files, start=1):
            relative = path.relative_to(workspace)
            response, process, elapsed_ms = run_json(
                [
                    kastctl,
                    "--output",
                    "json",
                    "agent",
                    "graph",
                    "--workspace-root",
                    workspace,
                    "--operation",
                    "refresh",
                    "--file-path",
                    relative,
                ],
                timeout,
                workspace,
            )
            timings.append(elapsed_ms)
            result = response.get("result") or {}
            ok = process.returncode == 0 and response.get("ok") is not False
            if ok:
                symbol_count += int(result.get("symbolCount", 0))
                edge_count += int(result.get("edgeOccurrenceCount", 0))
                final_generation = result.get("generation", final_generation)
            else:
                failures[response_error_code(response)] += 1
            output.write(
                json.dumps(
                    {
                        "file": str(relative),
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
                    f"native graph refresh: {request_id}/{len(files)} files",
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


def graph_command(kastctl, workspace, database, scope, operation, generation, seed):
    command = [
        kastctl,
        "--output",
        "json",
        "agent",
        "graph",
        "--workspace-root",
        workspace,
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


def traverse_graph(kastctl, workspace, database, iterations, timeout, output_dir):
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
                    kastctl, workspace, database, scope, operation, generation, seed
                )
                payload, process, elapsed_ms = run_json(command, timeout, workspace)
                timings.append(elapsed_ms)
                last_payload = payload
                stderr = process.stderr.strip()
                if process.returncode != 0 or payload.get("ok") is False:
                    status = "failed"
                    break
                if generation is None:
                    generation = (payload.get("result") or {}).get("generation")
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
    kast, kastctl = resolve_commands(args)
    receipt_path = kast.parent.parent / "receipt.json"
    receipt = load_json(receipt_path, "INSTALL_RECEIPT_INVALID")
    validate_headless_install(receipt)

    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    run_dir = Path(args.output_root).expanduser().resolve() / workspace.name / timestamp
    run_dir.mkdir(parents=True)
    started_at = datetime.datetime.now(datetime.timezone.utc)
    started = time.perf_counter()

    bootstrap = bootstrap_headless(kast, kastctl, workspace, args.ready_timeout)
    descriptor = bootstrap["descriptor"]
    if descriptor.get("backendVersion") != receipt.get("backendVersion"):
        raise BenchmarkError(
            "HEADLESS_VERSION_MISMATCH",
            "The live headless runtime and current Kast receipt versions do not match.",
            "Refresh Kast before benchmarking.",
        )

    database = discover_database(args.database, receipt, workspace)
    files = tracked_kotlin_files(workspace, args.source_root, args.limit)
    write_json(
        run_dir / "preflight.json",
        {
            "receipt": receipt,
            "status": bootstrap["status"],
            "timings": {
                "publicUpMs": bootstrap["publicUpMs"],
                "statusMs": bootstrap["statusMs"],
                "readyMs": bootstrap["readyMs"],
            },
        },
    )

    build = build_graph(
        kastctl,
        workspace,
        files,
        args.timeout,
        run_dir / "semantic-graph-build.jsonl",
    )
    generation, seeds, traversals = traverse_graph(
        kastctl,
        workspace,
        database,
        args.iterations,
        args.timeout,
        run_dir / "traversal",
    )
    finished_at = datetime.datetime.now(datetime.timezone.utc)
    partial = build["failedFiles"] > 0 or any(
        report["status"] == "failed" for report in traversals
    )
    summary = {
        "schemaVersion": 2,
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
            "publicUpMs": bootstrap["publicUpMs"],
            "statusMs": bootstrap["statusMs"],
            "readyMs": bootstrap["readyMs"],
            "runtimeInstanceId": descriptor.get("runtimeInstanceId"),
        },
        "graphBuild": build,
        "nativeGraph": {
            "generation": generation,
            "seeds": seeds,
            "traversals": traversals,
        },
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
            "runtimeInstanceId": descriptor.get("runtimeInstanceId"),
            "readyMs": bootstrap["readyMs"],
            "summary": summary_path,
        },
    )
    return 0


def self_test():
    good_receipt = {
        "components": ["cli", "headless-backend", "manifest"],
        "backends": [{"name": "headless"}],
    }
    validate_headless_install(good_receipt)
    try:
        validate_headless_install(
            {"components": ["cli", "manifest"], "backends": []}
        )
    except BenchmarkError as error:
        assert error.code == "HEADLESS_INSTALL_REQUIRED"
    else:
        raise AssertionError("install without a headless backend was accepted")

    assert timing_stats([5.0, 1.0, 3.0])["p50Ms"] == 3.0
    workspace = Path("/repo")
    ready = {
        "workspaceRoot": str(workspace),
        "selected": {
            "descriptor": {
                "workspaceRoot": str(workspace),
                "backendName": "headless",
                "socketPath": "/tmp/headless.sock",
            },
            "runtimeStatus": {
                "workspaceRoot": str(workspace),
                "state": "READY",
                "healthy": True,
                "referenceIndexReady": True,
            },
        },
    }
    descriptor, status = runtime_snapshot(ready, workspace)
    assert descriptor["backendName"] == "headless"
    assert status["referenceIndexReady"] is True

    command = graph_command(
        Path("/kastctl"), workspace, Path("/index.db"), "symbol", "summary", 7, None
    )
    assert command[-2:] == ["--generation", "7"]
    assert "idea" not in command

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        database = (
            root
            / "data"
            / "workspaces"
            / "git"
            / "local"
            / "repo--abc"
            / "cache"
            / "source-index.db"
        )
        database.parent.mkdir(parents=True)
        database.touch()
        receipt = {"roots": {"data": str(root / "data")}}
        metadata = database.parent.parent / "workspace.json"
        metadata.write_text(json.dumps({"workspaceRoot": str(workspace)}))
        assert database_candidates(receipt, workspace) == [database]
        assert discover_database(None, receipt, workspace) == database

    emit_result("selfTest", {"ok": True, "checks": 7})
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
