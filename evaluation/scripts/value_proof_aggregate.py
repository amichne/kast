#!/usr/bin/env python3
"""Aggregate evaluation runs into the authoritative benchmark contract."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import statistics
import sys
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable

PRIMARY_DIMENSIONS = (
    "task_completion",
    "accuracy",
    "reliability",
    "scope_control",
)
MEASUREMENT_DIMENSIONS = PRIMARY_DIMENSIONS + ("efficiency",)
MEASUREMENT_KEYS = ("overall",) + MEASUREMENT_DIMENSIONS
MEASUREMENT_KINDS = ("all", "outcome", "process")
CONFIGURATION_ORDER = ("with_skill", "tool_only", "without_skill")
EFFICIENCY_METRICS = (
    "transcript_chars",
    "total_tool_calls",
    "semantic_tool_calls",
    "generic_search_calls",
    "executor_duration_seconds",
    "errors_encountered",
)
PAIR_SCORE_METRICS = {
    "overall_outcome": ("overall", "outcome"),
    "task_completion": ("task_completion", "outcome"),
    "accuracy": ("accuracy", "outcome"),
    "reliability": ("reliability", "outcome"),
    "scope_control": ("scope_control", "outcome"),
}
OUTLIER_METRICS = {
    "overall_outcome_score": ("overall", "outcome"),
    "transcript_chars": "transcript_chars",
    "executor_duration_seconds": "executor_duration_seconds",
}


def _read_json(path: Path | None) -> dict[str, Any]:
    if path is None or not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def _iter_runs(iteration_dir: Path) -> Iterable[tuple[str, str, int, Path]]:
    for grading_path in sorted(iteration_dir.glob("eval-*/*/run-*/grading.json")):
        eval_dir = grading_path.parents[2]
        config_dir = grading_path.parents[1]
        run_dir = grading_path.parent
        eval_id = eval_dir.name.removeprefix("eval-")
        try:
            run_number = int(run_dir.name.removeprefix("run-"))
        except ValueError:
            continue
        yield eval_id, config_dir.name, run_number, run_dir


def _build_expectation_index(catalog: dict[str, Any]) -> dict[str, dict[str, dict[str, Any]]]:
    index: dict[str, dict[str, dict[str, Any]]] = {}
    for case in catalog.get("cases", []) or []:
        if not isinstance(case, dict):
            continue
        case_id = str(case.get("id", "")).strip()
        if not case_id:
            continue
        entries: dict[str, dict[str, Any]] = {}
        for expectation in case.get("expectations", []) or []:
            if not isinstance(expectation, dict):
                continue
            expectation_id = str(expectation.get("id", "")).strip()
            expectation_text = str(expectation.get("text", "")).strip()
            if expectation_id:
                entries[expectation_id] = expectation
            if expectation_text:
                entries[expectation_text] = expectation
        index[case_id] = entries
    return index


def _evidence(entry: dict[str, Any], graded_by: str) -> dict[str, Any]:
    raw = entry.get("evidence")
    if isinstance(raw, dict):
        kind = str(raw.get("kind", "")).strip() or ("automated_check" if graded_by == "script" else "llm_judgment")
        summary = str(raw.get("summary", "")).strip() or "No evidence provided."
        citations = raw.get("citations")
        evidence = {"kind": kind, "summary": summary}
        if isinstance(citations, list):
            evidence["citations"] = [str(item) for item in citations if str(item).strip()]
        return evidence
    summary = str(raw or "").strip() or "No evidence provided."
    return {
        "kind": "automated_check" if graded_by == "script" else "llm_judgment",
        "summary": summary,
    }


def _normalize_expectation(
    *,
    eval_id: str,
    entry: dict[str, Any],
    expectation_index: dict[str, dict[str, dict[str, Any]]],
    configuration: str,
) -> dict[str, Any]:
    case_index = expectation_index.get(eval_id, {})
    info = case_index.get(str(entry.get("id", "")).strip()) or case_index.get(str(entry.get("text", "")).strip()) or {}
    expectation_id = str(entry.get("id") or info.get("id") or entry.get("text") or "unknown").strip()
    text = str(entry.get("text") or info.get("text") or expectation_id).strip()
    kind = str(entry.get("kind") or info.get("kind") or "outcome")
    dimension = str(entry.get("dimension") or info.get("dimension") or "task_completion")
    applicability = str(entry.get("applicability") or info.get("applicability") or "both")
    graded_by = str(entry.get("graded_by") or info.get("graded_by") or "llm")

    normalized: dict[str, Any] = {
        "id": expectation_id,
        "text": text,
        "kind": kind,
        "dimension": dimension,
        "applicability": applicability,
        "graded_by": graded_by,
    }
    oracle = entry.get("oracle") or info.get("oracle")
    if isinstance(oracle, str) and oracle.strip():
        normalized["oracle"] = oracle.strip()

    if entry.get("skipped") is True or (
        applicability == "with_skill_only" and configuration != "with_skill"
    ) or (
        applicability == "without_skill_only" and configuration != "without_skill"
    ):
        normalized["status"] = "not_applicable"
        normalized["reason"] = "configuration_mismatch"
        return normalized

    if "passed" in entry:
        normalized["status"] = "passed" if bool(entry.get("passed")) else "failed"
        normalized["evidence"] = _evidence(entry, graded_by)
        return normalized

    normalized["status"] = "ungraded"
    normalized["error"] = "missing_pass_fail_status"
    return normalized


def _score_group(expectations: list[dict[str, Any]]) -> dict[str, Any]:
    passed = sum(1 for expectation in expectations if expectation["status"] == "passed")
    failed = sum(1 for expectation in expectations if expectation["status"] == "failed")
    not_applicable = sum(1 for expectation in expectations if expectation["status"] == "not_applicable")
    eligible = passed + failed
    if eligible == 0:
        return {
            "status": "not_applicable",
            "eligible": 0,
            "not_applicable": not_applicable,
        }
    return {
        "status": "scored",
        "eligible": eligible,
        "passed": passed,
        "failed": failed,
        "not_applicable": not_applicable,
        "score": round(passed / eligible, 4),
    }


def _select_expectations(
    expectations: list[dict[str, Any]],
    *,
    dimension: str | None,
    kind: str | None,
) -> list[dict[str, Any]]:
    selected = expectations
    if dimension is not None:
        selected = [expectation for expectation in selected if expectation["dimension"] == dimension]
    if kind is not None:
        selected = [expectation for expectation in selected if expectation["kind"] == kind]
    return selected


def _measurement_card(expectations: list[dict[str, Any]], *, dimension: str | None) -> dict[str, Any]:
    return {
        "all": _score_group(_select_expectations(expectations, dimension=dimension, kind=None)),
        "outcome": _score_group(_select_expectations(expectations, dimension=dimension, kind="outcome")),
        "process": _score_group(_select_expectations(expectations, dimension=dimension, kind="process")),
    }


def _measurements(expectations: list[dict[str, Any]]) -> dict[str, Any]:
    measurements = {"overall": _measurement_card(expectations, dimension=None)}
    for dimension in MEASUREMENT_DIMENSIONS:
        measurements[dimension] = _measurement_card(expectations, dimension=dimension)
    return measurements


def _integrity(grading: dict[str, Any], configuration: str) -> dict[str, Any]:
    source = grading.get("integrity", {}) or {}
    contradictions = source.get("contradictions", []) or []
    baseline_violated = bool(source.get("baseline_isolation_violation"))
    if configuration == "without_skill":
        baseline_isolation = "violated" if baseline_violated else "intact"
    else:
        baseline_isolation = "not_applicable"
    return {
        "attempts": int(source.get("attempts", 1) or 1),
        "flaky": bool(source.get("flaky", False)),
        "baseline_isolation": baseline_isolation,
        "contradiction_count": len(contradictions),
        "contradiction_samples": [str(item) for item in contradictions[:3] if str(item).strip()],
        "workspace_dirty_post": bool(source.get("workspace_dirty_post", False)),
        **({"git_sha_post": str(source["git_sha_post"])} if str(source.get("git_sha_post", "")).strip() else {}),
    }


def _run_efficiency(grading: dict[str, Any]) -> dict[str, Any]:
    execution = grading.get("execution_metrics", {}) or {}
    timing = grading.get("timing", {}) or {}
    return {
        "transcript_chars": int(execution.get("transcript_chars", 0) or 0),
        "total_tool_calls": int(execution.get("total_tool_calls", 0) or 0),
        "semantic_tool_calls": int(execution.get("kast_calls", 0) or 0),
        "generic_search_calls": int(execution.get("grep_or_find_calls", 0) or 0),
        "executor_duration_seconds": float(timing.get("executor_duration_seconds", 0.0) or 0.0),
        "executor_duration_source": str(timing.get("executor_duration_source", "missing") or "missing"),
        "errors_encountered": int(execution.get("errors_encountered", 0) or 0),
    }


def _invalid_reason(expectations: list[dict[str, Any]], integrity: dict[str, Any]) -> str | None:
    if any(expectation["status"] == "ungraded" for expectation in expectations):
        return "ungraded_expectation"
    if integrity["baseline_isolation"] == "violated":
        return "baseline_tainted"
    if integrity["contradiction_count"] > 0:
        return "contradictory_grading"
    return None


def _distribution(
    values: list[float],
    *,
    lower_bound: float | None = None,
    upper_bound: float | None = None,
) -> dict[str, Any]:
    if not values:
        return {"status": "no_valid_samples", "n": 0}
    mean = statistics.fmean(values)
    stddev = statistics.pstdev(values) if len(values) > 1 else 0.0
    if len(values) == 1:
        ci_lower = ci_upper = mean
        method = "degenerate_single_sample"
    else:
        margin = 1.96 * stddev / math.sqrt(len(values))
        ci_lower = mean - margin
        ci_upper = mean + margin
        method = "normal_approximation"
    if lower_bound is not None:
        ci_lower = max(lower_bound, ci_lower)
    if upper_bound is not None:
        ci_upper = min(upper_bound, ci_upper)
    mean = max(lower_bound, mean) if lower_bound is not None else mean
    mean = min(upper_bound, mean) if upper_bound is not None else mean
    minimum = min(values)
    maximum = max(values)
    if lower_bound is not None:
        minimum = max(lower_bound, minimum)
        maximum = max(lower_bound, maximum)
    if upper_bound is not None:
        minimum = min(upper_bound, minimum)
        maximum = min(upper_bound, maximum)
    return {
        "status": "summarized",
        "n": len(values),
        "mean": round(mean, 4),
        "stddev": round(stddev, 4),
        "min": round(minimum, 4),
        "max": round(maximum, 4),
        "confidence_interval_95": {
            "level": 0.95,
            "lower": round(ci_lower, 4),
            "upper": round(ci_upper, 4),
            "method": method,
        },
    }


def _empty_surface_summary() -> dict[str, Any]:
    return {
        "run_counts": {
            "total": 0,
            "valid": 0,
            "invalid": 0,
        },
        "measurements": {
            measurement_key: {kind: {"status": "no_valid_samples", "n": 0} for kind in MEASUREMENT_KINDS}
            for measurement_key in MEASUREMENT_KEYS
        },
        "efficiency": {
            metric: {"status": "no_valid_samples", "n": 0}
            for metric in EFFICIENCY_METRICS
        },
    }


def _configuration_summary(runs: list[dict[str, Any]], *, surface: str) -> dict[str, Any]:
    valid_runs = [run for run in runs if run["status"] == "valid"]
    measurements: dict[str, Any] = {}
    for measurement_key in MEASUREMENT_KEYS:
        measurements[measurement_key] = {}
        for kind in MEASUREMENT_KINDS:
            scores = [
                float(run[surface]["measurements"][measurement_key][kind]["score"])
                for run in valid_runs
                if run[surface]["measurements"][measurement_key][kind]["status"] == "scored"
            ]
            measurements[measurement_key][kind] = _distribution(
                scores,
                lower_bound=0.0,
                upper_bound=1.0,
            )
    if surface == "llm_graded":
        efficiency = {metric: {"status": "no_valid_samples", "n": 0} for metric in EFFICIENCY_METRICS}
    else:
        efficiency = {
            metric: _distribution([float(run["efficiency"][metric]) for run in valid_runs], lower_bound=0.0)
            for metric in EFFICIENCY_METRICS
        }
    return {
        "run_counts": {
            "total": len(runs),
            "valid": len(valid_runs),
            "invalid": len(runs) - len(valid_runs),
        },
        "measurements": measurements,
        "efficiency": efficiency,
    }


def _wilcoxon_signed_rank(deltas: list[float]) -> dict[str, Any]:
    nonzero = [delta for delta in deltas if delta != 0]
    n_pairs = len(nonzero)
    if n_pairs == 0:
        return {"status": "not_applicable", "n_pairs": 0}

    absolute_values = sorted(
        ((abs(delta), 1 if delta > 0 else -1) for delta in nonzero),
        key=lambda item: item[0],
    )
    ranks = [0.0] * n_pairs
    index = 0
    while index < n_pairs:
        end = index
        while end + 1 < n_pairs and absolute_values[end + 1][0] == absolute_values[index][0]:
            end += 1
        average_rank = (index + end) / 2.0 + 1.0
        for rank_index in range(index, end + 1):
            ranks[rank_index] = average_rank
        index = end + 1

    w_plus = sum(rank for rank, (_, sign) in zip(ranks, absolute_values) if sign > 0)
    w_minus = sum(rank for rank, (_, sign) in zip(ranks, absolute_values) if sign < 0)
    statistic = min(w_plus, w_minus)
    if n_pairs <= 20:
        p_value = _wilcoxon_exact_p(nonzero, statistic)
        method = "exact"
    else:
        mean = n_pairs * (n_pairs + 1) / 4.0
        variance = n_pairs * (n_pairs + 1) * (2 * n_pairs + 1) / 24.0
        if variance == 0:
            p_value = 1.0
            method = "no_variance"
        else:
            z_score = (statistic - mean + 0.5) / math.sqrt(variance)
            p_value = 2 * _normal_sf(abs(z_score))
            method = "normal_approx"

    total_rank = w_plus + w_minus
    effect_size = 0.0 if total_rank == 0 else (w_plus - w_minus) / total_rank
    return {
        "status": "scored",
        "test_name": "wilcoxon_signed_rank",
        "alternative": "two_sided",
        "zero_method": "wilcox",
        "correction": "none",
        "statistic": round(float(statistic), 4),
        "p_value": round(min(1.0, max(0.0, p_value)), 4),
        "n_pairs": n_pairs,
        "method": method,
        "effect_size_rank_biserial": round(effect_size, 4),
        "mean_delta": round(statistics.fmean(nonzero), 4),
        "median_delta": round(statistics.median(nonzero), 4),
    }


def _wilcoxon_exact_p(deltas: list[float], statistic: float) -> float:
    count = len(deltas)
    if count > 20:
        return 1.0
    absolute_values = sorted(abs(delta) for delta in deltas)
    ranks = [0.0] * count
    index = 0
    while index < count:
        end = index
        while end + 1 < count and absolute_values[end + 1] == absolute_values[index]:
            end += 1
        average_rank = (index + end) / 2.0 + 1.0
        for rank_index in range(index, end + 1):
            ranks[rank_index] = average_rank
        index = end + 1
    total = 1 << count
    less_or_equal = 0
    for mask in range(total):
        w_plus = 0.0
        for bit in range(count):
            if mask & (1 << bit):
                w_plus += ranks[bit]
        w_minus = sum(ranks) - w_plus
        if min(w_plus, w_minus) <= statistic + 1e-9:
            less_or_equal += 1
    return less_or_equal / total


def _normal_sf(value: float) -> float:
    return 0.5 * math.erfc(value / math.sqrt(2))


def _mean_run_score(runs: list[dict[str, Any]], measurement_key: str, kind: str) -> float | None:
    values = [
        float(run["measurements"][measurement_key][kind]["score"])
        for run in runs
        if run["measurements"][measurement_key][kind]["status"] == "scored"
    ]
    return statistics.fmean(values) if values else None


def _score_delta(with_runs: list[dict[str, Any]], without_runs: list[dict[str, Any]], measurement_key: str, kind: str) -> dict[str, Any]:
    with_mean = _mean_run_score(with_runs, measurement_key, kind)
    without_mean = _mean_run_score(without_runs, measurement_key, kind)
    if with_mean is None or without_mean is None:
        return {"status": "not_applicable"}
    return {
        "status": "scored",
        "with_skill_mean": round(with_mean, 4),
        "without_skill_mean": round(without_mean, 4),
        "delta": round(with_mean - without_mean, 4),
    }


def _metric_delta(with_runs: list[dict[str, Any]], without_runs: list[dict[str, Any]], metric: str) -> dict[str, Any]:
    with_mean = statistics.fmean(float(run["efficiency"][metric]) for run in with_runs)
    without_mean = statistics.fmean(float(run["efficiency"][metric]) for run in without_runs)
    return {
        "with_skill_mean": round(with_mean, 4),
        "without_skill_mean": round(without_mean, 4),
        "delta": round(with_mean - without_mean, 4),
    }


def _tukey_outliers(values: dict[str, float]) -> list[tuple[str, float, str]]:
    if len(values) < 4:
        return []
    sorted_values = sorted(values.values())
    q1 = sorted_values[len(sorted_values) // 4]
    q3 = sorted_values[(3 * len(sorted_values)) // 4]
    iqr = q3 - q1
    if iqr == 0:
        return []
    lower = q1 - 1.5 * iqr
    upper = q3 + 1.5 * iqr
    outliers = []
    for key, value in values.items():
        if value < lower:
            outliers.append((key, value, "low"))
        elif value > upper:
            outliers.append((key, value, "high"))
    return outliers


def _paired_analysis(runs: list[dict[str, Any]]) -> dict[str, Any]:
    valid_runs = [run for run in runs if run["status"] == "valid"]
    by_eval_config: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: defaultdict(list))
    invalid_runs: list[dict[str, Any]] = []
    flaky_runs: list[dict[str, Any]] = []
    for run in runs:
        if run["integrity"]["flaky"]:
            flaky_runs.append(
                {
                    "eval_id": run["eval_id"],
                    "configuration": run["configuration"],
                    "run_number": run["run_number"],
                    "attempts": run["integrity"]["attempts"],
                }
            )
        if run["status"] == "invalid":
            invalid_runs.append(
                {
                    "eval_id": run["eval_id"],
                    "configuration": run["configuration"],
                    "run_number": run["run_number"],
                    "reason": run["invalid_reason"],
                }
            )
            continue
        by_eval_config[run["eval_id"]][run["configuration"]].append(run)

    pairs: list[dict[str, Any]] = []
    score_deltas_for_stats: dict[str, list[float]] = {metric: [] for metric in PAIR_SCORE_METRICS}
    efficiency_deltas_for_stats: dict[str, list[float]] = {
        metric: [] for metric in EFFICIENCY_METRICS if metric != "errors_encountered"
    }
    for eval_id in sorted(by_eval_config):
        with_runs = sorted(by_eval_config[eval_id].get("with_skill", []), key=lambda run: run["run_number"])
        without_runs = sorted(by_eval_config[eval_id].get("without_skill", []), key=lambda run: run["run_number"])
        if not with_runs or not without_runs:
            continue
        score_deltas = {
            metric: _score_delta(with_runs, without_runs, measurement_key, kind)
            for metric, (measurement_key, kind) in PAIR_SCORE_METRICS.items()
        }
        for metric, delta in score_deltas.items():
            if delta["status"] == "scored":
                score_deltas_for_stats[metric].append(float(delta["delta"]))
        efficiency_deltas = {
            metric: _metric_delta(with_runs, without_runs, metric)
            for metric in efficiency_deltas_for_stats
        }
        for metric, delta in efficiency_deltas.items():
            efficiency_deltas_for_stats[metric].append(float(delta["delta"]))
        pairs.append(
            {
                "eval_id": eval_id,
                "with_skill_run_numbers": [run["run_number"] for run in with_runs],
                "without_skill_run_numbers": [run["run_number"] for run in without_runs],
                "score_deltas": score_deltas,
                "efficiency_deltas": efficiency_deltas,
            }
        )

    outliers: list[dict[str, Any]] = []
    for configuration in ("with_skill", "without_skill"):
        config_runs = [run for run in valid_runs if run["configuration"] == configuration]
        by_eval: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for run in config_runs:
            by_eval[run["eval_id"]].append(run)
        for metric_name, metric_spec in OUTLIER_METRICS.items():
            series: dict[str, float] = {}
            for eval_id, eval_runs in by_eval.items():
                if isinstance(metric_spec, tuple):
                    mean_score = _mean_run_score(eval_runs, metric_spec[0], metric_spec[1])
                    if mean_score is not None:
                        series[eval_id] = mean_score
                else:
                    series[eval_id] = statistics.fmean(float(run["efficiency"][metric_spec]) for run in eval_runs)
            for eval_id, value, side in _tukey_outliers(series):
                outliers.append(
                    {
                        "configuration": configuration,
                        "metric": metric_name,
                        "eval_id": eval_id,
                        "value": round(value, 4),
                        "side": side,
                    }
                )

    return {
        "pair_unit": "eval_id",
        "pairs": pairs,
        "statistics": {
            "score_metrics": {
                metric: _wilcoxon_signed_rank(deltas)
                for metric, deltas in score_deltas_for_stats.items()
            },
            "efficiency_metrics": {
                metric: _wilcoxon_signed_rank(deltas)
                for metric, deltas in efficiency_deltas_for_stats.items()
            },
        },
        "issues": {
            "invalid_runs": invalid_runs,
            "flaky_runs": flaky_runs,
            "outliers": outliers,
        },
    }


def _grading_surface(grading: dict[str, Any], name: str) -> dict[str, Any]:
    surface = grading.get(name)
    if isinstance(surface, dict):
        return surface
    if name == "llm_graded":
        return {
            "status": "not_requested",
            "expectations": [],
            "summary": {
                "passed": 0,
                "failed": 0,
                "total": 0,
                "pass_rate": 0.0,
                "outcome_passed": 0,
                "outcome_total": 0,
                "outcome_pass_rate": 0.0,
                "process_pass_rate": 0.0,
                "skipped": 0,
            },
        }
    return grading


def _normalize_surface_expectations(
    *,
    eval_id: str,
    surface: dict[str, Any],
    expectation_index: dict[str, dict[str, dict[str, Any]]],
    configuration: str,
) -> list[dict[str, Any]]:
    return [
        _normalize_expectation(
            eval_id=eval_id,
            entry=entry,
            expectation_index=expectation_index,
            configuration=configuration,
        )
        for entry in surface.get("expectations", []) or []
        if isinstance(entry, dict)
    ]


def _surface_payload(
    *,
    source: dict[str, Any],
    expectations: list[dict[str, Any]],
    fallback_execution: dict[str, Any] | None = None,
    fallback_timing: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload = {
        "status": str(source.get("status") or "graded"),
        "expectations": expectations,
        "summary": source.get("summary", {}) or {},
        "measurements": _measurements(expectations),
    }
    execution_metrics = source.get("execution_metrics") or fallback_execution
    timing = source.get("timing") or fallback_timing
    if isinstance(execution_metrics, dict):
        payload["execution_metrics"] = execution_metrics
    if isinstance(timing, dict):
        payload["timing"] = timing
    for key in ("artifacts", "identity", "tokens", "tool_metrics", "permission_metrics", "build_test_iterations", "repo_state", "harness_validation", "rubric_results"):
        value = source.get(key)
        if value not in (None, {}):
            payload[key] = value
    return payload


def aggregate(
    iteration_dir: Path,
    *,
    skill_name: str,
    bindings_path: Path | None,
    catalog_path: Path | None,
) -> dict[str, Any]:
    bindings = _read_json(bindings_path)
    catalog = _read_json(catalog_path)
    expectation_index = _build_expectation_index(catalog)
    eval_ids = sorted({eval_id for eval_id, _, _, _ in _iter_runs(iteration_dir)})
    seen_configurations = {
        configuration for _, configuration, _, _ in _iter_runs(iteration_dir)
    }
    configurations = [
        configuration for configuration in CONFIGURATION_ORDER if configuration in seen_configurations
    ] or sorted(seen_configurations)

    runs: list[dict[str, Any]] = []
    by_configuration: dict[str, list[dict[str, Any]]] = {configuration: [] for configuration in configurations}
    for eval_id, configuration, run_number, run_dir in _iter_runs(iteration_dir):
        grading = _read_json(run_dir / "grading.json")
        if not grading:
            continue
        mechanical_source = _grading_surface(grading, "mechanical")
        llm_source = _grading_surface(grading, "llm_graded")
        combined_source = _grading_surface(grading, "combined")
        mechanical_expectations = _normalize_surface_expectations(
            eval_id=eval_id,
            surface=mechanical_source,
            expectation_index=expectation_index,
            configuration=configuration,
        )
        llm_expectations = _normalize_surface_expectations(
            eval_id=eval_id,
            surface=llm_source,
            expectation_index=expectation_index,
            configuration=configuration,
        )
        combined_expectations = _normalize_surface_expectations(
            eval_id=eval_id,
            surface=combined_source,
            expectation_index=expectation_index,
            configuration=configuration,
        )
        if not combined_expectations:
            combined_expectations = [*mechanical_expectations, *llm_expectations]
        integrity = _integrity(mechanical_source if "integrity" in mechanical_source else grading, configuration)
        mechanical_payload = _surface_payload(
            source=mechanical_source,
            expectations=mechanical_expectations,
        )
        llm_payload = _surface_payload(
            source=llm_source,
            expectations=llm_expectations,
        )
        combined_payload = _surface_payload(
            source=combined_source if combined_source is not grading else {"status": grading.get("status"), "summary": grading.get("summary")},
            expectations=combined_expectations,
            fallback_execution=mechanical_payload.get("execution_metrics"),
            fallback_timing=mechanical_payload.get("timing"),
        )
        record: dict[str, Any] = {
            "eval_id": eval_id,
            "configuration": configuration,
            "run_number": run_number,
            "mechanical": mechanical_payload,
            "llm_graded": llm_payload,
            "combined": combined_payload,
            "expectations": combined_expectations,
            "efficiency": _run_efficiency(mechanical_source if "execution_metrics" in mechanical_source else grading),
            "integrity": integrity,
        }
        invalid_reason = _invalid_reason(combined_expectations, integrity)
        if invalid_reason is None:
            record["status"] = "valid"
            record["measurements"] = combined_payload["measurements"]
        else:
            record["status"] = "invalid"
            record["invalid_reason"] = invalid_reason
        runs.append(record)
        if configuration in by_configuration:
            by_configuration[configuration].append(record)

    target_repo = str(bindings.get("target_repo", "")).strip()
    if not target_repo:
        workspace_root = str(bindings.get("workspace_root", "")).strip()
        target_repo = Path(workspace_root).name if workspace_root else skill_name

    return {
        "$schema": "https://github.com/amichne/kast/evaluation/benchmark.schema.json",
        "schema_version": 2,
        "benchmark_kind": "kast-system-performance-benchmark",
        "metadata": {
            "skill_name": skill_name,
            "skill_path": "evaluation",
            "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "iteration_dir": str(iteration_dir),
            "target_repo": target_repo,
            "workspace_root": str(bindings.get("workspace_root", "") or iteration_dir),
            **(
                {"target_git_sha": str(bindings["git_sha"]).strip()}
                if str(bindings.get("git_sha", "")).strip()
                else {}
            ),
            "eval_ids": eval_ids,
            "configurations": configurations,
            "runs_per_eval_per_config": {
                eval_id: {
                    configuration: sum(
                        1
                        for run in runs
                        if run["eval_id"] == eval_id and run["configuration"] == configuration
                    )
                    for configuration in configurations
                }
                for eval_id in eval_ids
            },
            "catalog_version": int(catalog.get("version", 1) or 1),
            "primary_dimensions": list(PRIMARY_DIMENSIONS),
            "supporting_metrics": [
                "efficiency",
                "transcript_chars",
                "total_tool_calls",
                "semantic_tool_calls",
                "generic_search_calls",
                "executor_duration_seconds",
                "errors_encountered",
            ],
            "execution_environment": {
                "platform": platform.platform(),
                "python_version": platform.python_version(),
                "cpu_count": int(os.cpu_count() or 1),
            },
        },
        "runs": runs,
        "mechanical_summary": {
            "by_configuration": {
                configuration: _configuration_summary(by_configuration[configuration], surface="mechanical")
                for configuration in configurations
            }
        },
        "llm_graded_summary": {
            "by_configuration": {
                configuration: _configuration_summary(by_configuration[configuration], surface="llm_graded")
                for configuration in configurations
            }
        },
        "combined_summary": {
            "by_configuration": {
                configuration: _configuration_summary(by_configuration[configuration], surface="combined")
                for configuration in configurations
            }
        },
        "summary": {
            "by_configuration": {
                configuration: _configuration_summary(by_configuration[configuration], surface="combined")
                for configuration in configurations
            }
        },
        "paired_analysis": _paired_analysis(runs),
    }


def _summary_mean(benchmark: dict[str, Any], configuration: str, measurement_key: str, kind: str) -> float | None:
    summary = benchmark["summary"]["by_configuration"][configuration]["measurements"][measurement_key][kind]
    if summary["status"] != "summarized":
        return None
    return float(summary["mean"])


def _section_summary_mean(
    benchmark: dict[str, Any],
    section: str,
    configuration: str,
    measurement_key: str,
    kind: str = "outcome",
) -> float | None:
    summary = (
        benchmark.get(section, {})
        .get("by_configuration", {})
        .get(configuration, {})
        .get("measurements", {})
        .get(measurement_key, {})
        .get(kind, {})
    )
    if summary.get("status") != "summarized":
        return None
    return float(summary["mean"])


def _format_optional_mean(value: float | None) -> str:
    return f"{value:.3f}" if value is not None else "n/a"


def _efficiency_mean(benchmark: dict[str, Any], configuration: str, metric: str) -> float | None:
    summary = benchmark["summary"]["by_configuration"][configuration]["efficiency"][metric]
    if summary["status"] != "summarized":
        return None
    return float(summary["mean"])


def write_outputs(iteration_dir: Path, benchmark: dict[str, Any]) -> None:
    (iteration_dir / "benchmark.json").write_text(json.dumps(benchmark, indent=2) + "\n")

    lines = [
        f"# Benchmark: {benchmark['metadata']['skill_name']} — {benchmark['metadata']['target_repo']}",
        "",
        f"_iteration: `{Path(benchmark['metadata']['iteration_dir']).name}`, evals: {len(benchmark['metadata']['eval_ids'])}_",
        "",
        "This report preserves separate `mechanical_summary`, `llm_graded_summary`, and `combined_summary` surfaces.",
        "",
        "## Combined primary dimensions",
        "",
        "| Dimension | with_skill | without_skill | Delta | p-value |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    paired_scores = benchmark["paired_analysis"]["statistics"]["score_metrics"]
    for metric, measurement_key in (
        ("task_completion", "task_completion"),
        ("accuracy", "accuracy"),
        ("reliability", "reliability"),
        ("scope_control", "scope_control"),
    ):
        with_mean = _summary_mean(benchmark, "with_skill", measurement_key, "outcome")
        without_mean = _summary_mean(benchmark, "without_skill", measurement_key, "outcome")
        stats = paired_scores[metric]
        delta = "n/a"
        if with_mean is not None and without_mean is not None:
            delta = f"{with_mean - without_mean:+.3f}"
        p_value = f"{stats['p_value']:.3f}" if stats["status"] == "scored" else "n/a"
        with_value = f"{with_mean:.3f}" if with_mean is not None else "n/a"
        without_value = f"{without_mean:.3f}" if without_mean is not None else "n/a"
        lines.append(f"| {metric} | {with_value} | {without_value} | {delta} | {p_value} |")

    lines.extend(
        [
            "",
            "## Surface split",
            "",
            "| Surface | with_skill overall outcome | tool_only overall outcome | without_skill overall outcome |",
            "| --- | ---: | ---: | ---: |",
        ]
    )
    for section, label in (
        ("mechanical_summary", "Mechanical"),
        ("llm_graded_summary", "LLM-graded"),
        ("combined_summary", "Combined"),
    ):
        with_mean = _section_summary_mean(benchmark, section, "with_skill", "overall")
        tool_only_mean = _section_summary_mean(benchmark, section, "tool_only", "overall")
        without_mean = _section_summary_mean(benchmark, section, "without_skill", "overall")
        lines.append(
            f"| {label} | "
            f"{_format_optional_mean(with_mean)} | "
            f"{_format_optional_mean(tool_only_mean)} | "
            f"{_format_optional_mean(without_mean)} |"
        )

    lines.extend(
        [
            "",
            "## Supporting efficiency",
            "",
            "| Metric | with_skill | without_skill | Delta |",
            "| --- | ---: | ---: | ---: |",
        ]
    )
    for metric in (
        "transcript_chars",
        "total_tool_calls",
        "semantic_tool_calls",
        "generic_search_calls",
        "executor_duration_seconds",
    ):
        with_mean = _efficiency_mean(benchmark, "with_skill", metric)
        without_mean = _efficiency_mean(benchmark, "without_skill", metric)
        delta = "n/a"
        if with_mean is not None and without_mean is not None:
            delta = f"{with_mean - without_mean:+.3f}"
        with_value = f"{with_mean:.3f}" if with_mean is not None else "n/a"
        without_value = f"{without_mean:.3f}" if without_mean is not None else "n/a"
        lines.append(f"| {metric} | {with_value} | {without_value} | {delta} |")

    issues = benchmark["paired_analysis"]["issues"]
    if issues["invalid_runs"]:
        lines.extend(["", "## Invalid runs", ""])
        for run in issues["invalid_runs"]:
            lines.append(
                f"- `{run['eval_id']}` {run['configuration']} run {run['run_number']} → {run['reason']}"
            )

    if issues["flaky_runs"]:
        lines.extend(["", "## Flaky runs", ""])
        for run in issues["flaky_runs"]:
            lines.append(
                f"- `{run['eval_id']}` {run['configuration']} run {run['run_number']} attempts={run['attempts']}"
            )

    if issues["outliers"]:
        lines.extend(["", "## Outliers", ""])
        for outlier in issues["outliers"]:
            lines.append(
                f"- `{outlier['eval_id']}` {outlier['configuration']} {outlier['metric']}={outlier['value']} ({outlier['side']})"
            )

    (iteration_dir / "benchmark.md").write_text("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Aggregate evaluation results into the benchmark contract.")
    parser.add_argument("iteration_dir", type=Path)
    parser.add_argument("--skill-name", default="kast-value-proof")
    parser.add_argument("--bindings", type=Path, default=None)
    parser.add_argument("--catalog", type=Path, default=None)
    args = parser.parse_args()

    if not args.iteration_dir.exists():
        print(f"error: iteration directory missing: {args.iteration_dir}", file=sys.stderr)
        return 2

    benchmark = aggregate(
        args.iteration_dir,
        skill_name=args.skill_name,
        bindings_path=args.bindings,
        catalog_path=args.catalog,
    )
    write_outputs(args.iteration_dir, benchmark)
    print(f"wrote {args.iteration_dir / 'benchmark.json'}")
    print(f"wrote {args.iteration_dir / 'benchmark.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
