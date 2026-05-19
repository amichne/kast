#!/usr/bin/env python3
"""Build compact, gist-ready summaries from benchmark.json artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import UTC, datetime
from itertools import combinations
from pathlib import Path
from typing import Any, Iterable

from generate_executive_summary import CATEGORIES
from value_proof_aggregate import _distribution, _wilcoxon_signed_rank

CONFIGURATION_ORDER = ("without_skill", "skill_only", "tool_only", "with_skill")
SCORE_METRICS = {
    "overall_outcome": ("overall", "outcome"),
    "task_completion": ("task_completion", "outcome"),
    "accuracy": ("accuracy", "outcome"),
    "reliability": ("reliability", "outcome"),
    "scope_control": ("scope_control", "outcome"),
}
EFFICIENCY_METRICS = (
    "total_tokens",
    "transcript_chars",
    "total_tool_calls",
    "semantic_tool_calls",
    "generic_search_calls",
    "executor_duration_seconds",
)
FOUR_SCENARIO_CONFIGS = ("without_skill", "skill_only", "tool_only", "with_skill")
PAIRWISE_COMPARISONS = (
    ("with_skill", "without_skill"),
    ("tool_only", "without_skill"),
    ("skill_only", "without_skill"),
    ("with_skill", "tool_only"),
    ("with_skill", "skill_only"),
    ("tool_only", "skill_only"),
)


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def resolve_benchmark_path(path: Path) -> Path:
    if path.is_dir():
        return path / "benchmark.json"
    return path


def discover_benchmark_paths(paths: Iterable[Path]) -> list[Path]:
    discovered: list[Path] = []
    for path in paths:
        resolved = path.resolve()
        if resolved.is_dir() and not (resolved / "benchmark.json").exists():
            discovered.extend(sorted(resolved.glob("**/benchmark.json")))
        else:
            discovered.append(resolve_benchmark_path(resolved))
    unique: dict[str, Path] = {}
    for path in discovered:
        if path.exists():
            unique[str(path.resolve())] = path
    return list(unique.values())


def benchmark_id(path: Path, benchmark: dict[str, Any]) -> str:
    iteration_dir = str(benchmark.get("metadata", {}).get("iteration_dir") or "").rstrip("/")
    if iteration_dir:
        return Path(iteration_dir).name
    return path.parent.name


def ordered_configurations(benchmark: dict[str, Any]) -> list[str]:
    metadata_configs = [
        str(config)
        for config in benchmark.get("metadata", {}).get("configurations", [])
        if str(config)
    ]
    seen = set(metadata_configs)
    for run in benchmark.get("runs", []) or []:
        config = str(run.get("configuration") or "")
        if config:
            seen.add(config)
    ordered = [config for config in CONFIGURATION_ORDER if config in seen]
    ordered.extend(sorted(seen - set(ordered)))
    return ordered


def valid_runs(benchmark: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        run
        for run in benchmark.get("runs", []) or []
        if isinstance(run, dict) and run.get("status") == "valid"
    ]


def run_counts(runs: list[dict[str, Any]]) -> dict[str, int]:
    valid = sum(1 for run in runs if run.get("status") == "valid")
    total = len(runs)
    return {"total": total, "valid": valid, "invalid": total - valid}


def score_value(run: dict[str, Any], metric: str) -> float | None:
    measurement_key, kind = SCORE_METRICS[metric]
    surfaces = [run.get("combined"), run.get("mechanical"), run]
    for surface in surfaces:
        if not isinstance(surface, dict):
            continue
        score = (
            surface.get("measurements", {})
            .get(measurement_key, {})
            .get(kind, {})
        )
        if isinstance(score, dict) and score.get("status") == "scored":
            return float(score["score"])
    return None


def efficiency_value(run: dict[str, Any], metric: str) -> float | None:
    efficiency = run.get("efficiency")
    if not isinstance(efficiency, dict) or metric not in efficiency:
        return None
    value = efficiency[metric]
    if isinstance(value, (int, float)):
        return float(value)
    return None


def config_summary(benchmark: dict[str, Any], configuration: str) -> dict[str, Any]:
    runs = [
        run
        for run in benchmark.get("runs", []) or []
        if isinstance(run, dict) and run.get("configuration") == configuration
    ]
    valid = [run for run in runs if run.get("status") == "valid"]
    return {
        "run_counts": run_counts(runs),
        "scores": {
            metric: _distribution(
                [value for run in valid if (value := score_value(run, metric)) is not None],
                lower_bound=0.0,
                upper_bound=1.0,
            )
            for metric in SCORE_METRICS
        },
        "efficiency": {
            metric: _distribution(
                [value for run in valid if (value := efficiency_value(run, metric)) is not None],
                lower_bound=0.0,
            )
            for metric in EFFICIENCY_METRICS
        },
    }


def summarized_mean(summary: dict[str, Any], section: str, metric: str) -> float | None:
    payload = summary.get(section, {}).get(metric, {})
    if payload.get("status") != "summarized":
        return None
    return float(payload["mean"])


def delta_vs_baseline(by_configuration: dict[str, Any], baseline: str) -> dict[str, dict[str, float]]:
    baseline_summary = by_configuration.get(baseline)
    if not baseline_summary:
        return {}
    deltas: dict[str, dict[str, float]] = {}
    for configuration, summary in by_configuration.items():
        if configuration == baseline:
            continue
        metric_deltas: dict[str, float] = {}
        for metric in SCORE_METRICS:
            left = summarized_mean(summary, "scores", metric)
            right = summarized_mean(baseline_summary, "scores", metric)
            if left is not None and right is not None:
                metric_deltas[metric] = round(left - right, 4)
        for metric in EFFICIENCY_METRICS:
            left = summarized_mean(summary, "efficiency", metric)
            right = summarized_mean(baseline_summary, "efficiency", metric)
            if left is not None and right is not None:
                metric_deltas[metric] = round(left - right, 4)
        deltas[configuration] = metric_deltas
    return deltas


def invalid_reason_counts(benchmark: dict[str, Any]) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for run in benchmark.get("runs", []) or []:
        if isinstance(run, dict) and run.get("status") == "invalid":
            counter[str(run.get("invalid_reason") or "unknown")] += 1
    issues = benchmark.get("paired_analysis", {}).get("issues", {})
    for run in issues.get("invalid_runs", []) or []:
        reason = str(run.get("reason") or "unknown")
        counter.setdefault(reason, 0)
    return dict(sorted(counter.items()))


def category_coverage(eval_ids: Iterable[str]) -> dict[str, Any]:
    eval_set = set(eval_ids)
    categories: dict[str, Any] = {}
    for category, ids in CATEGORIES.items():
        covered = sorted(eval_set & ids)
        categories[category] = {
            "covered": covered,
            "covered_count": len(covered),
            "total_count": len(ids),
        }
    return categories


def summarize_one(path: Path, benchmark: dict[str, Any]) -> dict[str, Any]:
    configs = ordered_configurations(benchmark)
    runs = benchmark.get("runs", []) or []
    eval_ids = sorted(
        set(benchmark.get("metadata", {}).get("eval_ids", []) or [])
        | {str(run.get("eval_id")) for run in runs if isinstance(run, dict) and run.get("eval_id")}
    )
    by_configuration = {configuration: config_summary(benchmark, configuration) for configuration in configs}
    counts = run_counts([run for run in runs if isinstance(run, dict)])
    return {
        "benchmark_id": benchmark_id(path, benchmark),
        "path": path.as_posix(),
        "generated_at": benchmark.get("metadata", {}).get("generated_at"),
        "target_git_sha": benchmark.get("metadata", {}).get("target_git_sha"),
        "eval_ids": eval_ids,
        "configurations": configs,
        "run_counts": counts,
        "invalid_rate": round(counts["invalid"] / counts["total"], 4) if counts["total"] else 0.0,
        "invalid_reason_counts": invalid_reason_counts(benchmark),
        "category_coverage": category_coverage(eval_ids),
        "by_configuration": by_configuration,
        "delta_vs_without_skill": delta_vs_baseline(by_configuration, "without_skill"),
    }


def paired_delta_statistics(benchmarks: list[tuple[Path, dict[str, Any]]]) -> dict[str, dict[str, Any]]:
    deltas: dict[str, dict[str, list[float]]] = {
        metric: {f"{left} - {right}": [] for left, right in PAIRWISE_COMPARISONS}
        for metric in SCORE_METRICS
    }
    for path, benchmark in benchmarks:
        identity = benchmark_id(path, benchmark)
        grouped: dict[tuple[str, str, int], dict[str, dict[str, Any]]] = defaultdict(dict)
        for run in valid_runs(benchmark):
            eval_id = str(run.get("eval_id") or "")
            configuration = str(run.get("configuration") or "")
            try:
                run_number = int(run.get("run_number"))
            except (TypeError, ValueError):
                run_number = 0
            grouped[(identity, eval_id, run_number)][configuration] = run
        for by_config in grouped.values():
            for left, right in PAIRWISE_COMPARISONS:
                left_run = by_config.get(left)
                right_run = by_config.get(right)
                if left_run is None or right_run is None:
                    continue
                for metric in SCORE_METRICS:
                    left_value = score_value(left_run, metric)
                    right_value = score_value(right_run, metric)
                    if left_value is not None and right_value is not None:
                        deltas[metric][f"{left} - {right}"].append(left_value - right_value)
    return {
        metric: {
            label: _wilcoxon_signed_rank(values)
            for label, values in by_label.items()
            if values
        }
        for metric, by_label in deltas.items()
    }


def known_good_statistics(benchmarks: list[tuple[Path, dict[str, Any]]]) -> dict[str, Any]:
    by_configuration: dict[str, dict[str, dict[str, Any]]] = {}
    all_configs = sorted(
        {config for _, benchmark in benchmarks for config in ordered_configurations(benchmark)},
        key=lambda config: CONFIGURATION_ORDER.index(config) if config in CONFIGURATION_ORDER else 99,
    )
    for configuration in all_configs:
        config_runs = [
            run
            for _, benchmark in benchmarks
            for run in valid_runs(benchmark)
            if run.get("configuration") == configuration
        ]
        by_configuration[configuration] = {
            "run_counts": {"valid": len(config_runs)},
            "scores": {
                metric: _distribution(
                    [value for run in config_runs if (value := score_value(run, metric)) is not None],
                    lower_bound=0.0,
                    upper_bound=1.0,
                )
                for metric in SCORE_METRICS
            },
            "efficiency": {
                metric: _distribution(
                    [value for run in config_runs if (value := efficiency_value(run, metric)) is not None],
                    lower_bound=0.0,
                )
                for metric in EFFICIENCY_METRICS
            },
        }
    return {
        "basis": [benchmark_id(path, benchmark) for path, benchmark in benchmarks],
        "by_configuration": by_configuration,
        "paired_deltas": paired_delta_statistics(benchmarks),
    }


def select_four_way(benchmarks: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates = [
        benchmark
        for benchmark in benchmarks
        if all(config in benchmark["configurations"] for config in FOUR_SCENARIO_CONFIGS)
    ]
    if not candidates:
        return None
    return sorted(
        candidates,
        key=lambda benchmark: str(benchmark.get("generated_at") or ""),
    )[-1]


def summarize_benchmarks(
    *,
    benchmark_paths: list[Path],
    known_good_paths: list[Path],
    title: str,
) -> dict[str, Any]:
    resolved_benchmarks = discover_benchmark_paths(benchmark_paths)
    resolved_known_good = set(str(path.resolve()) for path in discover_benchmark_paths(known_good_paths))
    loaded = [(path, load_json(path)) for path in resolved_benchmarks]
    summaries = [summarize_one(path, benchmark) for path, benchmark in loaded]
    known_good_loaded = [
        (path, benchmark)
        for path, benchmark in loaded
        if str(path.resolve()) in resolved_known_good
    ]
    if not known_good_loaded and loaded:
        known_good_loaded = loaded
    return {
        "title": title,
        "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "benchmark_count": len(summaries),
        "known_good_count": len(known_good_loaded),
        "benchmarks": summaries,
        "four_scenario_delta": select_four_way(summaries),
        "known_good_statistics": known_good_statistics(known_good_loaded),
    }


def format_score(payload: dict[str, Any]) -> str:
    if payload.get("status") != "summarized":
        return "n/a"
    return f"{float(payload['mean']) * 100:.1f}%"


def format_number(payload: dict[str, Any]) -> str:
    if payload.get("status") != "summarized":
        return "n/a"
    value = float(payload["mean"])
    return f"{value:.1f}" if abs(value) < 100 else f"{value:,.0f}"


def format_delta(value: float | None, *, percent: bool = False) -> str:
    if value is None:
        return "n/a"
    if percent:
        return f"{value * 100:+.1f} pp"
    return f"{value:+.1f}" if abs(value) < 100 else f"{value:+,.0f}"


def markdown_table(headers: list[str], rows: list[list[str]]) -> list[str]:
    return [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(["---", *(["---:"] * (len(headers) - 1))]) + " |",
        *["| " + " | ".join(row) + " |" for row in rows],
    ]


def markdown_for_summary(summary: dict[str, Any]) -> str:
    lines = [f"# {summary['title']}", "", f"Generated: {summary['generated_at']}", ""]
    known_good = set(summary["known_good_statistics"]["basis"])
    rows = []
    for benchmark in summary["benchmarks"]:
        counts = benchmark["run_counts"]
        usage = "known-good stats" if benchmark["benchmark_id"] in known_good else "context output"
        rows.append(
            [
                benchmark["benchmark_id"],
                usage,
                f"{counts['valid']}/{counts['total']}",
                str(len(benchmark["eval_ids"])),
                ", ".join(benchmark["configurations"]),
                str(benchmark.get("generated_at") or "n/a"),
                str(benchmark.get("target_git_sha") or "n/a")[:8],
            ]
        )
    lines.extend(["## Artifact Selection", ""])
    lines.extend(markdown_table(["Benchmark", "Use", "Valid", "Cases", "Configs", "Generated", "SHA"], rows))

    coverage_source = summary["four_scenario_delta"] or (summary["benchmarks"][0] if summary["benchmarks"] else None)
    if coverage_source:
        lines.extend(["", "## Case Coverage", ""])
        rows = [
            [
                category,
                f"{payload['covered_count']}/{payload['total_count']}",
                ", ".join(payload["covered"]) or "n/a",
            ]
            for category, payload in coverage_source["category_coverage"].items()
        ]
        lines.extend(markdown_table(["Flow", "Covered", "Cases"], rows))

    four_way = summary.get("four_scenario_delta")
    if four_way:
        lines.extend(["", "## Four-Scenario Delta", ""])
        rows = []
        for configuration in FOUR_SCENARIO_CONFIGS:
            config_summary_payload = four_way["by_configuration"].get(configuration)
            if not config_summary_payload:
                continue
            counts = config_summary_payload["run_counts"]
            rows.append(
                [
                    configuration,
                    f"{counts['valid']}/{counts['total']}",
                    format_score(config_summary_payload["scores"]["overall_outcome"]),
                    format_score(config_summary_payload["scores"]["task_completion"]),
                    format_score(config_summary_payload["scores"]["accuracy"]),
                    format_score(config_summary_payload["scores"]["reliability"]),
                    format_score(config_summary_payload["scores"]["scope_control"]),
                    format_number(config_summary_payload["efficiency"]["total_tokens"]),
                    format_number(config_summary_payload["efficiency"]["generic_search_calls"]),
                    format_number(config_summary_payload["efficiency"]["executor_duration_seconds"]),
                ]
            )
        lines.extend(
            markdown_table(
                [
                    "Scenario",
                    "Valid",
                    "Overall",
                    "Task",
                    "Accuracy",
                    "Reliability",
                    "Scope",
                    "Tokens",
                    "Search",
                    "Seconds",
                ],
                rows,
            )
        )
        rows = []
        for configuration, deltas in four_way["delta_vs_without_skill"].items():
            rows.append(
                [
                    f"{configuration} - without_skill",
                    format_delta(deltas.get("overall_outcome"), percent=True),
                    format_delta(deltas.get("task_completion"), percent=True),
                    format_delta(deltas.get("total_tokens")),
                    format_delta(deltas.get("generic_search_calls")),
                    format_delta(deltas.get("executor_duration_seconds")),
                ]
            )
        lines.extend(["", "### Delta vs without_skill", ""])
        lines.extend(markdown_table(["Comparison", "Overall", "Task", "Tokens", "Search", "Seconds"], rows))

    stats = summary["known_good_statistics"]
    lines.extend(["", "## Known-Good Statistics", ""])
    rows = []
    for configuration, payload in stats["by_configuration"].items():
        overall = payload["scores"]["overall_outcome"]
        ci = overall.get("confidence_interval_95", {})
        rows.append(
            [
                configuration,
                str(payload["run_counts"]["valid"]),
                format_score(overall),
                f"{float(ci.get('lower', 0.0)) * 100:.1f}%..{float(ci.get('upper', 0.0)) * 100:.1f}%"
                if ci
                else "n/a",
                format_number(payload["efficiency"]["total_tokens"]),
                format_number(payload["efficiency"]["generic_search_calls"]),
            ]
        )
    lines.extend(markdown_table(["Scenario", "Valid runs", "Overall mean", "95% CI", "Tokens", "Search"], rows))

    paired_rows = []
    for metric in ("overall_outcome", "task_completion", "accuracy", "reliability", "scope_control"):
        for label, payload in stats["paired_deltas"].get(metric, {}).items():
            if payload.get("status") != "scored":
                continue
            paired_rows.append(
                [
                    metric,
                    label,
                    str(payload["n_pairs"]),
                    f"{float(payload['mean_delta']) * 100:+.1f} pp",
                    f"{float(payload['p_value']):.4f}",
                    f"{float(payload['effect_size_rank_biserial']):+.3f}",
                ]
            )
    if paired_rows:
        lines.extend(["", "### Paired Deltas", ""])
        lines.extend(markdown_table(["Metric", "Comparison", "Pairs", "Mean delta", "p-value", "Effect"], paired_rows))

    invalid_rows = []
    for benchmark in summary["benchmarks"]:
        counts = benchmark["run_counts"]
        if not counts["invalid"]:
            continue
        reasons = ", ".join(f"{reason}:{count}" for reason, count in benchmark["invalid_reason_counts"].items()) or "unknown"
        invalid_rows.append([benchmark["benchmark_id"], f"{counts['invalid']}/{counts['total']}", reasons])
    if invalid_rows:
        lines.extend(["", "## Excluded or Diagnostic Outputs", ""])
        lines.extend(markdown_table(["Benchmark", "Invalid", "Reasons"], invalid_rows))

    lines.extend(
        [
            "",
            "## Usage Justification",
            "",
            "- `known-good stats` rows are the statistical basis: their benchmark contracts completed and their valid/total counts are shown above.",
            "- `context output` rows are retained for diagnosis, regression history, or external traceability; high-invalid outputs are not mixed into known-good statistics.",
            "- The four-scenario section isolates the latest complete run containing `without_skill`, `skill_only`, `tool_only`, and `with_skill` so the scenario delta comes from one comparable matrix.",
            "- Paired deltas use matched `benchmark_id + eval_id + run_number` samples and Wilcoxon signed-rank tests; this is operational evidence across repeated benchmark batches, not a randomized controlled study.",
        ]
    )
    return "\n".join(lines) + "\n"


def write_summary_outputs(summary: dict[str, Any], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "benchmark-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (output_dir / "benchmark-summary.md").write_text(markdown_for_summary(summary))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Summarize benchmark.json outputs into compact JSON and Markdown.")
    parser.add_argument("--benchmark", action="append", type=Path, default=[], help="benchmark.json or iteration directory to include")
    parser.add_argument("--scan-root", action="append", type=Path, default=[], help="Directory to recursively scan for benchmark.json")
    parser.add_argument("--known-good", action="append", type=Path, default=[], help="benchmark.json or iteration directory to use in statistical basis")
    parser.add_argument("--output-dir", required=True, type=Path, help="Directory for benchmark-summary.json and benchmark-summary.md")
    parser.add_argument("--title", default="Kast Benchmark Output Summary", help="Markdown report title")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    benchmark_paths = args.benchmark + args.scan_root
    if not benchmark_paths:
        parser.error("at least one --benchmark or --scan-root is required")
    summary = summarize_benchmarks(
        benchmark_paths=benchmark_paths,
        known_good_paths=args.known_good,
        title=args.title,
    )
    write_summary_outputs(summary, args.output_dir)
    print(f"Generated: {args.output_dir / 'benchmark-summary.json'}")
    print(f"Generated: {args.output_dir / 'benchmark-summary.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
