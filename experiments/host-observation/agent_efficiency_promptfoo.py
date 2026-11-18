"""Provide one admitted efficiency trial per Promptfoo evaluation row."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Literal

from pydantic import Field, JsonValue, TypeAdapter

from agent_efficiency import (
    Record,
    SessionReport,
    TrialRun,
    admit_trial_run,
    digest,
    record_trial_run,
    report_trials,
    run_trial,
    save,
)
from agent_efficiency_metrics import Arm, InvalidInput, Trial, parse_trial
from agent_efficiency_optimize import Candidate, OptimizationPlan, comparable, report_optimization


class ComparisonPlan(Record):
    benchmark: str
    configuration: str
    output: str
    tasks: tuple[str, ...] = ()
    repeats: int = Field(default=1, ge=1)
    holdout: bool = False


class Cohort(ComparisonPlan):
    name: str


class Row(Record):
    caseId: str
    cohort: str
    taskId: str
    arm: Arm
    repeat: int = Field(ge=0)


class Request(Record):
    action: Literal["cases", "prepare", "trial", "finish"]
    plan: Path
    row: Row | None = None


class Usage(Record):
    total: int


class TrialMetadata(Record):
    model: str
    effort: str
    wallSeconds: float
    usageQualification: str
    toolCalls: int | None = None


class TrialResult(Record):
    status: Literal["PASSED", "FAILED", "BLOCKED"]
    reason: str
    metadata: TrialMetadata
    tokenUsage: Usage | None = None


class IncompleteCohort(Record):
    type: Literal["MISSING_PROMPTFOO_TRIAL_ROWS"] = "MISSING_PROMPTFOO_TRIAL_ROWS"
    missing: tuple[Row, ...]


def cohorts(plan: ComparisonPlan | OptimizationPlan) -> tuple[Cohort, ...]:
    if isinstance(plan, ComparisonPlan):
        return (Cohort(name="comparison", **plan.model_dump()),)
    for candidate in plan.candidates:
        comparable(plan.champion, candidate)
    candidates = (plan.champion, *plan.candidates)
    return tuple(
        candidate_cohort(plan, "champion" if index == 0 else f"candidate-{index - 1}", candidate)
        for index, candidate in enumerate(candidates)
    )


def candidate_cohort(plan: OptimizationPlan, name: str, candidate: Candidate) -> Cohort:
    return Cohort(
        name=name,
        benchmark=candidate.benchmark,
        configuration=candidate.configuration,
        output=str(Path(plan.output) / name),
        tasks=plan.development_task_ids,
        repeats=plan.repeats,
    )


def admit(cohort: Cohort) -> TrialRun:
    return admit_trial_run(
        Path(cohort.benchmark).resolve(strict=True),
        Path(cohort.configuration).resolve(strict=True),
        cohort.tasks,
        cohort.holdout,
    )


def trial_rows(cohort: Cohort, run: TrialRun) -> tuple[Row, ...]:
    rows: list[Row] = []
    for repeat in range(cohort.repeats):
        for index, task in enumerate(run.tasks):
            arms: tuple[Arm, Arm] = (
                ("control", "treatment") if (index + repeat) % 2 == 0 else ("treatment", "control")
            )
            case_id = task.id if cohort.name == "comparison" else f"{cohort.name}/{task.id}"
            rows.extend(
                Row(caseId=case_id, cohort=cohort.name, taskId=task.id, arm=arm, repeat=repeat)
                for arm in arms
            )
    return tuple(rows)


def evidence_directory(output: Path, row: Row) -> Path:
    return output / f"{row.taskId}-{row.repeat}-{row.arm}"


def read_trial(path: Path) -> Trial:
    admitted = parse_trial(json.loads(path.read_bytes()))
    if isinstance(admitted, InvalidInput):
        raise ValueError(admitted.reason)
    return admitted.trial


def run_row(cohort: Cohort, run: TrialRun, row: Row) -> TrialResult:
    if row not in trial_rows(cohort, run):
        raise ValueError("UNKNOWN_TRIAL_ROW")
    output = Path(cohort.output).resolve(strict=True)
    manifest = TypeAdapter(dict[str, JsonValue]).validate_json(
        (output / "run-manifest.json").read_bytes()
    )
    config = Path(cohort.configuration).resolve(strict=True)
    if (
        manifest["configurationSha256"] != digest(config.read_bytes())
        or manifest["preparedManifestSha256"]
        != digest((Path(cohort.benchmark) / "manifest.json").read_bytes())
        or manifest["environment"] != run.environment.model_dump(mode="json")
        or manifest["repeats"] != cohort.repeats
        or manifest["tasks"] != list(task.id for task in run.tasks)
        or manifest["split"] != ("holdout" if cohort.holdout else "development")
    ):
        raise ValueError("TRIAL_PLAN_CHANGED")
    task = next(task for task in run.tasks if task.id == row.taskId)
    trial = run_trial(
        task, row.arm, row.repeat, run.configuration, run.environment, output, run.policy
    )
    evidence = evidence_directory(output, row)
    verdict = TypeAdapter(dict[str, JsonValue]).validate_json(
        (evidence / "verdict.json").read_bytes()
    )
    partial = TypeAdapter(dict[str, JsonValue]).validate_json(
        (evidence / "usage-evidence.json").read_bytes()
    )
    result_path = evidence / "plan.json.result.json"
    report = (
        TypeAdapter(dict[str, JsonValue]).validate_json(result_path.read_bytes()).get("report")
        if result_path.is_file()
        else None
    )
    session = SessionReport.model_validate(report) if report is not None else None
    usage_qualification = session.usageQualification if session else partial["qualification"]
    if not isinstance(usage_qualification, str):
        raise ValueError("INVALID_USAGE_QUALIFICATION")
    usage = None
    if session and (session.provider, session.model, session.effort) != (
        run.environment.provider,
        run.environment.model,
        run.environment.effort,
    ):
        raise ValueError("SESSION_MODEL_CHANGED")
    if session and usage_qualification == "FINALIZED_PROVIDER_USAGE":
        if session.totalTokens != trial.input_tokens + trial.output_tokens:
            raise ValueError("TOKEN_COMPONENT_MISMATCH")
        usage = Usage(total=session.totalTokens)
    metadata = TrialMetadata(
        model=f"{run.environment.provider}/{run.environment.model}",
        effort=run.environment.effort,
        wallSeconds=trial.wall_seconds,
        usageQualification=usage_qualification,
        toolCalls=session.toolCalls if session else None,
    )
    reason = verdict["reason"]
    if not isinstance(reason, str):
        raise ValueError("INVALID_VERDICT")
    status: Literal["PASSED", "FAILED", "BLOCKED"] = "BLOCKED"
    if session:
        status = "PASSED" if trial.correct else "FAILED"
    return TrialResult(
        status=status,
        reason=reason,
        metadata=metadata,
        tokenUsage=usage,
    )


def finish_cohort(cohort: Cohort, run: TrialRun) -> None:
    output = Path(cohort.output)
    receipts = tuple(
        (row, evidence_directory(output, row) / "trial.json") for row in trial_rows(cohort, run)
    )
    missing = tuple(row for row, path in receipts if not path.is_file())
    if missing:
        rejected = IncompleteCohort(missing=missing)
        save(output / "comparison.json", rejected.model_dump(mode="json"))
        raise ValueError(rejected.type)
    trials = tuple(read_trial(path) for _, path in receipts)
    report_trials(output, tuple(task.id for task in run.tasks), trials)


def main() -> None:
    request = Request.model_validate_json(sys.stdin.buffer.read())
    plan = TypeAdapter(ComparisonPlan | OptimizationPlan).validate_json(request.plan.read_bytes())
    admitted_cohorts = cohorts(plan)
    match request.action:
        case "cases":
            print(
                json.dumps(
                    [
                        row.model_dump()
                        for cohort in admitted_cohorts
                        for row in trial_rows(cohort, admit(cohort))
                    ]
                )
            )
        case "prepare":
            runs = tuple((cohort, admit(cohort)) for cohort in admitted_cohorts)
            if isinstance(plan, OptimizationPlan):
                output = Path(plan.output).resolve()
                output.mkdir(mode=0o700)
                save(output / "plan.json", plan.model_dump(mode="json"))
            for cohort, run in runs:
                record_trial_run(
                    run,
                    Path(cohort.benchmark).resolve(),
                    Path(cohort.configuration).resolve(),
                    Path(cohort.output).resolve(),
                    cohort.repeats,
                    cohort.holdout,
                )
            print("null")
        case "trial":
            row = request.row
            if row is None:
                raise ValueError("TRIAL_ROW_REQUIRED")
            cohort = next(cohort for cohort in admitted_cohorts if cohort.name == row.cohort)
            print(run_row(cohort, admit(cohort), row).model_dump_json(exclude_none=True))
        case "finish":
            for cohort in admitted_cohorts:
                finish_cohort(cohort, admit(cohort))
            if isinstance(plan, OptimizationPlan):
                report_optimization(plan, Path(plan.output))
            print("null")


if __name__ == "__main__":
    main()
