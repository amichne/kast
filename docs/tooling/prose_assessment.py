"""Run existing prose and contract checks with a publication-bound receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal, TypeGuard

from publication import Failure, InputDigest, State, snapshot

ROOT = Path(__file__).resolve().parents[2]
type CheckState = Literal[State.PASSED, State.FAILED]


@dataclass(frozen=True, slots=True)
class Command:
    arguments: tuple[str, ...]
    directory: str = "."


@dataclass(frozen=True, slots=True)
class Step:
    name: str
    commands: tuple[Command, ...]


@dataclass(frozen=True, slots=True)
class ProcessResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True, slots=True)
class Finding:
    stage: str
    path: str
    sourceSha256: str
    rule: str
    line: int
    message: str
    severity: str


@dataclass(frozen=True, slots=True)
class Disposition:
    finding: Finding
    reason: str


@dataclass(frozen=True, slots=True)
class Check:
    name: str
    status: CheckState
    evidence: tuple[InputDigest, ...]
    reason: str


@dataclass(frozen=True, slots=True)
class Receipt:
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    checks: tuple[Check, ...]
    limits: tuple[str, ...]
    status: Literal["completed", "failed"]
    kind: Literal["KAST_DOCUMENTATION_PROSE"] = "KAST_DOCUMENTATION_PROSE"
    schemaVersion: Literal[1] = 1


@dataclass(frozen=True, slots=True)
class CommandLog:
    command: Command
    result: ProcessResult | Failure


@dataclass(frozen=True, slots=True)
class FindingsReport:
    findings: tuple[Finding, ...]
    schemaVersion: Literal[1] = 1


@dataclass(frozen=True, slots=True)
class Summary:
    status: Literal["FAILED", "completed", "failed"]
    report: str


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return isinstance(value, dict)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def findings(value: object, root: Path, stage: str) -> tuple[Finding, ...] | Failure:
    if not _record(value):
        return Failure("prose-output", "Vale did not return a file-to-alert object")
    result: list[Finding] = []
    for path, alerts in value.items():
        if not _objects(alerts):
            return Failure("prose-output", "Vale returned an invalid alert array")
        source = root / path
        try:
            if not source.resolve().is_relative_to(root.resolve()):
                return Failure("prose-output", "Vale reported an external source")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
        except OSError:
            return Failure("prose-output", "Vale reported a missing source: " + path)
        for alert in alerts:
            if not _record(alert):
                return Failure("prose-output", "Vale returned an invalid alert")
            rule, line, message, severity = (
                alert.get("Check"),
                alert.get("Line"),
                alert.get("Message"),
                alert.get("Severity"),
            )
            if (
                not isinstance(rule, str)
                or type(line) is not int
                or not isinstance(message, str)
                or not isinstance(severity, str)
                or severity not in {"error", "warning", "suggestion"}
            ):
                return Failure("prose-output", "Vale returned incomplete alert identity")
            result.append(Finding(stage, path, digest, rule, line, message, severity))
    return tuple(result)


def parse_dispositions(value: object) -> tuple[Disposition, ...] | Failure:
    if (
        not _record(value)
        or set(value) != {"schemaVersion", "dispositions"}
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or not _objects(value["dispositions"])
    ):
        return Failure("prose-dispositions", "The disposition document is invalid")
    result: list[Disposition] = []
    fields = {"stage", "path", "sourceSha256", "rule", "line", "message", "severity"}
    for item in value["dispositions"]:
        if (
            not _record(item)
            or set(item) != {"finding", "reason"}
            or not _record(item["finding"])
            or set(item["finding"]) != fields
        ):
            return Failure("prose-dispositions", "A disposition lacks its exact finding identity")
        raw, reason = item["finding"], item["reason"]
        if not isinstance(reason, str) or not reason.strip():
            return Failure("prose-dispositions", "A disposition needs a review reason")
        stage, path, digest, rule, line, message, severity = (
            raw[key]
            for key in ("stage", "path", "sourceSha256", "rule", "line", "message", "severity")
        )
        if (
            not isinstance(stage, str)
            or not isinstance(path, str)
            or not isinstance(digest, str)
            or not isinstance(rule, str)
            or type(line) is not int
            or not isinstance(message, str)
            or not isinstance(severity, str)
            or severity not in {"warning", "suggestion"}
        ):
            return Failure(
                "prose-dispositions", "A disposition cannot accept errors or unknown findings"
            )
        result.append(
            Disposition(Finding(stage, path, digest, rule, line, message, severity), reason)
        )
    if len({item.finding for item in result}) != len(result):
        return Failure("prose-dispositions", "Duplicate advisory dispositions")
    return tuple(result)


def assess_findings(
    alerts: tuple[Finding, ...], dispositions: tuple[Disposition, ...]
) -> tuple[CheckState, str]:
    errors = [item for item in alerts if item.severity == "error"]
    admitted = {item.finding for item in dispositions}
    unresolved = [item for item in alerts if item.severity != "error" and item not in admitted]
    if errors:
        return State.FAILED, f"{len(errors)} blocking prose errors remain"
    if unresolved:
        return State.FAILED, f"{len(unresolved)} advisories need source-bound review dispositions"
    return State.PASSED, f"{len(alerts)} advisories remain with explicit review reasons"


def assess_review_identity(path: str, frozen: bytes, current: bytes) -> Check:
    reference = InputDigest(path, hashlib.sha256(frozen).hexdigest())
    if frozen != current:
        return Check(
            "review-identity",
            State.FAILED,
            (reference,),
            "Advisory dispositions changed during assessment",
        )
    return Check(
        "review-identity",
        State.PASSED,
        (reference,),
        "The assessed advisory dispositions remain unchanged",
    )


def plan(
    root: Path, output: Path, vale: str, reader_vale: str, mint: str, paths: tuple[str, ...]
) -> tuple[Step, ...]:
    uv = ("uv", "run", "--locked", "--project", "docs/tooling", "python")
    return (
        Step(
            "vale", (Command((vale, "--no-global", "--config=.vale.ini", "--output=JSON", *paths)),)
        ),
        Step(
            "terminology",
            (
                Command(
                    (
                        *uv,
                        "docs/tooling/check_glossary.py",
                        "docs/glossary.json",
                        "README.md",
                        "docs",
                    )
                ),
            ),
        ),
        Step(
            "examples",
            (
                Command(
                    (
                        str(root / "gradlew"),
                        ":app-server:test",
                        "--rerun",
                        "--tests",
                        "io.github.amichne.kast.appserver.query.PublicToolContractTest",
                        "--tests",
                        "io.github.amichne.kast.appserver.query.PublicToolRecipeTest",
                        "--console=plain",
                    )
                ),
                Command((sys.executable, "packaging/test-installer-entrypoint.py")),
            ),
        ),
        Step(
            "reader-audit",
            (
                Command(
                    (
                        *uv,
                        "config/vale/readability/audit.py",
                        "--vale-bin",
                        reader_vale,
                        "--corpus",
                        "docs/reviews/readability-audit/corpus.txt",
                        "--output",
                        str(output / "reader"),
                    )
                ),
            ),
        ),
        Step("mint-validate", (Command((mint, "validate"), "docs/public"),)),
        Step("broken-links", (Command((mint, "broken-links"), "docs/public"),)),
        Step(
            "callable-parity",
            (
                Command(
                    (
                        str(root / "gradlew"),
                        ":cli:verifyMintlifyCallableReference",
                        "--console=plain",
                    )
                ),
            ),
        ),
        Step(
            "glossary-parity",
            (
                Command(
                    (
                        *uv,
                        "docs/tooling/check_glossary.py",
                        "docs/glossary.json",
                        "--check-render",
                        "docs/GLOSSARY.md",
                        "--check-public-render",
                        "docs/public/glossary.mdx",
                        "--check-vale",
                        "config/vale/styles/Kast",
                    )
                ),
            ),
        ),
    )


def run_command(root: Path, command: Command) -> ProcessResult | Failure:
    executable = shutil.which(command.arguments[0])
    if executable is None:
        return Failure("process", "Missing executable: " + command.arguments[0])
    environment = {key: value for key, value in os.environ.items() if not key.startswith("VALE_")}
    try:
        result = subprocess.run(
            (executable, *command.arguments[1:]),
            cwd=root / command.directory,
            env=environment,
            capture_output=True,
            text=True,
            timeout=1800,
            check=False,
        )
    except (OSError, UnicodeError, subprocess.TimeoutExpired) as error:
        return Failure("process", type(error).__name__ + " at " + command.arguments[0])
    return ProcessResult(result.returncode, result.stdout, result.stderr)


def _load(path: Path) -> object | Failure:
    try:
        result: object = json.loads(path.read_text())
        return result
    except (OSError, UnicodeError, ValueError):
        return Failure("input", "Cannot read JSON: " + str(path))


def run(
    root: Path, output: Path, vale: str, reader_vale: str, mint: str, dispositions_path: Path
) -> Receipt | Failure:
    if not output.resolve().is_relative_to((root / "build").resolve()):
        return Failure("output", "Prose evidence must reside under the repository build directory")
    if not dispositions_path.resolve().is_relative_to(root.resolve()):
        return Failure("input", "Advisory dispositions must reside in the repository")
    before = snapshot(root)
    if isinstance(before, Failure):
        return before
    try:
        frozen_dispositions = dispositions_path.read_bytes()
        parsed: object = json.loads(frozen_dispositions)
    except (OSError, UnicodeError, ValueError):
        return Failure("prose-dispositions", "Cannot read advisory dispositions")
    dispositions = parse_dispositions(parsed)
    if isinstance(dispositions, Failure):
        return dispositions
    for executable, expected in (
        (vale, "vale version 3.24.0"),
        (reader_vale, "vale version 3.25.1"),
    ):
        version = run_command(root, Command((executable, "--version")))
        if (
            isinstance(version, Failure)
            or version.returncode != 0
            or version.stdout.strip() != expected
        ):
            return Failure("engine", "Expected " + expected + " from " + executable)
    output.mkdir(parents=True, exist_ok=True)
    reader_output = output / "reader"
    if reader_output.exists():
        shutil.rmtree(reader_output)
    paths = tuple(
        item.path
        for item in before.inputs
        if item.path.startswith(("docs/public/", "openwiki/"))
        and Path(item.path).suffix in {".md", ".mdx"}
        and Path(item.path).name != "AGENTS.md"
    )
    if not paths:
        return Failure("input", "The prose scope is empty")
    checks: list[Check] = []
    all_findings: list[Finding] = []

    def evidence(path: Path) -> InputDigest:
        return InputDigest(
            path.relative_to(root).as_posix(), hashlib.sha256(path.read_bytes()).hexdigest()
        )

    for step in plan(root, output, vale, reader_vale, mint, paths):
        refs: list[InputDigest] = []
        state: CheckState = State.PASSED
        reason = "The named existing procedure passed"
        for index, command in enumerate(step.commands):
            result = run_command(root, command)
            log = output / f"{step.name}-{index}.json"
            log.write_text(json.dumps(asdict(CommandLog(command, result)), indent=2) + "\n")
            refs.append(evidence(log))
            if isinstance(result, Failure):
                state, reason = State.FAILED, result.detail
                break
            if step.name == "vale":
                try:
                    raw: object = json.loads(result.stdout)
                except ValueError:
                    raw = None
                alerts = findings(raw, root, "root-vale-3.24.0")
                if isinstance(alerts, Failure):
                    state, reason = State.FAILED, alerts.detail
                else:
                    all_findings.extend(alerts)
                    state, reason = assess_findings(alerts, dispositions)
                if result.returncode not in {0, 1}:
                    state, reason = State.FAILED, "The root Vale process failed"
            elif step.name == "reader-audit":
                for profile in ("prose", "terminology"):
                    path = reader_output / f"{profile}.json"
                    alerts = findings(_load(path), root, "reader-" + profile + "-3.25.1")
                    if isinstance(alerts, Failure):
                        state, reason = State.FAILED, alerts.detail
                        continue
                    refs.append(evidence(path))
                    all_findings.extend(alerts)
                    profile_state, profile_reason = assess_findings(alerts, dispositions)
                    if profile_state != State.PASSED:
                        state, reason = profile_state, profile_reason
                audit_run = reader_output / "run.json"
                if audit_run.is_file():
                    refs.append(evidence(audit_run))
                    refs.append(evidence(root / "docs/reviews/readability-audit/corpus.txt"))
                if result.returncode not in {0, 1}:
                    state, reason = State.FAILED, "The frozen reader audit failed"
            elif result.returncode != 0:
                state, reason = State.FAILED, "The procedure exited " + str(result.returncode)
        checks.append(Check(step.name, state, tuple(refs), reason))
    pending = output / "findings.json"
    pending.write_text(json.dumps(asdict(FindingsReport(tuple(all_findings))), indent=2) + "\n")
    after = snapshot(root)
    if isinstance(after, Failure) or before != after:
        checks.append(
            Check(
                "candidate-identity",
                State.FAILED,
                (evidence(pending),),
                "Candidate inputs changed during assessment",
            )
        )
    disposition_ref = InputDigest(
        dispositions_path.relative_to(root).as_posix(),
        hashlib.sha256(frozen_dispositions).hexdigest(),
    )
    checks.append(
        assess_review_identity(
            disposition_ref.path, frozen_dispositions, dispositions_path.read_bytes()
        )
    )
    checks.append(
        Check(
            "advisory-dispositions",
            State.PASSED,
            (disposition_ref, evidence(pending)),
            "Each accepted advisory keeps its exact source and review reason",
        )
    )
    return Receipt(
        before.sourceRevision,
        before.inputs,
        tuple(checks),
        (
            "Prose metrics do not establish reader comprehension, native task execution, or current semantic truth.",
            "The frozen reader audit keeps Vale 3.25.1 and separate prose and terminology stages. Root policy keeps Vale 3.24.0.",
            "Historical request captures remain revision-scoped and do not establish current request admission.",
        ),
        "completed" if all(check.status == State.PASSED for check in checks) else "failed",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--root", type=Path, default=ROOT)
    _ = parser.add_argument(
        "--output", type=Path, default=Path("build/reports/documentation/prose")
    )
    _ = parser.add_argument("--vale-bin", required=True)
    _ = parser.add_argument("--reader-vale-bin", required=True)
    _ = parser.add_argument(
        "--mint-bin", type=Path, default=Path(".github/tools/mintlify/node_modules/.bin/mint")
    )
    _ = parser.add_argument("--dispositions", type=Path, required=True)
    _ = parser.add_argument(
        "--report", type=Path, default=Path("build/reports/documentation/prose-receipt.json")
    )
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        result = run(
            root,
            (root / args.output).resolve(),
            args.vale_bin,
            args.reader_vale_bin,
            str((root / args.mint_bin).resolve()),
            (root / args.dispositions).resolve(),
        )
    except (OSError, UnicodeError) as error:
        result = Failure("evidence-write", type(error).__name__)
    report = root / args.report
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(asdict(result), indent=2) + "\n")
    print(
        json.dumps(
            asdict(
                Summary(result.type if isinstance(result, Failure) else result.status, str(report))
            )
        )
    )
    return 2 if isinstance(result, Failure) else 0 if result.status == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
