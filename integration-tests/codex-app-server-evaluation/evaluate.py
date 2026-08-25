#!/usr/bin/env python3
"""Run one fail-closed Codex App Server evaluation and retain its evidence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any


SCHEMA_VERSION = 1
MODES = frozenset({"dynamic-only", "comparison"})
REQUIRED_REQUEST_FIELDS = frozenset(
    {
        "schemaVersion",
        "mode",
        "workspaceRoot",
        "symbolQuery",
        "expectedCallerNames",
    }
)
ALLOWED_REQUEST_FIELDS = REQUIRED_REQUEST_FIELDS | {"model"}


class EvaluationFailure(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message

    def document(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


@dataclass(frozen=True)
class EvaluationRequest:
    mode: str
    workspace_root: Path
    symbol_query: str
    expected_caller_names: tuple[str, ...]
    model: str | None

    def document(self) -> dict[str, Any]:
        document: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "mode": self.mode,
            "workspaceRoot": str(self.workspace_root),
            "symbolQuery": self.symbol_query,
            "expectedCallerNames": list(self.expected_caller_names),
        }
        if self.model is not None:
            document["model"] = self.model
        return document


@dataclass(frozen=True)
class PlannedCommand:
    name: str
    argv: tuple[str, ...]
    working_directory: Path
    retain_output: bool = True

    def document(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "argv": list(self.argv),
            "workingDirectory": str(self.working_directory),
            "outputRetained": self.retain_output,
        }


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the pre-production Kast dynamic-tools enterprise evaluation."
    )
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--allow-full-access-comparison", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=1200)
    return parser.parse_args()


def fail(code: str, message: str) -> None:
    raise EvaluationFailure(code, message)


def text(document: dict[str, Any], name: str) -> str:
    value = document.get(name)
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > 256
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        fail("request-invalid", f"{name} must be nonblank printable text of at most 256 characters")
    return value


def load_request(path: Path) -> EvaluationRequest:
    try:
        document = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        fail("request-invalid", f"cannot read request: {error}")
    if not isinstance(document, dict):
        fail("request-invalid", "request must be one JSON object")
    fields = frozenset(document)
    missing = sorted(REQUIRED_REQUEST_FIELDS - fields)
    unknown = sorted(fields - ALLOWED_REQUEST_FIELDS)
    if missing or unknown:
        fail("request-invalid", f"request fields rejected; missing={missing}, unknown={unknown}")
    version = document.get("schemaVersion")
    if not isinstance(version, int) or isinstance(version, bool) or version != SCHEMA_VERSION:
        fail("request-invalid", f"schemaVersion must equal {SCHEMA_VERSION}")
    mode = document.get("mode")
    if mode not in MODES:
        fail("request-invalid", f"mode must be one of {sorted(MODES)}")
    workspace_value = document.get("workspaceRoot")
    if not isinstance(workspace_value, str) or not Path(workspace_value).is_absolute():
        fail("request-invalid", "workspaceRoot must be an absolute path")
    try:
        workspace = Path(workspace_value).resolve(strict=True)
    except OSError as error:
        fail("request-invalid", f"workspaceRoot is unavailable: {error}")
    if not workspace.is_dir():
        fail("request-invalid", "workspaceRoot must be a directory")
    callers = document.get("expectedCallerNames")
    if not isinstance(callers, list) or not callers:
        fail("request-invalid", "expectedCallerNames must be a nonempty array")
    if any(not isinstance(caller, str) for caller in callers):
        fail("request-invalid", "expectedCallerNames must contain only strings")
    expected = tuple(text({"caller": caller}, "caller") for caller in callers)
    if len(set(expected)) != len(expected):
        fail("request-invalid", "expectedCallerNames must be distinct")
    model_value = document.get("model")
    model = None if model_value is None else text({"model": model_value}, "model")
    return EvaluationRequest(
        mode=mode,
        workspace_root=workspace,
        symbol_query=text(document, "symbolQuery"),
        expected_caller_names=expected,
        model=model,
    )


def command_plan(
    repository: Path,
    request: EvaluationRequest,
    output: Path,
    skip_install: bool,
) -> tuple[PlannedCommand, ...]:
    gradlew = str(repository / "gradlew")
    commands: list[PlannedCommand] = [
        PlannedCommand("source-commit", ("git", "rev-parse", "HEAD"), repository),
        PlannedCommand("source-status", ("git", "status", "--porcelain"), repository),
    ]
    if not skip_install:
        commands.append(PlannedCommand("install-local", (gradlew, "installLocal"), repository))
    commands.extend(
        [
            PlannedCommand("codex-version", ("codex", "--version"), request.workspace_root),
            PlannedCommand(
                "codex-login-status",
                ("codex", "login", "status"),
                request.workspace_root,
                retain_output=False,
            ),
            PlannedCommand("kast-version", ("kast", "--version"), request.workspace_root),
            PlannedCommand("kast-start", ("kast", "start"), request.workspace_root),
            PlannedCommand(
                "app-server-evaluation",
                (
                    gradlew,
                    ":cli:codexAppServerEvaluation",
                    f"-PkastCodexEvaluationRequest={output / 'request.json'}",
                    f"-PkastCodexEvaluationEvidence={output / 'evidence.json'}",
                ),
                repository,
            ),
        ]
    )
    return tuple(commands)


def plan_document(
    request: EvaluationRequest,
    output: Path,
    commands: tuple[PlannedCommand, ...],
    allow_comparison: bool,
    skip_install: bool,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "request": request.document(),
        "outputDirectory": str(output),
        "fullAccessComparisonAuthorized": allow_comparison,
        "skipInstall": skip_install,
        "commands": [command.document() for command in commands],
    }


def write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(document, output, indent=2, sort_keys=True)
            output.write("\n")
        os.replace(temporary_name, path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def run_command(
    command: PlannedCommand,
    output: Path,
    timeout_seconds: int,
) -> subprocess.CompletedProcess[str]:
    try:
        completed = subprocess.run(
            command.argv,
            cwd=command.working_directory,
            text=True,
            capture_output=True,
            timeout=timeout_seconds,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        fail("command-unavailable", f"{command.name} could not complete: {error}")
    log = output / f"{command.name}.log"
    retained_stdout = completed.stdout if command.retain_output else "[redacted]\n"
    retained_stderr = completed.stderr if command.retain_output else "[redacted]\n"
    log.write_text(
        f"working-directory: {command.working_directory}\n"
        f"argv: {json.dumps(command.argv)}\n"
        f"exit-code: {completed.returncode}\n"
        "--- stdout ---\n"
        f"{retained_stdout}"
        "--- stderr ---\n"
        f"{retained_stderr}"
    )
    if completed.returncode != 0:
        fail("command-failed", f"{command.name} exited {completed.returncode}; inspect {log}")
    return completed


def evaluate(args: argparse.Namespace) -> dict[str, Any]:
    repository = Path(__file__).resolve().parents[2]
    request = load_request(args.request.resolve())
    output = args.output_directory.expanduser().absolute()
    if request.mode == "comparison" and not args.allow_full_access_comparison:
        fail(
            "full-access-comparison-not-authorized",
            "comparison mode requires --allow-full-access-comparison",
        )
    if args.timeout_seconds < 1:
        fail("request-invalid", "timeout-seconds must be positive")
    commands = command_plan(repository, request, output, args.skip_install)
    plan = plan_document(
        request,
        output,
        commands,
        args.allow_full_access_comparison,
        args.skip_install,
    )
    if args.plan_only:
        return plan
    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            fail("output-exists", f"output directory is not empty: {output}")
    else:
        output.mkdir(parents=True)
    write_json(output / "request.json", request.document())
    results: dict[str, subprocess.CompletedProcess[str]] = {}
    try:
        for command in commands:
            results[command.name] = run_command(command, output, args.timeout_seconds)
        evidence = json.loads((output / "evidence.json").read_text())
        if evidence.get("mode") != request.mode or evidence.get("decision") != "go":
            fail("evaluation-no-go", "App Server evaluation did not produce a go decision")
        environment = {
            "schemaVersion": SCHEMA_VERSION,
            "recordedAt": datetime.now(timezone.utc).isoformat(),
            "sourceCommit": results["source-commit"].stdout.strip(),
            "sourceDirty": bool(results["source-status"].stdout.strip()),
            "codexVersion": results["codex-version"].stdout.strip(),
            "codexLoginConfigured": True,
            "kastVersion": results["kast-version"].stdout.strip(),
            "workspaceRoot": str(request.workspace_root),
            "mode": request.mode,
        }
        write_json(output / "environment.json", environment)
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "status": "passed",
            "decision": evidence["decision"],
            "mode": request.mode,
            "evidence": str(output / "evidence.json"),
        }
        write_json(output / "result.json", result)
        return result
    except (EvaluationFailure, OSError, json.JSONDecodeError) as error:
        failure = error if isinstance(error, EvaluationFailure) else EvaluationFailure(
            "evidence-invalid", str(error)
        )
        write_json(
            output / "result.json",
            {
                "schemaVersion": SCHEMA_VERSION,
                "status": "failed",
                **failure.document(),
            },
        )
        raise failure


def main() -> int:
    try:
        result = evaluate(arguments())
    except EvaluationFailure as error:
        print(json.dumps(error.document(), sort_keys=True), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
