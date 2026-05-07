#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
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
    "Completeness": "completeness -> audit confidence and fewer missed call sites",
    "Safe Mutations": "validated edits -> fewer broken builds after refactors",
    "Token Efficiency": "structural summaries -> lower API cost and faster reviews",
    "Multi-Step": "compound workflows -> clearer blast-radius analysis before changes",
}


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def config_names(benchmark: dict[str, Any]) -> tuple[str, str]:
    summary = benchmark.get("run_summary", {})
    configs = [name for name in summary if name != "delta"]
    if "with_skill" in configs and "without_skill" in configs:
        return "with_skill", "without_skill"
    if len(configs) >= 2:
        return configs[0], configs[1]
    if len(configs) == 1:
        return configs[0], "without_skill"
    return "with_skill", "without_skill"


def mean_from_summary(benchmark: dict[str, Any], config: str, metric: str) -> float:
    summary_metric = benchmark.get("run_summary", {}).get(config, {}).get(metric, {})
    if isinstance(summary_metric, dict) and "mean" in summary_metric:
        return float(summary_metric["mean"])

    values = [
        float(run.get("result", {}).get(metric, 0.0))
        for run in benchmark.get("runs", [])
        if run.get("configuration") == config
    ]
    return sum(values) / len(values) if values else 0.0


def category_pass_rates(benchmark: dict[str, Any], config: str) -> dict[str, float]:
    grouped: dict[str, list[float]] = defaultdict(list)
    for run in benchmark.get("runs", []):
        if run.get("configuration") != config:
            continue
        eval_id = str(run.get("eval_id", ""))
        for category, ids in CATEGORIES.items():
            if eval_id in ids:
                grouped[category].append(float(run.get("result", {}).get("pass_rate", 0.0)))
                break
    return {
        category: (sum(values) / len(values) if values else 0.0)
        for category, values in grouped.items()
    }


def percent(value: float) -> str:
    return f"{value * 100:.0f}%"


def metric_rows(benchmark: dict[str, Any], primary: str, baseline: str) -> list[tuple[str, str, str, str]]:
    delta = benchmark.get("run_summary", {}).get("delta", {})
    rows = []
    for label, metric in [
        ("Pass rate", "pass_rate"),
        ("Tokens", "tokens"),
        ("Tool calls", "tool_calls"),
        ("Time", "time_seconds"),
    ]:
        primary_value = mean_from_summary(benchmark, primary, metric)
        baseline_value = mean_from_summary(benchmark, baseline, metric)
        if metric == "pass_rate":
            rows.append((label, percent(primary_value), percent(baseline_value), str(delta.get(metric, f"{primary_value - baseline_value:+.2f}"))))
        elif metric == "time_seconds":
            rows.append((label, f"{primary_value:.1f}s", f"{baseline_value:.1f}s", str(delta.get(metric, f"{primary_value - baseline_value:+.1f}"))))
        else:
            rows.append((label, f"{primary_value:.0f}", f"{baseline_value:.0f}", str(delta.get(metric, f"{primary_value - baseline_value:+.0f}"))))
    return rows


def key_findings(benchmark: dict[str, Any]) -> list[str]:
    findings = [str(note) for note in benchmark.get("notes", []) if str(note).strip()]
    if findings:
        return findings

    by_expectation: dict[str, dict[str, int]] = defaultdict(lambda: {"passed": 0, "total": 0})
    for run in benchmark.get("runs", []):
        if run.get("configuration") != "with_skill":
            continue
        for expectation in run.get("expectations", []):
            text = str(expectation.get("text", "")).strip()
            if not text:
                continue
            by_expectation[text]["total"] += 1
            if expectation.get("passed") is True:
                by_expectation[text]["passed"] += 1

    generated = []
    for text, counts in by_expectation.items():
        if counts["total"]:
            generated.append(f"Assertion '{text}' passed in {counts['passed']}/{counts['total']} with-skill runs.")
    return generated or ["No analyzer notes were present in benchmark.json."]


def build_markdown(benchmark: dict[str, Any], bindings: dict[str, Any]) -> str:
    target_repo = bindings.get("target_repo") or benchmark.get("metadata", {}).get("skill_name", "target repo")
    primary, baseline = config_names(benchmark)
    category_rates = category_pass_rates(benchmark, primary)

    lines = [
        f"# Kast Value Proof: {target_repo}",
        "",
        "## Headline metrics",
        "",
        f"| Metric | {primary} | {baseline} | Delta |",
        "| --- | ---: | ---: | ---: |",
    ]
    for label, primary_value, baseline_value, delta_value in metric_rows(benchmark, primary, baseline):
        lines.append(f"| {label} | {primary_value} | {baseline_value} | {delta_value} |")

    lines.extend(["", "## Per-category breakdown", "", "| Category | Pass rate | Enterprise value |", "| --- | ---: | --- |"])
    for category in CATEGORIES:
        lines.append(f"| {category} | {percent(category_rates.get(category, 0.0))} | {ENTERPRISE_VALUE[category]} |")

    lines.extend(["", "## Key findings", ""])
    for finding in key_findings(benchmark):
        lines.append(f"- {finding}")

    lines.extend(["", "## What this means", ""])
    for category, value in ENTERPRISE_VALUE.items():
        lines.append(f"- **{category}**: {value}.")

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


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate enterprise-facing Kast value-proof summary documents.")
    parser.add_argument("--benchmark", required=True, type=Path, help="Path to benchmark.json")
    parser.add_argument("--bindings", required=True, type=Path, help="Path to bindings JSON")
    parser.add_argument("--output", required=True, type=Path, help="Markdown output path")
    parser.add_argument("--html-output", type=Path, help="Optional HTML output path")
    args = parser.parse_args()

    html_path = generate_summary_documents(
        benchmark_path=args.benchmark,
        bindings_path=args.bindings,
        output_path=args.output,
        html_output_path=args.html_output,
    )
    print(f"Generated: {args.output}")
    print(f"Generated: {html_path}")


if __name__ == "__main__":
    main()
