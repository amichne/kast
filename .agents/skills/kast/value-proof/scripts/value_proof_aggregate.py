#!/usr/bin/env python3
"""Value-proof aggregator with applicability-aware pass-rate and paired stats.

Replaces the headline numbers in the standard skill-creator benchmark.json
with a fairer comparison and a paired-test significance estimate.

Inputs:
  iteration_dir/eval-*/<config>/run-*/grading.json   (schema v2)
  iteration_dir/manifest.json                         (eval -> dir/chain map)
  bindings JSON                                       (optional; for metadata)

Outputs:
  iteration_dir/benchmark.json   — extended with:
       run_summary[config].outcome_pass_rate         {mean, stddev, min, max}
       run_summary.delta.outcome_pass_rate           "+0.NN [N=K runs, p=0.NN]"
       run_summary.delta.paired_wilcoxon             {statistic, p_value, n_pairs}
       paired_stats.eval_deltas                      [{eval_id, delta_outcome_pass_rate, delta_tokens, delta_time}]
       paired_stats.outliers                         [{eval_id, metric, value, fence}]
       paired_stats.flaky_runs                       [{eval_id, configuration, run, attempts}]
       paired_stats.baseline_violations              [{eval_id, run, kast_calls}]
       paired_stats.contradictions                   [{eval_id, configuration, run, count}]
  iteration_dir/benchmark.md     — Markdown rollup including the above.
  iteration_dir/../history/progression.json
                                — appends a row {iteration, target_repo, git_sha,
                                  outcome_pass_rate_with, outcome_pass_rate_without,
                                  delta, p_value}.

The Wilcoxon signed-rank statistic is implemented in pure Python (no SciPy
required) using the exact distribution for n <= 20 and a normal approximation
for n > 20. Good enough for the 10-eval, 5-run regime we're in.

Usage:
    python3 value_proof_aggregate.py <iteration_dir>           \\
        --skill-name kast-value-proof                          \\
        [--bindings <path>]                                    \\
        [--catalog <path>]
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable

# ---------- IO -------------------------------------------------------------


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


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


# ---------- stats ----------------------------------------------------------


def _mean_stddev(values: list[float]) -> dict[str, float]:
    if not values:
        return {"mean": 0.0, "stddev": 0.0, "min": 0.0, "max": 0.0, "n": 0}
    mean = statistics.fmean(values)
    stddev = statistics.pstdev(values) if len(values) > 1 else 0.0
    return {
        "mean": round(mean, 4),
        "stddev": round(stddev, 4),
        "min": round(min(values), 4),
        "max": round(max(values), 4),
        "n": len(values),
    }


def _wilcoxon_signed_rank(diffs: list[float]) -> dict[str, float | int | str]:
    """Two-sided Wilcoxon signed-rank test on the paired differences.

    Returns {statistic: W, p_value, n_pairs, method}.
    Drops zero differences (Pratt would keep them; we use the simpler
    Wilcoxon convention since exact ties are rare on continuous metrics).
    """
    nonzero = [d for d in diffs if d != 0]
    n = len(nonzero)
    if n == 0:
        return {"statistic": 0.0, "p_value": 1.0, "n_pairs": 0, "method": "no_signal"}

    abs_vals = sorted(((abs(d), 1 if d > 0 else -1) for d in nonzero), key=lambda t: t[0])
    # Average ranks for ties.
    ranks: list[float] = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and abs_vals[j + 1][0] == abs_vals[i][0]:
            j += 1
        avg_rank = (i + j) / 2.0 + 1.0  # 1-indexed
        for k in range(i, j + 1):
            ranks[k] = avg_rank
        i = j + 1
    w_plus = sum(r for r, (_, sign) in zip(ranks, abs_vals) if sign > 0)
    w_minus = sum(r for r, (_, sign) in zip(ranks, abs_vals) if sign < 0)
    statistic = min(w_plus, w_minus)

    if n <= 20:
        p = _wilcoxon_exact_p(nonzero, statistic)
        method = "exact"
    else:
        # Normal approximation with continuity correction.
        mean = n * (n + 1) / 4.0
        var = n * (n + 1) * (2 * n + 1) / 24.0
        if var == 0:
            return {"statistic": float(statistic), "p_value": 1.0, "n_pairs": n, "method": "no_variance"}
        z = (statistic - mean + 0.5) / math.sqrt(var)
        p = 2 * _normal_sf(abs(z))
        method = "normal_approx"
    return {
        "statistic": round(float(statistic), 4),
        "p_value": round(min(1.0, max(0.0, p)), 4),
        "n_pairs": n,
        "method": method,
    }


def _wilcoxon_exact_p(diffs: list[float], statistic: float) -> float:
    n = len(diffs)
    # Enumerate all 2^n sign assignments of ranks 1..n; count those with W <= statistic.
    if n > 20:
        return 1.0
    abs_sorted = sorted(abs(d) for d in diffs)
    ranks: list[float] = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and abs_sorted[j + 1] == abs_sorted[i]:
            j += 1
        avg_rank = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[k] = avg_rank
        i = j + 1
    total = 1 << n
    le_count = 0
    for mask in range(total):
        w_plus = 0.0
        for k in range(n):
            if mask & (1 << k):
                w_plus += ranks[k]
        w_minus = sum(ranks) - w_plus
        w = min(w_plus, w_minus)
        if w <= statistic + 1e-9:
            le_count += 1
    return le_count / total


def _normal_sf(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2))


def _tukey_outliers(values: dict[str, float]) -> list[tuple[str, float, str]]:
    """Return [(eval_id, value, 'low'|'high'), ...] using 1.5×IQR fences."""
    if len(values) < 4:
        return []
    sorted_vals = sorted(values.values())
    q1 = sorted_vals[len(sorted_vals) // 4]
    q3 = sorted_vals[(3 * len(sorted_vals)) // 4]
    iqr = q3 - q1
    if iqr == 0:
        return []
    low = q1 - 1.5 * iqr
    high = q3 + 1.5 * iqr
    out = []
    for k, v in values.items():
        if v < low:
            out.append((k, v, "low"))
        elif v > high:
            out.append((k, v, "high"))
    return out


# ---------- main aggregation ----------------------------------------------


def collect_runs(iteration_dir: Path) -> dict[str, dict[str, list[dict[str, Any]]]]:
    """eval_id -> config -> list[run grading dict]."""
    out: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: defaultdict(list))
    for eval_id, config, run_number, run_dir in _iter_runs(iteration_dir):
        grading = _read_json(run_dir / "grading.json")
        if not grading:
            continue
        grading.setdefault("_run_dir", str(run_dir))
        grading.setdefault("_run_number", run_number)
        out[eval_id][config].append(grading)
    return out


def per_config_metrics(runs: list[dict[str, Any]]) -> dict[str, list[float]]:
    metrics: dict[str, list[float]] = defaultdict(list)
    for grading in runs:
        summary = grading.get("summary", {})
        em = grading.get("execution_metrics", {})
        timing = grading.get("timing", {})
        metrics["pass_rate"].append(float(summary.get("pass_rate", 0.0)))
        metrics["outcome_pass_rate"].append(float(summary.get("outcome_pass_rate", 0.0)))
        metrics["process_pass_rate"].append(float(summary.get("process_pass_rate", 0.0)))
        metrics["transcript_chars"].append(float(em.get("transcript_chars", 0)))
        metrics["tool_calls"].append(float(em.get("total_tool_calls", 0)))
        metrics["kast_calls"].append(float(em.get("kast_calls", 0)))
        metrics["grep_or_find_calls"].append(float(em.get("grep_or_find_calls", 0)))
        metrics["executor_duration_seconds"].append(float(timing.get("executor_duration_seconds", 0.0)))
    return metrics


def aggregate(iteration_dir: Path, *, skill_name: str, bindings_path: Path | None, catalog_path: Path | None) -> dict[str, Any]:
    runs_by_eval = collect_runs(iteration_dir)

    bindings = _read_json(bindings_path) if bindings_path else {}
    catalog = _read_json(catalog_path) if catalog_path else {}

    configs = sorted({c for ev in runs_by_eval.values() for c in ev.keys()})
    eval_ids = sorted(runs_by_eval.keys())

    # Per (eval, config): mean of each metric.
    eval_means: dict[str, dict[str, dict[str, float]]] = defaultdict(dict)
    runs_flat: list[dict[str, Any]] = []
    flaky: list[dict[str, Any]] = []
    baseline_violations: list[dict[str, Any]] = []
    contradictions: list[dict[str, Any]] = []

    for eval_id in eval_ids:
        for config in configs:
            run_grades = runs_by_eval.get(eval_id, {}).get(config, [])
            if not run_grades:
                continue
            metrics = per_config_metrics(run_grades)
            eval_means[eval_id][config] = {k: statistics.fmean(v) if v else 0.0 for k, v in metrics.items()}
            for grading in run_grades:
                summary = grading.get("summary", {})
                em = grading.get("execution_metrics", {})
                timing = grading.get("timing", {})
                integrity = grading.get("integrity", {}) or {}
                runs_flat.append({
                    "eval_id": eval_id,
                    "configuration": config,
                    "run_number": grading.get("_run_number"),
                    "result": {
                        "pass_rate": summary.get("pass_rate", 0.0),
                        "outcome_pass_rate": summary.get("outcome_pass_rate", 0.0),
                        "process_pass_rate": summary.get("process_pass_rate", 0.0),
                        "passed": summary.get("passed", 0),
                        "failed": summary.get("failed", 0),
                        "total": summary.get("total", 0),
                        "outcome_total": summary.get("outcome_total", 0),
                        "outcome_passed": summary.get("outcome_passed", 0),
                        "transcript_chars": em.get("transcript_chars", 0),
                        "tool_calls": em.get("total_tool_calls", 0),
                        "kast_calls": em.get("kast_calls", 0),
                        "grep_or_find_calls": em.get("grep_or_find_calls", 0),
                        "time_seconds": timing.get("executor_duration_seconds", 0.0),
                        "executor_duration_source": timing.get("executor_duration_source", "missing"),
                        "errors": em.get("errors_encountered", 0),
                    },
                    "expectations": grading.get("expectations", []),
                    "integrity": integrity,
                })
                if integrity.get("attempts", 1) > 1:
                    flaky.append({
                        "eval_id": eval_id,
                        "configuration": config,
                        "run_number": grading.get("_run_number"),
                        "attempts": integrity.get("attempts", 1),
                    })
                if integrity.get("baseline_isolation_violation"):
                    baseline_violations.append({
                        "eval_id": eval_id,
                        "run_number": grading.get("_run_number"),
                        "kast_calls": em.get("kast_calls", 0),
                    })
                if integrity.get("contradictions"):
                    contradictions.append({
                        "eval_id": eval_id,
                        "configuration": config,
                        "run_number": grading.get("_run_number"),
                        "count": len(integrity["contradictions"]),
                        "samples": integrity["contradictions"][:3],
                    })

    # Run-summary across all runs of a configuration.
    run_summary: dict[str, Any] = {}
    for config in configs:
        all_metrics: dict[str, list[float]] = defaultdict(list)
        for eval_id in eval_ids:
            run_grades = runs_by_eval.get(eval_id, {}).get(config, [])
            metrics = per_config_metrics(run_grades)
            for k, vs in metrics.items():
                all_metrics[k].extend(vs)
        run_summary[config] = {k: _mean_stddev(v) for k, v in all_metrics.items()}

    # Paired analysis: per-eval mean delta on outcome_pass_rate, transcript_chars, time.
    paired_stats: dict[str, Any] = {}
    if "with_skill" in configs and "without_skill" in configs:
        outcome_diffs: list[float] = []
        token_diffs: list[float] = []
        time_diffs: list[float] = []
        per_eval = []
        for eval_id in eval_ids:
            with_skill_means = eval_means.get(eval_id, {}).get("with_skill")
            without_skill_means = eval_means.get(eval_id, {}).get("without_skill")
            if not with_skill_means or not without_skill_means:
                continue
            d_outcome = with_skill_means["outcome_pass_rate"] - without_skill_means["outcome_pass_rate"]
            d_tokens = with_skill_means["transcript_chars"] - without_skill_means["transcript_chars"]
            d_time = with_skill_means["executor_duration_seconds"] - without_skill_means["executor_duration_seconds"]
            outcome_diffs.append(d_outcome)
            token_diffs.append(d_tokens)
            time_diffs.append(d_time)
            per_eval.append({
                "eval_id": eval_id,
                "delta_outcome_pass_rate": round(d_outcome, 4),
                "delta_transcript_chars": round(d_tokens, 1),
                "delta_time_seconds": round(d_time, 3),
                "with_skill_outcome_pass_rate": round(with_skill_means["outcome_pass_rate"], 4),
                "without_skill_outcome_pass_rate": round(without_skill_means["outcome_pass_rate"], 4),
            })
        paired_stats["eval_deltas"] = per_eval
        paired_stats["outcome_pass_rate_paired"] = _wilcoxon_signed_rank(outcome_diffs)
        paired_stats["transcript_chars_paired"] = _wilcoxon_signed_rank(token_diffs)
        paired_stats["time_seconds_paired"] = _wilcoxon_signed_rank(time_diffs)

        # Outliers across evals (per-config) on outcome_pass_rate and tokens.
        outliers: list[dict[str, Any]] = []
        for config in ("with_skill", "without_skill"):
            for metric in ("outcome_pass_rate", "transcript_chars", "executor_duration_seconds"):
                series = {ev: eval_means[ev][config][metric] for ev in eval_ids if config in eval_means.get(ev, {})}
                for eval_id, value, side in _tukey_outliers(series):
                    outliers.append({
                        "configuration": config,
                        "metric": metric,
                        "eval_id": eval_id,
                        "value": round(value, 4),
                        "side": side,
                    })
        paired_stats["outliers"] = outliers

    paired_stats["flaky_runs"] = flaky
    paired_stats["baseline_violations"] = baseline_violations
    paired_stats["contradictions"] = contradictions

    # Headline delta as a string.
    delta = {}
    if "with_skill" in configs and "without_skill" in configs:
        for metric in ("pass_rate", "outcome_pass_rate", "transcript_chars", "executor_duration_seconds", "tool_calls", "kast_calls", "grep_or_find_calls", "process_pass_rate"):
            w = run_summary["with_skill"].get(metric, {}).get("mean", 0.0)
            wo = run_summary["without_skill"].get(metric, {}).get("mean", 0.0)
            delta[metric] = f"{w - wo:+.2f}"
        wilcoxon = paired_stats.get("outcome_pass_rate_paired", {})
        delta["outcome_pass_rate_significance"] = (
            f"p={wilcoxon.get('p_value', 1.0):.3f} ({wilcoxon.get('method', 'n/a')}, n_pairs={wilcoxon.get('n_pairs', 0)})"
        )

    benchmark = {
        "metadata": {
            "skill_name": skill_name,
            "skill_path": ".agents/skills/kast/value-proof",
            "timestamp": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "iteration_dir": str(iteration_dir),
            "target_repo": (bindings or {}).get("target_repo", ""),
            "workspace_root": (bindings or {}).get("workspace_root", ""),
            "git_sha_target": (bindings or {}).get("git_sha", ""),
            "evals_run": eval_ids,
            "configs": configs,
            "runs_per_eval_per_config": {
                eval_id: {config: len(runs_by_eval[eval_id].get(config, [])) for config in configs}
                for eval_id in eval_ids
            },
            "catalog_version": (catalog or {}).get("version", 0),
        },
        "runs": runs_flat,
        "run_summary": {**run_summary, "delta": delta},
        "paired_stats": paired_stats,
    }
    return benchmark


def write_outputs(iteration_dir: Path, benchmark: dict[str, Any]) -> None:
    benchmark_path = iteration_dir / "benchmark.json"
    benchmark_path.write_text(json.dumps(benchmark, indent=2) + "\n")

    md_lines: list[str] = []
    meta = benchmark["metadata"]
    md_lines.append(f"# Benchmark: {meta['skill_name']} — {meta.get('target_repo', '?')}")
    md_lines.append("")
    md_lines.append(f"_iteration: `{Path(meta['iteration_dir']).name}`, evals: {len(meta['evals_run'])}, configs: {meta['configs']}_")
    md_lines.append("")
    md_lines.append("## Headline (paired across evals)")
    delta = benchmark["run_summary"].get("delta", {})
    md_lines.append("")
    md_lines.append("| Metric | with_skill | without_skill | Δ (with − without) |")
    md_lines.append("| --- | ---: | ---: | ---: |")
    for metric in ("pass_rate", "outcome_pass_rate", "transcript_chars", "executor_duration_seconds", "tool_calls", "kast_calls"):
        w = benchmark["run_summary"].get("with_skill", {}).get(metric, {}).get("mean", 0.0)
        wo = benchmark["run_summary"].get("without_skill", {}).get(metric, {}).get("mean", 0.0)
        md_lines.append(f"| {metric} | {w:.3f} | {wo:.3f} | {delta.get(metric, '?')} |")
    md_lines.append("")
    md_lines.append(f"**Outcome pass-rate significance:** {delta.get('outcome_pass_rate_significance', 'n/a')}")
    md_lines.append("")

    paired = benchmark.get("paired_stats", {})
    if paired.get("eval_deltas"):
        md_lines.append("## Per-eval deltas (mean across runs)")
        md_lines.append("")
        md_lines.append("| eval_id | Δ outcome_pass_rate | Δ transcript_chars | Δ time (s) |")
        md_lines.append("| --- | ---: | ---: | ---: |")
        for ed in paired["eval_deltas"]:
            md_lines.append(f"| {ed['eval_id']} | {ed['delta_outcome_pass_rate']:+.3f} | {ed['delta_transcript_chars']:+,.0f} | {ed['delta_time_seconds']:+.2f} |")
        md_lines.append("")

    if paired.get("baseline_violations"):
        md_lines.append("## ⚠ Baseline-isolation violations")
        md_lines.append("")
        for v in paired["baseline_violations"]:
            md_lines.append(f"- `{v['eval_id']}` run {v['run_number']} → kast_calls={v['kast_calls']}")
        md_lines.append("")

    if paired.get("contradictions"):
        md_lines.append("## ⚠ Grading contradictions")
        md_lines.append("")
        for c in paired["contradictions"]:
            md_lines.append(f"- `{c['eval_id']}` ({c['configuration']}, run {c['run_number']}): {c['count']} contradiction(s)")
            for sample in c.get("samples", []):
                md_lines.append(f"    - {sample}")
        md_lines.append("")

    if paired.get("outliers"):
        md_lines.append("## Tukey outliers (1.5×IQR)")
        md_lines.append("")
        for o in paired["outliers"]:
            md_lines.append(f"- `{o['eval_id']}` {o['configuration']}: {o['metric']} = {o['value']} ({o['side']})")
        md_lines.append("")

    if paired.get("flaky_runs"):
        md_lines.append("## Flaky runs (succeeded after retry)")
        md_lines.append("")
        for r in paired["flaky_runs"]:
            md_lines.append(f"- `{r['eval_id']}` {r['configuration']} run {r['run_number']} attempts={r['attempts']}")
        md_lines.append("")

    (iteration_dir / "benchmark.md").write_text("\n".join(md_lines) + "\n")


def append_progression(iteration_dir: Path, benchmark: dict[str, Any]) -> None:
    skill_dir = iteration_dir
    while skill_dir.parent != skill_dir and skill_dir.name != "value-proof":
        skill_dir = skill_dir.parent
    if skill_dir.name != "value-proof":
        # Fall back to skill path from metadata.
        skill_dir = Path(benchmark["metadata"].get("skill_path", "."))
    history_path = skill_dir / "history" / "progression.json"
    history = _read_json(history_path) or {"skill_name": benchmark["metadata"]["skill_name"], "benchmarks": [], "case_history": {}}
    history.setdefault("benchmarks", [])
    delta = benchmark["run_summary"].get("delta", {})
    paired = benchmark.get("paired_stats", {}).get("outcome_pass_rate_paired", {})
    history["benchmarks"].append({
        "iteration": Path(benchmark["metadata"]["iteration_dir"]).name,
        "timestamp": benchmark["metadata"]["timestamp"],
        "target_repo": benchmark["metadata"].get("target_repo", ""),
        "git_sha_target": benchmark["metadata"].get("git_sha_target", ""),
        "with_skill_outcome_pass_rate": benchmark["run_summary"].get("with_skill", {}).get("outcome_pass_rate", {}).get("mean", 0.0),
        "without_skill_outcome_pass_rate": benchmark["run_summary"].get("without_skill", {}).get("outcome_pass_rate", {}).get("mean", 0.0),
        "delta_outcome_pass_rate": delta.get("outcome_pass_rate", "?"),
        "p_value": paired.get("p_value"),
        "n_pairs": paired.get("n_pairs"),
    })
    history["updated_at"] = benchmark["metadata"]["timestamp"]
    history_path.parent.mkdir(parents=True, exist_ok=True)
    history_path.write_text(json.dumps(history, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Aggregate value-proof results with paired statistics.")
    parser.add_argument("iteration_dir", type=Path)
    parser.add_argument("--skill-name", default="kast-value-proof")
    parser.add_argument("--bindings", type=Path, default=None)
    parser.add_argument("--catalog", type=Path, default=None)
    parser.add_argument("--no-progression", action="store_true")
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
    if not args.no_progression:
        append_progression(args.iteration_dir, benchmark)
    print(f"wrote {args.iteration_dir / 'benchmark.json'}")
    print(f"wrote {args.iteration_dir / 'benchmark.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
