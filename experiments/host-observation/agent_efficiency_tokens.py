"""Simple accounting for the finite Pi token POC, without statistical acceptance."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from pathlib import Path
from typing import Literal

from pydantic import JsonValue, TypeAdapter

from agent_efficiency import Record, SessionReport, save
from agent_efficiency_metrics import InvalidInput, parse_trial


class Row(Record):
    task: str
    repeat: int
    arm: Literal["control", "treatment"]
    correct: bool
    known_tokens: int
    total_tokens: int | None
    requests: int
    tools: int


class ArmSummary(Record):
    trials: int
    correct: int
    failures: int
    incomplete_usage_trials: int
    known_tokens: int
    total_tokens: int | None
    median_tokens: float | None
    tokens_per_correct_completion: float | None


class Pair(Record):
    task: str
    repeat: int
    control_correct: bool
    treatment_correct: bool
    control_tokens: int | None
    treatment_tokens: int | None
    token_ratio: float | None


class Report(Record):
    objective: Literal["TOTAL_TOKENS_PER_CORRECT_TASK"] = "TOTAL_TOKENS_PER_CORRECT_TASK"
    usage_basis: str = (
        "Finalized provider input + output + cacheRead + cacheWrite, summed across every response"
    )
    correctness_scope: str = (
        "Independent fixed source answers and unchanged workspace; no population accuracy claim"
    )
    manifest_sha256: str
    control: ArmSummary
    treatment: ArmSummary
    pairs: tuple[Pair, ...]
    paired_correct_completions: int
    paired_correct_control_median: float | None
    paired_correct_treatment_median: float | None
    paired_correct_median_ratio: float | None
    aggregate_tokens_per_correct_ratio: float | None
    aggregate_token_savings_percent: float | None
    all_treatment_tasks_correct: bool
    all_usage_complete: bool


def ratio(numerator: float | int | None, denominator: float | int | None) -> float | None:
    return numerator / denominator if numerator is not None and denominator else None


def summary(rows: tuple[Row, ...]) -> ArmSummary:
    correct = sum(r.correct for r in rows)
    known = sum(r.known_tokens for r in rows)
    incomplete = sum(r.total_tokens is None for r in rows)
    total = None if incomplete else known
    return ArmSummary(
        trials=len(rows),
        correct=correct,
        failures=len(rows) - correct,
        incomplete_usage_trials=incomplete,
        known_tokens=known,
        total_tokens=total,
        median_tokens=float(statistics.median(r.known_tokens for r in rows))
        if not incomplete
        else None,
        tokens_per_correct_completion=ratio(total, correct),
    )


def analyze(run: Path) -> Report:
    manifest_bytes = (run / "run-manifest.json").read_bytes()
    manifest = TypeAdapter(dict[str, JsonValue]).validate_json(manifest_bytes)
    tasks = TypeAdapter(tuple[str, ...]).validate_python(manifest["tasks"])
    repeats = TypeAdapter(int).validate_python(manifest["repeats"])
    if not tasks or len(set(tasks)) != len(tasks) or repeats < 1:
        raise ValueError("INVALID_POC_MANIFEST")
    rows: list[Row] = []
    pairs: list[Pair] = []
    paired_control: list[int] = []
    paired_treatment: list[int] = []
    for task in tasks:
        for repeat in range(repeats):
            current: list[Row] = []
            for arm in ("control", "treatment"):
                owned = run / f"{task}-{repeat}-{arm}"
                trial = parse_trial(json.loads((owned / "trial.json").read_bytes()))
                if isinstance(trial, InvalidInput):
                    raise ValueError(trial.reason)
                t = trial.trial
                if (t.task_id, t.repeat, t.arm) != (task, repeat, arm):
                    raise ValueError("TRIAL_IDENTITY_MISMATCH")
                raw = TypeAdapter(dict[str, JsonValue]).validate_json(
                    (owned / "plan.json.result.json").read_bytes()
                )
                report = SessionReport.model_validate(raw["report"])
                if report.totalTokens != t.input_tokens + t.output_tokens:
                    raise ValueError("TOKEN_COMPONENT_MISMATCH")
                row = Row(
                    task=task,
                    repeat=repeat,
                    arm=arm,
                    correct=t.correct,
                    known_tokens=report.totalTokens,
                    total_tokens=report.totalTokens
                    if report.usageQualification == "FINALIZED_PROVIDER_USAGE"
                    else None,
                    requests=report.requests,
                    tools=report.toolCalls,
                )
                current.append(row)
                rows.append(row)
            c, t = current
            pairs.append(
                Pair(
                    task=task,
                    repeat=repeat,
                    control_correct=c.correct,
                    treatment_correct=t.correct,
                    control_tokens=c.total_tokens,
                    treatment_tokens=t.total_tokens,
                    token_ratio=ratio(c.total_tokens, t.total_tokens),
                )
            )
            if (
                c.correct
                and t.correct
                and c.total_tokens is not None
                and t.total_tokens is not None
            ):
                paired_control.append(c.total_tokens)
                paired_treatment.append(t.total_tokens)
    control = summary(tuple(r for r in rows if r.arm == "control"))
    treatment = summary(tuple(r for r in rows if r.arm == "treatment"))
    c_median = float(statistics.median(paired_control)) if paired_control else None
    t_median = float(statistics.median(paired_treatment)) if paired_treatment else None
    total_ratio = ratio(control.total_tokens, treatment.total_tokens)
    return Report(
        manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(),
        control=control,
        treatment=treatment,
        pairs=tuple(pairs),
        paired_correct_completions=len(paired_control),
        paired_correct_control_median=c_median,
        paired_correct_treatment_median=t_median,
        paired_correct_median_ratio=ratio(c_median, t_median),
        aggregate_tokens_per_correct_ratio=ratio(
            control.tokens_per_correct_completion, treatment.tokens_per_correct_completion
        ),
        aggregate_token_savings_percent=(1 - 1 / total_ratio) * 100 if total_ratio else None,
        all_treatment_tasks_correct=treatment.failures == 0,
        all_usage_complete=control.incomplete_usage_trials + treatment.incomplete_usage_trials == 0,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = analyze(args.run)
    save(args.output, report.model_dump(mode="json"))
    print(report.model_dump_json(indent=2))


if __name__ == "__main__":
    main()
