#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Run:
    eval_id: str
    eval_dir: Path
    run_dir: Path
    instructions_path: Path
    chain_id: str | None
    configuration: str
    run_number: int | None


@dataclass(frozen=True)
class DispatchOptions:
    command_template: str
    concurrency: int = 4
    max_retries: int = 1


@dataclass(frozen=True)
class RunResult:
    run: Run
    succeeded: bool
    attempts: int
    duration_seconds: float
    message: str


@dataclass(frozen=True)
class DispatchSummary:
    succeeded: int
    failed: int
    retried: int


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def discover_runs(iteration_dir: Path) -> list[Run]:
    manifest_path = iteration_dir / "manifest.json"
    if manifest_path.exists():
        return discover_runs_from_manifest(iteration_dir, manifest_path)
    return discover_runs_from_filesystem(iteration_dir)


def discover_runs_from_manifest(iteration_dir: Path, manifest_path: Path) -> list[Run]:
    manifest = load_json_object(manifest_path)
    evals = manifest.get("evals")
    if not isinstance(evals, dict):
        raise ValueError(f"{manifest_path} must contain an 'evals' object.")

    runs: list[Run] = []
    for eval_id, entry in evals.items():
        if not isinstance(entry, dict):
            raise ValueError(f"{manifest_path}: eval '{eval_id}' must map to an object.")
        directory = entry.get("dir")
        if not isinstance(directory, str) or not directory:
            raise ValueError(f"{manifest_path}: eval '{eval_id}' must declare a non-empty dir.")
        chain_id = entry.get("chain_id")
        if chain_id is not None and not isinstance(chain_id, str):
            raise ValueError(f"{manifest_path}: eval '{eval_id}' chain_id must be a string or null.")
        eval_dir = iteration_dir / directory
        instructions = sorted(eval_dir.glob("*/run-*/run_instructions.md"))
        for instructions_path in instructions:
            runs.append(build_run(eval_id, eval_dir, instructions_path, chain_id))
    return runs


def discover_runs_from_filesystem(iteration_dir: Path) -> list[Run]:
    runs: list[Run] = []
    for instructions_path in sorted(iteration_dir.glob("eval-*/*/run-*/run_instructions.md")):
        eval_dir = instructions_path.parents[2]
        metadata_path = eval_dir / "eval_metadata.json"
        metadata = load_json_object(metadata_path) if metadata_path.exists() else {}
        eval_id = str(metadata.get("eval_id") or eval_dir.name.removeprefix("eval-"))
        chain_id = metadata.get("chain_id")
        if chain_id is not None and not isinstance(chain_id, str):
            raise ValueError(f"{metadata_path}: chain_id must be a string or null.")
        runs.append(build_run(eval_id, eval_dir, instructions_path, chain_id))
    return runs


def build_run(eval_id: str, eval_dir: Path, instructions_path: Path, chain_id: str | None) -> Run:
    run_dir = instructions_path.parent
    configuration = run_dir.parent.name
    run_number: int | None = None
    if run_dir.name.startswith("run-"):
        try:
            run_number = int(run_dir.name.split("-", 1)[1])
        except ValueError:
            run_number = None
    return Run(
        eval_id=eval_id,
        eval_dir=eval_dir,
        run_dir=run_dir,
        instructions_path=instructions_path,
        chain_id=chain_id,
        configuration=configuration,
        run_number=run_number,
    )


def group_runs(runs: list[Run]) -> list[list[Run]]:
    chains: dict[str, list[Run]] = {}
    groups: list[list[Run]] = []
    for run in runs:
        if run.chain_id:
            chains.setdefault(run.chain_id, []).append(run)
        else:
            groups.append([run])
    groups.extend(chains.values())
    return groups


def shell_value(value: str | int | None) -> str:
    return shlex.quote("" if value is None else str(value))


def render_command(command_template: str, run: Run, attempt: int) -> str:
    transcript_path = run.run_dir / "outputs" / "transcript.md"
    values = {
        "iteration_dir": shell_value(run.eval_dir.parent),
        "eval_dir": shell_value(run.eval_dir),
        "eval_id": shell_value(run.eval_id),
        "run_dir": shell_value(run.run_dir),
        "instructions": shell_value(run.instructions_path),
        "transcript": shell_value(transcript_path),
        "configuration": shell_value(run.configuration),
        "run_number": shell_value(run.run_number),
        "chain_id": shell_value(run.chain_id),
        "attempt": shell_value(attempt),
    }
    command = command_template
    for key, value in values.items():
        command = command.replace(f"{{{key}}}", value)
    unknown = sorted(set(re.findall(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", command)) - set(values))
    if unknown:
        raise ValueError(f"Unknown command template placeholder: {unknown[0]}")
    return command


def iso_from_epoch(epoch_seconds: float) -> str:
    return datetime.fromtimestamp(epoch_seconds, tz=UTC).isoformat().replace("+00:00", "Z")


def transcript_is_non_empty(run: Run) -> bool:
    transcript_path = run.run_dir / "outputs" / "transcript.md"
    try:
        return transcript_path.stat().st_size > 0
    except OSError:
        return False


def read_existing_timing(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def write_timing(run: Run, *, start: float, end: float, attempts: int, status: str, exit_code: int | None, message: str) -> None:
    timing_path = run.run_dir / "timing.json"
    existing = read_existing_timing(timing_path)
    executor_duration = max(0.0, end - start)
    grader_duration = float(existing.get("grader_duration_seconds", 0.0) or 0.0)
    payload = {
        **existing,
        "status": status,
        "start_ts": iso_from_epoch(start),
        "end_ts": iso_from_epoch(end),
        "executor_duration_seconds": executor_duration,
        "grader_duration_seconds": grader_duration,
        "total_duration_seconds": executor_duration + grader_duration,
        "total_tokens": existing.get("total_tokens", 0),
        "attempts": attempts,
        "retries": max(0, attempts - 1),
        "last_exit_code": exit_code,
        "message": message,
    }
    timing_path.write_text(json.dumps(payload, indent=2) + "\n")


PARSE_TOOL_CALLS_SCRIPT = Path(__file__).resolve().parent / "parse_tool_calls.py"


def _parse_tool_calls(run: Run) -> None:
    """Run the deterministic transcript parser. Failures are logged but
    non-fatal — we never want a parser bug to mark a real run as failed."""
    if not PARSE_TOOL_CALLS_SCRIPT.exists():
        return
    try:
        subprocess.run(
            [sys.executable, str(PARSE_TOOL_CALLS_SCRIPT), "--run-dir", str(run.run_dir)],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return


def execute_run(run: Run, options: DispatchOptions) -> RunResult:
    start = time.time()
    last_exit_code: int | None = None
    message = ""
    attempts = 0
    for attempt in range(1, options.max_retries + 2):
        attempts = attempt
        command = render_command(options.command_template, run, attempt)
        completed = subprocess.run(
            command,
            cwd=run.run_dir,
            shell=True,
            text=True,
            capture_output=True,
            check=False,
        )
        last_exit_code = completed.returncode
        if completed.returncode == 0 and transcript_is_non_empty(run):
            end = time.time()
            write_timing(
                run,
                start=start,
                end=end,
                attempts=attempts,
                status="succeeded",
                exit_code=last_exit_code,
                message="completed",
            )
            _parse_tool_calls(run)
            return RunResult(run, True, attempts, end - start, "completed")

        transcript_status = "non-empty" if transcript_is_non_empty(run) else "missing-or-empty"
        stderr = completed.stderr.strip()
        message = f"exit={completed.returncode}; transcript={transcript_status}"
        if stderr:
            message = f"{message}; stderr={stderr[-500:]}"

    end = time.time()
    write_timing(
        run,
        start=start,
        end=end,
        attempts=attempts,
        status="failed",
        exit_code=last_exit_code,
        message=message,
    )
    return RunResult(run, False, attempts, end - start, message)


def execute_group(group: list[Run], options: DispatchOptions, emit: "StatusEmitter") -> list[RunResult]:
    results = []
    for run in group:
        result = execute_run(run, options)
        emit(result)
        results.append(result)
    return results


class StatusEmitter:
    def __init__(self) -> None:
        self._lock = threading.Lock()

    def __call__(self, result: RunResult) -> None:
        status = "succeeded" if result.succeeded else "failed"
        retries = result.attempts - 1
        run_label = f"{result.run.eval_id}/{result.run.configuration}/{result.run.run_dir.name}"
        with self._lock:
            print(
                f"{status}: {run_label} attempts={result.attempts} retries={retries} "
                f"duration={result.duration_seconds:.3f}s",
                flush=True,
            )


def dispatch_iteration(iteration_dir: Path, options: DispatchOptions) -> DispatchSummary:
    if options.concurrency < 1:
        raise ValueError("concurrency must be at least 1.")
    if options.max_retries < 0:
        raise ValueError("max_retries must be non-negative.")
    if not options.command_template.strip():
        raise ValueError("A dispatch command is required via --command-template or VALUE_PROOF_RUN_COMMAND.")
    if not iteration_dir.exists():
        raise ValueError(f"Iteration directory does not exist: {iteration_dir}")

    runs = discover_runs(iteration_dir)
    if not runs:
        raise ValueError(f"No run_instructions.md files found under {iteration_dir}")

    emit = StatusEmitter()
    all_results: list[RunResult] = []
    groups = group_runs(runs)
    with ThreadPoolExecutor(max_workers=options.concurrency) as executor:
        futures = [executor.submit(execute_group, group, options, emit) for group in groups]
        for future in as_completed(futures):
            all_results.extend(future.result())

    succeeded = sum(1 for result in all_results if result.succeeded)
    failed = len(all_results) - succeeded
    retried = sum(max(0, result.attempts - 1) for result in all_results)
    return DispatchSummary(succeeded=succeeded, failed=failed, retried=retried)


def main() -> int:
    parser = argparse.ArgumentParser(description="Dispatch value-proof runs from a scaffolded iteration directory.")
    parser.add_argument("iteration_dir", type=Path, help="Iteration directory created by run_value_proof.py")
    parser.add_argument("--concurrency", type=int, default=4, help="Parallel worker count; default: 4")
    parser.add_argument("--max-retries", type=int, default=1, help="Retries for failed or empty transcript runs; default: 1")
    parser.add_argument(
        "--command-template",
        default=os.environ.get("VALUE_PROOF_RUN_COMMAND", ""),
        help=(
            "Shell command template used to execute one run. Placeholders are shell-quoted: "
            "{run_dir}, {instructions}, {transcript}, {eval_id}, {configuration}, {run_number}, {chain_id}, {attempt}."
        ),
    )
    args = parser.parse_args()

    try:
        summary = dispatch_iteration(
            args.iteration_dir,
            DispatchOptions(
                command_template=args.command_template,
                concurrency=args.concurrency,
                max_retries=args.max_retries,
            ),
        )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    print(f"Summary: {summary.succeeded} succeeded, {summary.failed} failed, {summary.retried} retried")
    return 1 if summary.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
