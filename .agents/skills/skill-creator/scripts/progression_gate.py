#!/usr/bin/env python3
"""Apply non-regression gates and promote eval cases through the suite."""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from .validation import (
        format_report,
        load_benchmark,
        load_catalog,
        load_progression,
        validate_catalog_data,
        validate_progression_data,
    )
except ImportError:
    from validation import (
        format_report,
        load_benchmark,
        load_catalog,
        load_progression,
        validate_catalog_data,
        validate_progression_data,
    )

EPSILON = 1e-9
STAGES = ("candidate", "holdout", "core", "retired")


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check a benchmark against the last accepted result and promote cases when justified.",
    )
    parser.add_argument("--catalog", required=True, help="Path to evals/catalog.json")
    parser.add_argument("--benchmark", required=True, help="Path to benchmark.json")
    parser.add_argument("--history", required=True, help="Path to history/progression.json")
    parser.add_argument(
        "--primary-config",
        default=None,
        help="Configuration name to judge (defaults to the first configuration in benchmark.json)",
    )
    return parser.parse_args()

def mean_or_zero(values: list[float]) -> float:
    return statistics.fmean(values) if values else 0.0


def infer_primary_config(benchmark: dict[str, Any], explicit: str | None) -> str:
    if explicit:
        return explicit
    summary = benchmark.get("run_summary", {})
    for key in summary:
        if key != "delta":
            return key
    raise ValueError("Could not infer primary configuration from benchmark.json")


def build_case_results(benchmark: dict[str, Any], primary_config: str) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[float]] = {}
    for run in benchmark.get("runs", []):
        if run.get("configuration") != primary_config:
            continue
        case_id = str(run.get("eval_id"))
        grouped.setdefault(case_id, []).append(float(run.get("result", {}).get("pass_rate", 0.0)))
    return {
        case_id: {
            "mean_pass_rate": mean_or_zero(values),
            "run_count": len(values),
        }
        for case_id, values in grouped.items()
    }


def summarize_stage(cases: list[dict[str, Any]], results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    pass_rates = [results.get(str(case["id"]), {}).get("mean_pass_rate", 0.0) for case in cases]
    return {
        "count": len(cases),
        "mean_pass_rate": mean_or_zero(pass_rates),
        "min_pass_rate": min(pass_rates) if pass_rates else 0.0,
    }


def next_stage(stage: str) -> str | None:
    if stage == "candidate":
        return "holdout"
    if stage == "holdout":
        return "core"
    return None


def ensure_history(history: dict[str, Any], skill_name: str) -> dict[str, Any]:
    history.setdefault("skill_name", skill_name)
    history.setdefault("updated_at", utc_now())
    history.setdefault("benchmarks", [])
    history.setdefault("case_history", {})
    return history


def last_accepted_record(history: dict[str, Any]) -> dict[str, Any] | None:
    accepted = [record for record in history.get("benchmarks", []) if record.get("accepted")]
    return accepted[-1] if accepted else None


def default_case_history(case: dict[str, Any]) -> dict[str, Any]:
    return {
        "stage": case.get("stage", "candidate"),
        "qualifying_streak": 0,
        "last_pass_rate": 0.0,
        "accepted_pass_rate": None,
        "last_accepted_benchmark": None,
    }


def main() -> None:
    args = parse_args()
    catalog_path = Path(args.catalog).expanduser().resolve()
    benchmark_path = Path(args.benchmark).expanduser().resolve()
    history_path = Path(args.history).expanduser().resolve()

    skill_dir = catalog_path.parent.parent
    try:
        catalog = load_catalog(catalog_path, skill_dir=skill_dir)
        expected_skill_name = str(catalog.get("skill_name", "")).strip() or None
        benchmark = load_benchmark(benchmark_path, expected_skill_name=expected_skill_name)
        history = ensure_history(
            load_progression(history_path, expected_skill_name=expected_skill_name)
            if history_path.exists()
            else {},
            catalog.get("skill_name", "<unknown-skill>"),
        )
    except ValueError as exc:
        print(exc)
        sys.exit(1)

    cases = catalog.get("cases", [])
    case_by_id = {str(case.get("id")): case for case in cases}
    primary_config = infer_primary_config(benchmark, args.primary_config)
    results = build_case_results(benchmark, primary_config)
    previous = last_accepted_record(history)

    stage_buckets: dict[str, list[dict[str, Any]]] = {stage: [] for stage in STAGES}
    for case in cases:
        stage_buckets.setdefault(case.get("stage", "candidate"), []).append(case)

    stage_summary = {
        stage: summarize_stage(stage_buckets.get(stage, []), results)
        for stage in ("candidate", "holdout", "core")
    }

    accepted = True
    reasons: list[str] = []

    for case in stage_buckets.get("core", []):
        case_id = str(case["id"])
        current = results.get(case_id, {}).get("mean_pass_rate", 0.0)
        previous_pass = history["case_history"].get(case_id, {}).get("accepted_pass_rate")
        if previous_pass is not None and current + EPSILON < previous_pass:
            accepted = False
            reasons.append(
                f"Core case '{case_id}' regressed from {previous_pass:.2f} to {current:.2f}."
            )

    if previous and stage_buckets.get("holdout"):
        previous_holdout = previous.get("stage_summary", {}).get("holdout", {}).get("mean_pass_rate")
        current_holdout = stage_summary["holdout"]["mean_pass_rate"]
        if previous_holdout is not None and current_holdout + EPSILON < previous_holdout:
            accepted = False
            reasons.append(
                f"Holdout mean pass rate regressed from {previous_holdout:.2f} to {current_holdout:.2f}."
            )

    if not reasons:
        reasons.append("Accepted: core cases and holdout coverage did not regress.")

    promotions: list[dict[str, Any]] = []
    case_history = history["case_history"]

    for case_id, case in case_by_id.items():
        metrics = results.get(case_id, {"mean_pass_rate": 0.0, "run_count": 0})
        record = case_history.setdefault(case_id, default_case_history(case))
        record["stage"] = case.get("stage", record.get("stage", "candidate"))
        record["last_pass_rate"] = metrics["mean_pass_rate"]

        required_pass_rate = float(case.get("promotion", {}).get("required_pass_rate", 1.0))
        required_benchmarks = int(case.get("promotion", {}).get("required_benchmarks", 2))

        if accepted and metrics["mean_pass_rate"] + EPSILON >= required_pass_rate:
            record["qualifying_streak"] = int(record.get("qualifying_streak", 0)) + 1
            record["accepted_pass_rate"] = metrics["mean_pass_rate"]
            record["last_accepted_benchmark"] = str(benchmark_path)
        else:
            record["qualifying_streak"] = 0

        promoted_stage = next_stage(case.get("stage", "candidate"))
        if (
            accepted
            and promoted_stage
            and record["qualifying_streak"] >= required_benchmarks
        ):
            promotions.append(
                {
                    "case_id": case_id,
                    "from": case.get("stage", "candidate"),
                    "to": promoted_stage,
                    "pass_rate": metrics["mean_pass_rate"],
                }
            )
            case["stage"] = promoted_stage
            record["stage"] = promoted_stage
            record["qualifying_streak"] = 0

    benchmark_record = {
        "benchmark_path": str(benchmark_path),
        "timestamp": benchmark.get("metadata", {}).get("timestamp", utc_now()),
        "primary_configuration": primary_config,
        "accepted": accepted,
        "reasons": reasons,
        "stage_summary": stage_summary,
        "promotions": promotions,
    }
    history["benchmarks"].append(benchmark_record)
    history["updated_at"] = utc_now()

    catalog_validation = validate_catalog_data(
        catalog,
        path=catalog_path,
        skill_dir=skill_dir,
        expected_skill_name=expected_skill_name,
    )
    history_validation = validate_progression_data(
        history,
        path=history_path,
        expected_skill_name=expected_skill_name,
    )
    if catalog_validation.errors or history_validation.errors:
        if catalog_validation.errors:
            print(format_report(catalog_validation, f"{catalog_path} is valid."))
        if catalog_validation.errors and history_validation.errors:
            print()
        if history_validation.errors:
            print(format_report(history_validation, f"{history_path} is valid."))
        sys.exit(1)

    catalog_path.write_text(json.dumps(catalog, indent=2) + "\n")
    history_path.parent.mkdir(parents=True, exist_ok=True)
    history_path.write_text(json.dumps(history, indent=2) + "\n")

    verdict = "ACCEPTED" if accepted else "REJECTED"
    print(f"{verdict}: {benchmark_path}")
    for reason in reasons:
        print(f"- {reason}")
    if promotions:
        print("- Promotions:")
        for promotion in promotions:
            print(
                f"  - {promotion['case_id']}: {promotion['from']} -> {promotion['to']} "
                f"(pass_rate={promotion['pass_rate']:.2f})"
            )


if __name__ == "__main__":
    main()
