#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

CATEGORIES = {
    "Disambiguation": {"vp-disambiguate-member", "vp-disambiguate-function"},
    "Completeness": {"vp-exhaustive-references", "vp-sealed-hierarchy-trace"},
    "Safe Mutations": {"vp-multi-file-rename", "vp-edit-and-validate"},
    "Token Efficiency": {"vp-scaffold-large-class", "vp-workspace-discovery"},
    "Multi-Step": {"vp-impact-analysis", "vp-cross-module-flow"},
}

ENTERPRISE_VALUE = {
    "Disambiguation": "correctness -> fewer bugs shipped from symbol mix-ups",
    "Completeness": "coverage -> fewer missed usages and safer audits",
    "Safe Mutations": "reliability -> fewer broken builds after edits",
    "Token Efficiency": "scope discipline -> less unnecessary work per task",
    "Multi-Step": "execution quality -> clearer compound-task outcomes",
}

PRIMARY_DIMENSIONS = (
    ("Overall outcome", "overall_outcome", "overall"),
    ("Task completion", "task_completion", "task_completion"),
    ("Accuracy", "accuracy", "accuracy"),
    ("Reliability", "reliability", "reliability"),
    ("Scope control", "scope_control", "scope_control"),
)

SUPPORTING_EFFICIENCY = (
    ("Transcript chars", "transcript_chars"),
    ("Tool calls", "total_tool_calls"),
    ("Semantic tool calls", "semantic_tool_calls"),
    ("Generic search calls", "generic_search_calls"),
    ("Executor time (s)", "executor_duration_seconds"),
    ("Input tokens", "input_tokens"),
    ("Output tokens", "output_tokens"),
    ("Cache read tokens", "cache_read_tokens"),
    ("Total tokens", "total_tokens"),
)


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def _summary_score_mean(
    benchmark: dict[str, Any],
    config: str,
    measurement_key: str,
    kind: str = "outcome",
) -> float | None:
    summary = (
        benchmark.get("summary", {})
        .get("by_configuration", {})
        .get(config, {})
        .get("measurements", {})
        .get(measurement_key, {})
        .get(kind, {})
    )
    if summary.get("status") != "summarized":
        return None
    return float(summary["mean"])


def _summary_efficiency_mean(benchmark: dict[str, Any], config: str, metric: str) -> float | None:
    summary = (
        benchmark.get("summary", {})
        .get("by_configuration", {})
        .get(config, {})
        .get("efficiency", {})
        .get(metric, {})
    )
    if summary.get("status") != "summarized":
        return None
    return float(summary["mean"])


def _score_delta(benchmark: dict[str, Any], stat_key: str) -> tuple[str, str]:
    stats = (
        benchmark.get("paired_analysis", {})
        .get("statistics", {})
        .get("score_metrics", {})
        .get(stat_key, {})
    )
    if stats.get("status") != "scored":
        return "n/a", "n/a"
    return f"{float(stats['mean_delta']):+.3f}", f"{float(stats['p_value']):.3f}"


def _efficiency_delta(benchmark: dict[str, Any], metric: str) -> tuple[str, str]:
    stats = (
        benchmark.get("paired_analysis", {})
        .get("statistics", {})
        .get("efficiency_metrics", {})
        .get(metric, {})
    )
    if stats.get("status") != "scored":
        return "n/a", "n/a"
    return f"{float(stats['mean_delta']):+.3f}", f"{float(stats['p_value']):.3f}"


def _format_score(value: float | None) -> str:
    return "n/a" if value is None else f"{value * 100:.0f}%"


def _format_metric(value: float | None, metric: str) -> str:
    if value is None:
        return "n/a"
    if metric == "executor_duration_seconds":
        return f"{value:.2f}"
    return f"{value:.0f}" if float(value).is_integer() else f"{value:.2f}"


def category_pass_rates(benchmark: dict[str, Any], config: str) -> dict[str, float]:
    grouped: dict[str, list[float]] = defaultdict(list)
    for run in benchmark.get("runs", []):
        if run.get("configuration") != config or run.get("status") != "valid":
            continue
        score = (
            run.get("measurements", {})
            .get("overall", {})
            .get("outcome", {})
        )
        if score.get("status") != "scored":
            continue
        eval_id = str(run.get("eval_id", ""))
        for category, ids in CATEGORIES.items():
            if eval_id in ids:
                grouped[category].append(float(score["score"]))
                break
    return {
        category: (sum(values) / len(values) if values else 0.0)
        for category, values in grouped.items()
    }


def key_findings(benchmark: dict[str, Any]) -> list[str]:
    findings: list[str] = []
    issues = benchmark.get("paired_analysis", {}).get("issues", {})
    invalid_runs = issues.get("invalid_runs", [])
    flaky_runs = issues.get("flaky_runs", [])
    if invalid_runs:
        findings.append(f"{len(invalid_runs)} run(s) were excluded as invalid and do not affect the benchmark headline.")
    if flaky_runs:
        findings.append(f"{len(flaky_runs)} run(s) required retries before succeeding.")

    by_expectation: dict[str, dict[str, int]] = defaultdict(lambda: {"passed": 0, "eligible": 0})
    for run in benchmark.get("runs", []):
        if run.get("configuration") != "with_skill" or run.get("status") != "valid":
            continue
        for expectation in run.get("expectations", []):
            status = expectation.get("status")
            if status not in {"passed", "failed"}:
                continue
            text = str(expectation.get("text", "")).strip()
            if not text:
                continue
            by_expectation[text]["eligible"] += 1
            if status == "passed":
                by_expectation[text]["passed"] += 1

    for text, counts in sorted(by_expectation.items()):
        findings.append(
            f"Expectation '{text}' passed in {counts['passed']}/{counts['eligible']} with-skill valid runs."
        )
        if len(findings) >= 5:
            break

    return findings or ["No benchmark findings were derivable from the current artifact."]


def build_markdown(benchmark: dict[str, Any], bindings: dict[str, Any]) -> str:
    target_repo = bindings.get("target_repo") or benchmark.get("metadata", {}).get("target_repo", "target repo")
    category_rates = category_pass_rates(benchmark, "with_skill")

    lines = [
        f"# Kast Value Proof: {target_repo}",
        "",
        "## Headline dimensions",
        "",
        "| Dimension | with_skill | without_skill | Delta | p-value |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for label, stat_key, measurement_key in PRIMARY_DIMENSIONS:
        with_value = _summary_score_mean(benchmark, "with_skill", measurement_key)
        without_value = _summary_score_mean(benchmark, "without_skill", measurement_key)
        delta, p_value = _score_delta(benchmark, stat_key)
        lines.append(
            f"| {label} | {_format_score(with_value)} | {_format_score(without_value)} | {delta} | {p_value} |"
        )

    lines.extend(
        [
            "",
            "## Supporting efficiency",
            "",
            "| Metric | with_skill | without_skill | Delta | p-value |",
            "| --- | ---: | ---: | ---: | ---: |",
        ]
    )
    for label, metric in SUPPORTING_EFFICIENCY:
        with_value = _summary_efficiency_mean(benchmark, "with_skill", metric)
        without_value = _summary_efficiency_mean(benchmark, "without_skill", metric)
        delta, p_value = _efficiency_delta(benchmark, metric)
        lines.append(
            f"| {label} | {_format_metric(with_value, metric)} | {_format_metric(without_value, metric)} | {delta} | {p_value} |"
        )

    lines.extend(
        [
            "",
            "## Per-category breakdown",
            "",
            "| Category | with_skill outcome | Enterprise value |",
            "| --- | ---: | --- |",
        ]
    )
    for category in CATEGORIES:
        lines.append(
            f"| {category} | {_format_score(category_rates.get(category, 0.0))} | {ENTERPRISE_VALUE[category]} |"
        )

    lines.extend(["", "## Key findings", ""])
    for finding in key_findings(benchmark):
        lines.append(f"- {finding}")

    return "\n".join(lines) + "\n"


def markdown_to_html(markdown: str, title: str) -> str:
    escaped = html.escape(markdown)
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)}</title>
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 2rem auto; max-width: 960px; line-height: 1.5; color: #1f2937; }}
    pre {{ white-space: pre-wrap; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 12px; padding: 1rem; }}
  </style>
</head>
<body>
  <pre>{escaped}</pre>
</body>
</html>
"""


def generate_summary_documents(*, benchmark_path: Path, bindings_path: Path, output_path: Path, html_output_path: Path | None = None) -> Path:
    benchmark = load_json(benchmark_path)
    bindings = load_json(bindings_path)
    markdown = build_markdown(benchmark, bindings)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(markdown)
    html_path = html_output_path or output_path.with_suffix(".html")
    html_path.write_text(markdown_to_html(markdown, f"Kast Value Proof: {bindings.get('target_repo', 'target repo')}"))
    return html_path


def default_bindings_path(iteration_dir: Path) -> Path:
    default_path = iteration_dir / "bindings.json"
    if default_path.exists():
        return default_path

    parent_bindings_dir = iteration_dir.parent / "bindings"
    if not parent_bindings_dir.exists():
        return default_path
    candidates = sorted(parent_bindings_dir.glob("*.json"))
    if len(candidates) == 1:
        return candidates[0]
    return default_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Generate enterprise-facing evaluation summary documents.")
    parser.add_argument("iteration_dir", nargs="?", type=Path, help="Iteration directory containing benchmark.json and bindings.json")
    parser.add_argument("--benchmark", type=Path, help="Path to benchmark.json; defaults to ITERATION_DIR/benchmark.json")
    parser.add_argument("--bindings", type=Path, help="Path to bindings JSON; defaults to ITERATION_DIR/bindings.json")
    parser.add_argument("--output", type=Path, help="Markdown output path; defaults to ITERATION_DIR/executive-summary.md")
    parser.add_argument("--html-output", type=Path, help="HTML output path; defaults to ITERATION_DIR/executive-summary.html")
    return parser


def resolved_paths(args: argparse.Namespace, parser: argparse.ArgumentParser) -> tuple[Path, Path, Path, Path | None]:
    if args.iteration_dir is None:
        missing = [
            flag
            for flag, value in (
                ("--benchmark", args.benchmark),
                ("--bindings", args.bindings),
                ("--output", args.output),
            )
            if value is None
        ]
        if missing:
            parser.error("iteration_dir is required unless --benchmark, --bindings, and --output are provided.")
        return args.benchmark, args.bindings, args.output, args.html_output

    iteration_dir = args.iteration_dir
    return (
        args.benchmark or iteration_dir / "benchmark.json",
        args.bindings or default_bindings_path(iteration_dir),
        args.output or iteration_dir / "executive-summary.md",
        args.html_output or iteration_dir / "executive-summary.html",
    )


def main(argv: list[str] | None = None) -> None:
    parser = build_parser()
    args = parser.parse_args(argv)
    benchmark_path, bindings_path, output_path, html_output_path = resolved_paths(args, parser)

    html_path = generate_summary_documents(
        benchmark_path=benchmark_path,
        bindings_path=bindings_path,
        output_path=output_path,
        html_output_path=html_output_path,
    )
    print(f"Generated: {output_path}")
    print(f"Generated: {html_path}")


if __name__ == "__main__":
    main(sys.argv[1:])
