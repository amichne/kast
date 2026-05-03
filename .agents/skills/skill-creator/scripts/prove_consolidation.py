#!/usr/bin/env python3
"""Prove whether a consolidated skill is non-regressive against legacy siblings."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

try:
    from .validation import load_benchmark
except ImportError:
    from validation import load_benchmark

EPSILON = 1e-9


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare a consolidated skill configuration against one or more legacy sibling "
            "configurations and produce a proof report."
        )
    )
    parser.add_argument("--benchmark", required=True, help="Path to benchmark.json")
    parser.add_argument(
        "--candidate-config",
        required=True,
        help="Configuration name for the consolidated skill candidate.",
    )
    parser.add_argument(
        "--baseline-config",
        dest="baseline_configs",
        action="append",
        required=True,
        help="Legacy configuration to compare against. Repeat for multiple legacy skills.",
    )
    parser.add_argument(
        "--max-pass-rate-regression",
        type=float,
        default=0.0,
        help="Allowed per-eval pass-rate regression against the legacy envelope.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Output path for consolidation_report.json (defaults beside benchmark.json).",
    )
    return parser.parse_args()


def mean_or_zero(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def build_config_case_results(benchmark: dict[str, Any]) -> dict[str, dict[str, float]]:
    grouped: dict[str, dict[str, list[float]]] = {}
    for run in benchmark.get("runs", []):
        config = str(run.get("configuration"))
        eval_id = str(run.get("eval_id"))
        pass_rate = float(run.get("result", {}).get("pass_rate", 0.0))
        grouped.setdefault(config, {}).setdefault(eval_id, []).append(pass_rate)
    return {
        config: {
            eval_id: round(mean_or_zero(pass_rates), 4)
            for eval_id, pass_rates in eval_results.items()
        }
        for config, eval_results in grouped.items()
    }


def _summary_metric(
    benchmark: dict[str, Any],
    configuration: str,
    metric: str,
) -> float:
    return float(
        benchmark.get("run_summary", {})
        .get(configuration, {})
        .get(metric, {})
        .get("mean", 0.0)
    )


def build_consolidation_report(
    benchmark: dict[str, Any],
    *,
    candidate_config: str,
    baseline_configs: list[str],
    max_pass_rate_regression: float = 0.0,
) -> dict[str, Any]:
    config_case_results = build_config_case_results(benchmark)
    missing = [
        config
        for config in [candidate_config, *baseline_configs]
        if config not in config_case_results
    ]
    if missing:
        rendered = ", ".join(sorted(missing))
        raise ValueError(f"Missing configuration(s) in benchmark.json: {rendered}")

    candidate_results = config_case_results[candidate_config]
    all_eval_ids = sorted(
        {
            *candidate_results.keys(),
            *{
                eval_id
                for baseline in baseline_configs
                for eval_id in config_case_results[baseline]
            },
        }
    )

    case_results: list[dict[str, Any]] = []
    improvement_count = 0
    matched_count = 0
    regression_count = 0
    legacy_envelope_values: list[float] = []
    candidate_values: list[float] = []

    for eval_id in all_eval_ids:
        baseline_pass_rates = {
            baseline: config_case_results[baseline].get(eval_id, 0.0)
            for baseline in baseline_configs
        }
        best_baseline_config = max(
            baseline_pass_rates,
            key=lambda baseline: baseline_pass_rates[baseline],
        )
        legacy_envelope = baseline_pass_rates[best_baseline_config]
        candidate_pass_rate = candidate_results.get(eval_id, 0.0)
        delta = candidate_pass_rate - legacy_envelope

        if delta > EPSILON:
            status = "improved"
            improvement_count += 1
        elif delta + max_pass_rate_regression >= -EPSILON:
            status = "matched"
            matched_count += 1
        else:
            status = "regressed"
            regression_count += 1

        legacy_envelope_values.append(legacy_envelope)
        candidate_values.append(candidate_pass_rate)
        case_results.append(
            {
                "eval_id": eval_id,
                "candidate_pass_rate": round(candidate_pass_rate, 4),
                "legacy_envelope_pass_rate": round(legacy_envelope, 4),
                "best_legacy_configuration": best_baseline_config,
                "baseline_pass_rates": {
                    baseline: round(pass_rate, 4)
                    for baseline, pass_rate in baseline_pass_rates.items()
                },
                "delta_vs_legacy_envelope": f"{delta:+.2f}",
                "status": status,
            }
        )

    candidate_mean_pass_rate = round(mean_or_zero(candidate_values), 4)
    legacy_envelope_mean_pass_rate = round(mean_or_zero(legacy_envelope_values), 4)
    average_legacy_mean_pass_rate = round(
        mean_or_zero(
            [
                _summary_metric(benchmark, baseline, "pass_rate")
                for baseline in baseline_configs
            ]
        ),
        4,
    )

    candidate_time_seconds_mean = round(
        _summary_metric(benchmark, candidate_config, "time_seconds"),
        4,
    )
    candidate_tokens_mean = round(_summary_metric(benchmark, candidate_config, "tokens"), 4)
    average_legacy_time_seconds_mean = round(
        mean_or_zero(
            [
                _summary_metric(benchmark, baseline, "time_seconds")
                for baseline in baseline_configs
            ]
        ),
        4,
    )
    average_legacy_tokens_mean = round(
        mean_or_zero(
            [_summary_metric(benchmark, baseline, "tokens") for baseline in baseline_configs]
        ),
        4,
    )

    supported = regression_count == 0
    reasons = []
    if supported:
        reasons.append(
            "The consolidated candidate matched or exceeded the best legacy pass-rate envelope on every eval."
        )
    else:
        reasons.append(
            f"The consolidated candidate regressed on {regression_count} eval(s) against the legacy envelope."
        )
    reasons.append(
        f"Candidate mean pass rate {candidate_mean_pass_rate:.2f} vs legacy envelope {legacy_envelope_mean_pass_rate:.2f}."
    )
    if candidate_time_seconds_mean > average_legacy_time_seconds_mean + EPSILON:
        reasons.append(
            f"Candidate is slower on average ({candidate_time_seconds_mean:.2f}s vs {average_legacy_time_seconds_mean:.2f}s)."
        )
    elif candidate_time_seconds_mean + EPSILON < average_legacy_time_seconds_mean:
        reasons.append(
            f"Candidate is faster on average ({candidate_time_seconds_mean:.2f}s vs {average_legacy_time_seconds_mean:.2f}s)."
        )

    return {
        "skill_path": benchmark.get("metadata", {}).get("skill_path", ""),
        "candidate_configuration": candidate_config,
        "baseline_configurations": baseline_configs,
        "max_pass_rate_regression": max_pass_rate_regression,
        "consolidation_supported": supported,
        "verdict": "supported" if supported else "not_supported",
        "summary": {
            "evaluated_cases": len(case_results),
            "improved_cases": improvement_count,
            "matched_cases": matched_count,
            "regressed_cases": regression_count,
            "candidate_mean_pass_rate": candidate_mean_pass_rate,
            "legacy_envelope_mean_pass_rate": legacy_envelope_mean_pass_rate,
            "average_legacy_mean_pass_rate": average_legacy_mean_pass_rate,
            "delta_vs_legacy_envelope": f"{candidate_mean_pass_rate - legacy_envelope_mean_pass_rate:+.2f}",
            "delta_vs_average_legacy": f"{candidate_mean_pass_rate - average_legacy_mean_pass_rate:+.2f}",
            "candidate_time_seconds_mean": candidate_time_seconds_mean,
            "average_legacy_time_seconds_mean": average_legacy_time_seconds_mean,
            "candidate_tokens_mean": candidate_tokens_mean,
            "average_legacy_tokens_mean": average_legacy_tokens_mean,
        },
        "case_results": case_results,
        "reasons": reasons,
    }


def generate_markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# Consolidation Report",
        "",
        f"**Verdict**: {report['verdict']}",
        f"**Candidate**: `{report['candidate_configuration']}`",
        f"**Legacy configurations**: {', '.join(f'`{config}`' for config in report['baseline_configurations'])}",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "| --- | --- |",
        f"| Evaluated cases | {summary['evaluated_cases']} |",
        f"| Improved cases | {summary['improved_cases']} |",
        f"| Matched cases | {summary['matched_cases']} |",
        f"| Regressed cases | {summary['regressed_cases']} |",
        f"| Candidate mean pass rate | {summary['candidate_mean_pass_rate']:.2f} |",
        f"| Legacy envelope mean pass rate | {summary['legacy_envelope_mean_pass_rate']:.2f} |",
        f"| Average legacy mean pass rate | {summary['average_legacy_mean_pass_rate']:.2f} |",
        f"| Delta vs legacy envelope | {summary['delta_vs_legacy_envelope']} |",
        f"| Delta vs average legacy | {summary['delta_vs_average_legacy']} |",
        "",
        "## Reasons",
        "",
    ]
    lines.extend(f"- {reason}" for reason in report["reasons"])
    lines.extend(
        [
            "",
            "## Per-eval results",
            "",
            "| Eval | Candidate | Legacy envelope | Best legacy | Delta | Status |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
    )
    for case in report["case_results"]:
        lines.append(
            f"| `{case['eval_id']}` | {case['candidate_pass_rate']:.2f} | "
            f"{case['legacy_envelope_pass_rate']:.2f} | `{case['best_legacy_configuration']}` | "
            f"{case['delta_vs_legacy_envelope']} | {case['status']} |"
        )
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    benchmark_path = Path(args.benchmark).expanduser().resolve()
    try:
        benchmark = load_benchmark(benchmark_path)
        report = build_consolidation_report(
            benchmark,
            candidate_config=args.candidate_config,
            baseline_configs=args.baseline_configs,
            max_pass_rate_regression=args.max_pass_rate_regression,
        )
    except ValueError as exc:
        print(exc)
        sys.exit(1)

    output_json = (
        Path(args.output).expanduser().resolve()
        if args.output
        else benchmark_path.with_name("consolidation_report.json")
    )
    output_md = output_json.with_suffix(".md")
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(report, indent=2) + "\n")
    output_md.write_text(generate_markdown(report) + "\n")

    print(f"{report['verdict'].upper()}: {output_json}")
    for reason in report["reasons"]:
        print(f"- {reason}")


if __name__ == "__main__":
    main()
