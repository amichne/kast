#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA_VERSION = 2
RECOMMENDED_SAMPLE_FLOOR = 5
IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._:/-]*$")
EXECUTION_CLASSES = {
    "static",
    "toolchain",
    "gradle-cache",
    "oci",
    "artifact-consumer",
    "release",
    "other",
}


class ModelError(Exception):
    pass


@dataclass(frozen=True)
class DurationStats:
    count: int
    mean_seconds: float
    median_seconds: float
    minimum_seconds: float
    maximum_seconds: float


@dataclass(frozen=True)
class Task:
    id: str
    needs: tuple[str, ...]
    outputs: tuple[str, ...]
    duration_samples_seconds: tuple[float, ...]
    execution_class: str

    @property
    def duration(self) -> DurationStats:
        return duration_stats(self.duration_samples_seconds)


@dataclass(frozen=True)
class Plan:
    tasks: Mapping[str, Task]
    canary_task_ids: tuple[str, ...]
    fanout_gate_task_ids: tuple[str, ...]
    observed_workflow_duration_samples_seconds: tuple[float, ...]


@dataclass(frozen=True)
class Expectations:
    output_equivalence: str
    timing_evidence_mode: str
    minimum_critical_path_reduction_seconds: float
    maximum_candidate_critical_path_seconds: float
    minimum_fanout_gate_reduction_seconds: float
    maximum_candidate_fanout_gate_seconds: float
    maximum_candidate_task_count_increase: int
    maximum_median_model_drift_ratio: float
    minimum_task_samples: int
    minimum_workflow_samples: int


@dataclass(frozen=True)
class Model:
    baseline: Plan
    candidate: Plan
    expectations: Expectations


@dataclass(frozen=True)
class PlanAnalysis:
    task_count: int
    edge_count: int
    output_ids: tuple[str, ...]
    canary_task_ids: tuple[str, ...]
    critical_path_task_ids: tuple[str, ...]
    critical_path_seconds: float
    fanout_gate_seconds: float
    maximum_parallel_tasks: int
    observed_workflow_duration: DurationStats
    median_model_drift_ratio: float
    execution_class_counts: Mapping[str, int]
    task_durations: Mapping[str, DurationStats]
    provisional_task_ids: tuple[str, ...]


def duration_stats(samples: Sequence[float]) -> DurationStats:
    if not samples:
        raise ModelError("duration samples must not be empty")
    return DurationStats(
        count=len(samples),
        mean_seconds=statistics.fmean(samples),
        median_seconds=statistics.median(samples),
        minimum_seconds=min(samples),
        maximum_seconds=max(samples),
    )


def require_mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise ModelError(f"{path} must be an object")
    return value


def require_list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise ModelError(f"{path} must be an array")
    return value


def validate_keys(
    value: Mapping[str, Any],
    *,
    path: str,
    required: set[str],
    optional: set[str] | None = None,
) -> None:
    optional = optional or set()
    missing = sorted(required - value.keys())
    unknown = sorted(value.keys() - required - optional)
    if missing:
        raise ModelError(f"{path} is missing required keys: {', '.join(missing)}")
    if unknown:
        raise ModelError(f"{path} has unsupported keys: {', '.join(unknown)}")


def require_string(value: Any, path: str, *, identifier: bool = False) -> str:
    if not isinstance(value, str) or not value:
        raise ModelError(f"{path} must be a non-empty string")
    if identifier and not IDENTIFIER.fullmatch(value):
        raise ModelError(f"{path} must match {IDENTIFIER.pattern}")
    return value


def require_number(value: Any, path: str, *, minimum: float = 0.0) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelError(f"{path} must be a number")
    number = float(value)
    if not math.isfinite(number):
        raise ModelError(f"{path} must be finite")
    if number < minimum:
        raise ModelError(f"{path} must be at least {minimum:g}")
    return number


def require_positive_number(value: Any, path: str) -> float:
    number = require_number(value, path)
    if number <= 0:
        raise ModelError(f"{path} must be greater than zero")
    return number


def require_integer(value: Any, path: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ModelError(f"{path} must be an integer")
    if value < minimum:
        raise ModelError(f"{path} must be at least {minimum}")
    return value


def parse_identifier_list(
    value: Any,
    path: str,
    *,
    minimum_items: int = 0,
) -> tuple[str, ...]:
    items = require_list(value, path)
    if len(items) < minimum_items:
        raise ModelError(f"{path} must contain at least {minimum_items} item(s)")
    parsed = tuple(
        require_string(item, f"{path}[{index}]", identifier=True)
        for index, item in enumerate(items)
    )
    if len(set(parsed)) != len(parsed):
        raise ModelError(f"{path} must not contain duplicates")
    return parsed


def parse_duration_samples(value: Any, path: str) -> tuple[float, ...]:
    items = require_list(value, path)
    if not items:
        raise ModelError(f"{path} must contain at least one sample")
    return tuple(
        require_positive_number(item, f"{path}[{index}]")
        for index, item in enumerate(items)
    )


def parse_task(value: Any, path: str) -> Task:
    item = require_mapping(value, path)
    validate_keys(
        item,
        path=path,
        required={"id", "needs", "outputs", "durationSamplesSeconds", "executionClass"},
    )
    task_id = require_string(item["id"], f"{path}.id", identifier=True)
    needs = parse_identifier_list(item["needs"], f"{path}.needs")
    if task_id in needs:
        raise ModelError(f"{path}.needs must not contain its own task id {task_id}")
    execution_class = require_string(item["executionClass"], f"{path}.executionClass")
    if execution_class not in EXECUTION_CLASSES:
        allowed = ", ".join(sorted(EXECUTION_CLASSES))
        raise ModelError(f"{path}.executionClass must be one of: {allowed}")
    return Task(
        id=task_id,
        needs=needs,
        outputs=parse_identifier_list(item["outputs"], f"{path}.outputs"),
        duration_samples_seconds=parse_duration_samples(
            item["durationSamplesSeconds"], f"{path}.durationSamplesSeconds"
        ),
        execution_class=execution_class,
    )


def parse_plan(value: Any, path: str) -> Plan:
    item = require_mapping(value, path)
    validate_keys(
        item,
        path=path,
        required={
            "tasks",
            "canaryTaskIds",
            "fanoutGateTaskIds",
            "observedWorkflowDurationSamplesSeconds",
        },
    )
    raw_tasks = require_list(item["tasks"], f"{path}.tasks")
    if not raw_tasks:
        raise ModelError(f"{path}.tasks must contain at least one task")
    parsed_tasks = [
        parse_task(task, f"{path}.tasks[{index}]")
        for index, task in enumerate(raw_tasks)
    ]
    tasks: dict[str, Task] = {}
    for task in parsed_tasks:
        if task.id in tasks:
            raise ModelError(f"{path}.tasks contains duplicate id {task.id}")
        tasks[task.id] = task

    for task in parsed_tasks:
        missing_needs = sorted(set(task.needs) - tasks.keys())
        if missing_needs:
            raise ModelError(
                f"{path}.tasks[{task.id}].needs references missing tasks: "
                + ", ".join(missing_needs)
            )

    output_owners: dict[str, str] = {}
    for task in parsed_tasks:
        for output in task.outputs:
            previous = output_owners.get(output)
            if previous is not None:
                raise ModelError(
                    f"{path} output {output} has multiple owners: {previous}, {task.id}"
                )
            output_owners[output] = task.id

    canary_tasks = parse_identifier_list(
        item["canaryTaskIds"],
        f"{path}.canaryTaskIds",
    )
    missing_canaries = sorted(set(canary_tasks) - tasks.keys())
    if missing_canaries:
        raise ModelError(
            f"{path}.canaryTaskIds references missing tasks: {', '.join(missing_canaries)}"
        )
    required_tasks = set(tasks) - set(canary_tasks)
    if not required_tasks:
        raise ModelError(f"{path} must contain at least one pull-request task")
    for task_id in sorted(required_tasks):
        canary_needs = sorted(set(tasks[task_id].needs) & set(canary_tasks))
        if canary_needs:
            raise ModelError(
                f"{path}.tasks[{task_id}] pull-request task depends on canary tasks: "
                + ", ".join(canary_needs)
            )

    fanout_gates = parse_identifier_list(
        item["fanoutGateTaskIds"],
        f"{path}.fanoutGateTaskIds",
        minimum_items=1,
    )
    missing_gates = sorted(set(fanout_gates) - tasks.keys())
    if missing_gates:
        raise ModelError(
            f"{path}.fanoutGateTaskIds references missing tasks: {', '.join(missing_gates)}"
        )
    canary_gates = sorted(set(fanout_gates) & set(canary_tasks))
    if canary_gates:
        raise ModelError(
            f"{path}.fanoutGateTaskIds must not include canary tasks: {', '.join(canary_gates)}"
        )

    plan = Plan(
        tasks=tasks,
        canary_task_ids=canary_tasks,
        fanout_gate_task_ids=fanout_gates,
        observed_workflow_duration_samples_seconds=parse_duration_samples(
            item["observedWorkflowDurationSamplesSeconds"],
            f"{path}.observedWorkflowDurationSamplesSeconds",
        ),
    )
    topological_order(plan, path)
    return plan


def parse_expectations(value: Any) -> Expectations:
    path = "expectations"
    item = require_mapping(value, path)
    required = {
        "outputEquivalence",
        "timingEvidenceMode",
        "minimumCriticalPathReductionSeconds",
        "maximumCandidateCriticalPathSeconds",
        "minimumFanoutGateReductionSeconds",
        "maximumCandidateFanoutGateSeconds",
        "maximumCandidateTaskCountIncrease",
        "maximumMedianModelDriftRatio",
        "minimumTaskSamples",
        "minimumWorkflowSamples",
    }
    validate_keys(item, path=path, required=required)
    output_equivalence = require_string(item["outputEquivalence"], f"{path}.outputEquivalence")
    if output_equivalence != "exact":
        raise ModelError(f"{path}.outputEquivalence must be exact")
    timing_evidence_mode = require_string(
        item["timingEvidenceMode"], f"{path}.timingEvidenceMode"
    )
    if timing_evidence_mode not in {"blocking", "provisional"}:
        raise ModelError(
            f"{path}.timingEvidenceMode must be blocking or provisional"
        )
    drift = require_number(
        item["maximumMedianModelDriftRatio"],
        f"{path}.maximumMedianModelDriftRatio",
    )
    if drift > 1:
        raise ModelError(f"{path}.maximumMedianModelDriftRatio must be at most 1")
    return Expectations(
        output_equivalence=output_equivalence,
        timing_evidence_mode=timing_evidence_mode,
        minimum_critical_path_reduction_seconds=require_number(
            item["minimumCriticalPathReductionSeconds"],
            f"{path}.minimumCriticalPathReductionSeconds",
        ),
        maximum_candidate_critical_path_seconds=require_positive_number(
            item["maximumCandidateCriticalPathSeconds"],
            f"{path}.maximumCandidateCriticalPathSeconds",
        ),
        minimum_fanout_gate_reduction_seconds=require_number(
            item["minimumFanoutGateReductionSeconds"],
            f"{path}.minimumFanoutGateReductionSeconds",
        ),
        maximum_candidate_fanout_gate_seconds=require_positive_number(
            item["maximumCandidateFanoutGateSeconds"],
            f"{path}.maximumCandidateFanoutGateSeconds",
        ),
        maximum_candidate_task_count_increase=require_integer(
            item["maximumCandidateTaskCountIncrease"],
            f"{path}.maximumCandidateTaskCountIncrease",
        ),
        maximum_median_model_drift_ratio=drift,
        minimum_task_samples=require_integer(
            item["minimumTaskSamples"],
            f"{path}.minimumTaskSamples",
            minimum=1,
        ),
        minimum_workflow_samples=require_integer(
            item["minimumWorkflowSamples"],
            f"{path}.minimumWorkflowSamples",
            minimum=1,
        ),
    )


def validate_provenance(value: Any) -> None:
    path = "provenance"
    item = require_mapping(value, path)
    required = {"description", "observedAt", "source", "baselineRunIds", "candidateRunIds"}
    validate_keys(item, path=path, required=required)
    require_string(item["description"], f"{path}.description")
    observed_at = require_string(item["observedAt"], f"{path}.observedAt")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", observed_at):
        raise ModelError(f"{path}.observedAt must use YYYY-MM-DD")
    require_string(item["source"], f"{path}.source")
    for key in ("baselineRunIds", "candidateRunIds"):
        raw_ids = require_list(item[key], f"{path}.{key}")
        parsed_ids = [
            require_integer(run_id, f"{path}.{key}[{index}]", minimum=1)
            for index, run_id in enumerate(raw_ids)
        ]
        if len(set(parsed_ids)) != len(parsed_ids):
            raise ModelError(f"{path}.{key} must not contain duplicates")


def parse_model(value: Any) -> Model:
    item = require_mapping(value, "model")
    validate_keys(
        item,
        path="model",
        required={"schemaVersion", "baseline", "candidate", "expectations"},
        optional={"$schema", "provenance"},
    )
    version = require_integer(item["schemaVersion"], "model.schemaVersion", minimum=1)
    if version != SCHEMA_VERSION:
        raise ModelError(
            f"model.schemaVersion must be {SCHEMA_VERSION}, received {version}"
        )
    if "$schema" in item:
        require_string(item["$schema"], "model.$schema")
    if "provenance" in item:
        validate_provenance(item["provenance"])
    return Model(
        baseline=parse_plan(item["baseline"], "baseline"),
        candidate=parse_plan(item["candidate"], "candidate"),
        expectations=parse_expectations(item["expectations"]),
    )


def topological_order(plan: Plan, path: str = "plan") -> tuple[str, ...]:
    incoming = {task_id: len(task.needs) for task_id, task in plan.tasks.items()}
    dependents: dict[str, list[str]] = {task_id: [] for task_id in plan.tasks}
    for task in plan.tasks.values():
        for need in task.needs:
            dependents[need].append(task.id)
    ready = sorted(task_id for task_id, count in incoming.items() if count == 0)
    ordered: list[str] = []
    while ready:
        task_id = ready.pop(0)
        ordered.append(task_id)
        for dependent in sorted(dependents[task_id]):
            incoming[dependent] -= 1
            if incoming[dependent] == 0:
                ready.append(dependent)
                ready.sort()
    if len(ordered) != len(plan.tasks):
        cyclic = sorted(task_id for task_id, count in incoming.items() if count > 0)
        raise ModelError(f"{path} contains a dependency cycle involving: {', '.join(cyclic)}")
    return tuple(ordered)


def maximum_parallel_tasks(starts: Mapping[str, float], finishes: Mapping[str, float]) -> int:
    events: list[tuple[float, int]] = []
    for task_id in starts:
        events.append((starts[task_id], 1))
        events.append((finishes[task_id], -1))
    active = 0
    maximum = 0
    for _, delta in sorted(events, key=lambda event: (event[0], event[1])):
        active += delta
        maximum = max(maximum, active)
    return maximum


def analyze_plan(plan: Plan) -> PlanAnalysis:
    canary_task_ids = set(plan.canary_task_ids)
    order = tuple(
        task_id
        for task_id in topological_order(plan)
        if task_id not in canary_task_ids
    )
    starts: dict[str, float] = {}
    finishes: dict[str, float] = {}
    predecessor: dict[str, str | None] = {}
    task_durations = {task_id: plan.tasks[task_id].duration for task_id in plan.tasks}

    for task_id in order:
        task = plan.tasks[task_id]
        if task.needs:
            previous = max(task.needs, key=lambda need: (finishes[need], need))
            starts[task_id] = finishes[previous]
            predecessor[task_id] = previous
        else:
            starts[task_id] = 0.0
            predecessor[task_id] = None
        finishes[task_id] = starts[task_id] + task_durations[task_id].median_seconds

    terminal = max(order, key=lambda task_id: (finishes[task_id], task_id))
    critical_path: list[str] = []
    cursor: str | None = terminal
    while cursor is not None:
        critical_path.append(cursor)
        cursor = predecessor[cursor]
    critical_path.reverse()

    observed = duration_stats(plan.observed_workflow_duration_samples_seconds)
    modeled = finishes[terminal]
    drift = abs(modeled - observed.median_seconds) / observed.median_seconds
    output_ids = tuple(sorted(output for task in plan.tasks.values() for output in task.outputs))
    execution_counts: dict[str, int] = {}
    for task in plan.tasks.values():
        execution_counts[task.execution_class] = execution_counts.get(task.execution_class, 0) + 1

    return PlanAnalysis(
        task_count=len(plan.tasks),
        edge_count=sum(len(task.needs) for task in plan.tasks.values()),
        output_ids=output_ids,
        canary_task_ids=tuple(sorted(canary_task_ids)),
        critical_path_task_ids=tuple(critical_path),
        critical_path_seconds=modeled,
        fanout_gate_seconds=max(finishes[task_id] for task_id in plan.fanout_gate_task_ids),
        maximum_parallel_tasks=maximum_parallel_tasks(starts, finishes),
        observed_workflow_duration=observed,
        median_model_drift_ratio=drift,
        execution_class_counts=dict(sorted(execution_counts.items())),
        task_durations=dict(sorted(task_durations.items())),
        provisional_task_ids=tuple(
            sorted(
                task_id
                for task_id, stats in task_durations.items()
                if stats.count < RECOMMENDED_SAMPLE_FLOOR
            )
        ),
    )


def rounded(value: float) -> float:
    return round(value, 3)


def stats_document(stats: DurationStats) -> dict[str, int | float]:
    return {
        "sampleCount": stats.count,
        "meanSeconds": rounded(stats.mean_seconds),
        "medianSeconds": rounded(stats.median_seconds),
        "minimumSeconds": rounded(stats.minimum_seconds),
        "maximumSeconds": rounded(stats.maximum_seconds),
    }


def analysis_document(analysis: PlanAnalysis) -> dict[str, Any]:
    return {
        "taskCount": analysis.task_count,
        "pullRequestTaskCount": analysis.task_count - len(analysis.canary_task_ids),
        "edgeCount": analysis.edge_count,
        "outputCount": len(analysis.output_ids),
        "canaryTaskIds": list(analysis.canary_task_ids),
        "criticalPathTaskIds": list(analysis.critical_path_task_ids),
        "criticalPathSeconds": rounded(analysis.critical_path_seconds),
        "fanoutGateSeconds": rounded(analysis.fanout_gate_seconds),
        "maximumParallelTasks": analysis.maximum_parallel_tasks,
        "observedWorkflowDuration": stats_document(analysis.observed_workflow_duration),
        "medianModelDriftRatio": rounded(analysis.median_model_drift_ratio),
        "executionClassCounts": analysis.execution_class_counts,
        "taskDurations": {
            task_id: stats_document(stats)
            for task_id, stats in analysis.task_durations.items()
        },
        "provisionalTaskIds": list(analysis.provisional_task_ids),
    }


def compare(model: Model) -> dict[str, Any]:
    baseline = analyze_plan(model.baseline)
    candidate = analyze_plan(model.candidate)
    expectations = model.expectations
    baseline_outputs = set(baseline.output_ids)
    candidate_outputs = set(candidate.output_ids)
    missing_outputs = sorted(baseline_outputs - candidate_outputs)
    added_outputs = sorted(candidate_outputs - baseline_outputs)
    critical_reduction = baseline.critical_path_seconds - candidate.critical_path_seconds
    gate_reduction = baseline.fanout_gate_seconds - candidate.fanout_gate_seconds
    task_count_increase = candidate.task_count - baseline.task_count
    failures: list[str] = []
    warnings: list[str] = []
    timing_findings: list[str] = []

    if expectations.output_equivalence == "exact" and (missing_outputs or added_outputs):
        failures.append(
            "candidate proof outputs are not exactly equivalent to baseline"
        )
    if critical_reduction < expectations.minimum_critical_path_reduction_seconds:
        timing_findings.append(
            "critical-path reduction "
            f"{critical_reduction:.3f}s is below required "
            f"{expectations.minimum_critical_path_reduction_seconds:.3f}s"
        )
    if candidate.critical_path_seconds > expectations.maximum_candidate_critical_path_seconds:
        timing_findings.append(
            "candidate critical path "
            f"{candidate.critical_path_seconds:.3f}s exceeds maximum "
            f"{expectations.maximum_candidate_critical_path_seconds:.3f}s"
        )
    if gate_reduction < expectations.minimum_fanout_gate_reduction_seconds:
        timing_findings.append(
            "fan-out gate reduction "
            f"{gate_reduction:.3f}s is below required "
            f"{expectations.minimum_fanout_gate_reduction_seconds:.3f}s"
        )
    if candidate.fanout_gate_seconds > expectations.maximum_candidate_fanout_gate_seconds:
        timing_findings.append(
            "candidate fan-out gate "
            f"{candidate.fanout_gate_seconds:.3f}s exceeds maximum "
            f"{expectations.maximum_candidate_fanout_gate_seconds:.3f}s"
        )
    if task_count_increase > expectations.maximum_candidate_task_count_increase:
        failures.append(
            "candidate task-count increase "
            f"{task_count_increase} exceeds maximum "
            f"{expectations.maximum_candidate_task_count_increase}"
        )

    for name, plan, analysis in (
        ("baseline", model.baseline, baseline),
        ("candidate", model.candidate, candidate),
    ):
        undersampled = sorted(
            task_id
            for task_id, task in plan.tasks.items()
            if len(task.duration_samples_seconds) < expectations.minimum_task_samples
        )
        if undersampled:
            timing_findings.append(
                f"{name} tasks below minimumTaskSamples: {', '.join(undersampled)}"
            )
        if (
            len(plan.observed_workflow_duration_samples_seconds)
            < expectations.minimum_workflow_samples
        ):
            timing_findings.append(f"{name} workflow is below minimumWorkflowSamples")
        if analysis.median_model_drift_ratio > expectations.maximum_median_model_drift_ratio:
            timing_findings.append(
                f"{name} median model drift ratio "
                f"{analysis.median_model_drift_ratio:.3f} exceeds maximum "
                f"{expectations.maximum_median_model_drift_ratio:.3f}"
            )
        if analysis.provisional_task_ids:
            warnings.append(
                f"{name} task timing is provisional below {RECOMMENDED_SAMPLE_FLOOR} samples: "
                + ", ".join(analysis.provisional_task_ids)
            )
        if analysis.observed_workflow_duration.count < RECOMMENDED_SAMPLE_FLOOR:
            warnings.append(
                f"{name} workflow timing is provisional below "
                f"{RECOMMENDED_SAMPLE_FLOOR} samples"
            )

    if expectations.timing_evidence_mode == "blocking":
        failures.extend(timing_findings)
    else:
        warnings.extend(f"provisional timing: {finding}" for finding in timing_findings)

    if failures:
        status = "fail"
    elif expectations.timing_evidence_mode == "provisional":
        status = "provisional"
    else:
        status = "pass"

    return {
        "schemaVersion": SCHEMA_VERSION,
        "status": status,
        "baseline": analysis_document(baseline),
        "candidate": analysis_document(candidate),
        "comparison": {
            "outputEquivalent": not missing_outputs and not added_outputs,
            "missingOutputIds": missing_outputs,
            "addedOutputIds": added_outputs,
            "criticalPathReductionSeconds": rounded(critical_reduction),
            "criticalPathReductionRatio": rounded(
                critical_reduction / baseline.critical_path_seconds
            ),
            "fanoutGateReductionSeconds": rounded(gate_reduction),
            "taskCountIncrease": task_count_increase,
        },
        "warnings": warnings,
        "failures": failures,
    }


def compare_document(value: Any) -> dict[str, Any]:
    return compare(parse_model(value))


def load_document(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ModelError(f"model file does not exist: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ModelError(f"invalid JSON in {path}: {exc}") from exc


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare deterministic median-timed workflow dependency graphs."
    )
    parser.add_argument("model", type=Path, help="workflow graph model JSON")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        result = compare_document(load_document(args.model))
    except ModelError as exc:
        print(
            json.dumps(
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "status": "invalid",
                    "errors": [str(exc)],
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] in {"pass", "provisional"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
