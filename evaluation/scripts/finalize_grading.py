#!/usr/bin/env python3
"""Finalize an evaluation run's grading.json.

Loads:
- run_dir/grading.json          (raw output from the LLM grader, schema v1 or partial v2)
- run_dir/timing.json            (dispatcher-recorded executor duration, attempts)
- run_dir/outputs/tool_calls.jsonl (parse_tool_calls.py output)
- eval_dir/eval_metadata.json    (configuration, applicability map)

Writes a normalized grading.json that:
- Merges authoritative dispatcher timing into timing.executor_duration_seconds.
- Replaces grader-reported tool counts with the deterministic JSONL counts.
- Marks expectations skipped=true when applicability does not match the run config.
- Computes summary.outcome_pass_rate (fair cross-config metric).
- Detects baseline-isolation violations (without_skill but kast_calls > 0).
- Detects contradictions (passed=true with evidence "= 0" / "missing" / "no ...").
- Sets schema_version=2 and integrity.attempts/flaky/git_sha_post if available.

The script is idempotent: running it twice produces the same output, modulo
the grader subprocess that produced grading.json.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

CONTRADICTION_PATTERNS = [
    re.compile(r"=\s*0\b"),                    # "file:line refs found = 0"
    re.compile(r"\bno\s+\w+\s+(found|present)", re.IGNORECASE),
    re.compile(r"\bmissing\b", re.IGNORECASE),
    re.compile(r"\bnot\s+(?:present|found|cited)\b", re.IGNORECASE),
    re.compile(r"\bcounts?\s+present\b", re.IGNORECASE),  # iter-001 false-positive shape
]


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    out = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            out.append(obj)
    return out


def _expectation_meta(metadata: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Return id-or-text -> {kind, applicability, graded_by, oracle}."""
    out: dict[str, dict[str, Any]] = {}
    for spec in metadata.get("assertions", []) or []:
        if isinstance(spec, str):
            out[spec] = {
                "kind": "outcome",
                "applicability": "both",
                "graded_by": "llm",
            }
            continue
        if not isinstance(spec, dict):
            continue
        text = spec.get("text") or spec.get("id")
        if not text:
            continue
        record = {
            "kind": spec.get("kind", "outcome"),
            "applicability": spec.get("applicability", "both"),
            "graded_by": spec.get("graded_by", "llm"),
        }
        if "id" in spec:
            record["id"] = spec["id"]
        if "oracle" in spec:
            record["oracle"] = spec["oracle"]
        out[text] = record
        if "id" in spec:
            out[spec["id"]] = record
    return out


def _git_head(workspace_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(workspace_root),
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except OSError:
        pass
    return ""


def _workspace_dirty(workspace_root: Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=str(workspace_root),
            capture_output=True,
            text=True,
            check=False,
        )
        return bool(result.stdout.strip()) if result.returncode == 0 else False
    except OSError:
        return False


def normalize_expectations(
    raw: list[dict[str, Any]],
    meta: dict[str, dict[str, Any]],
    configuration: str,
) -> tuple[list[dict[str, Any]], list[str]]:
    contradictions: list[str] = []
    out: list[dict[str, Any]] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        text = str(entry.get("text", "")).strip()
        if not text:
            continue
        info = meta.get(text) or meta.get(entry.get("id", "")) or {}
        kind = entry.get("kind", info.get("kind", "outcome"))
        applicability = entry.get("applicability", info.get("applicability", "both"))
        graded_by = entry.get("graded_by", info.get("graded_by", "llm"))
        passed = bool(entry.get("passed", False))
        evidence = str(entry.get("evidence", ""))

        skipped = False
        if applicability == "with_skill_only" and configuration != "with_skill":
            skipped = True
        elif applicability == "without_skill_only" and configuration != "without_skill":
            skipped = True

        if not skipped and passed and evidence:
            for pattern in CONTRADICTION_PATTERNS:
                if pattern.search(evidence):
                    contradictions.append(
                        f"expectation '{text[:80]}' marked passed but evidence matches '{pattern.pattern}': {evidence!r}"
                    )
                    break

        record: dict[str, Any] = {
            "text": text,
            "passed": passed,
            "evidence": evidence,
            "kind": kind,
            "applicability": applicability,
            "graded_by": graded_by,
            "skipped": skipped,
        }
        if "id" in entry:
            record["id"] = entry["id"]
        elif "id" in info:
            record["id"] = info["id"]
        if "oracle" in entry:
            record["oracle"] = entry["oracle"]
        elif "oracle" in info:
            record["oracle"] = info["oracle"]
        out.append(record)
    return out, contradictions


def compute_summary(expectations: list[dict[str, Any]]) -> dict[str, Any]:
    total = sum(1 for e in expectations if not e.get("skipped"))
    passed = sum(1 for e in expectations if not e.get("skipped") and e.get("passed"))
    failed = total - passed

    outcome_total = sum(
        1
        for e in expectations
        if not e.get("skipped") and e.get("kind") == "outcome" and e.get("applicability") == "both"
    )
    outcome_passed = sum(
        1
        for e in expectations
        if not e.get("skipped")
        and e.get("kind") == "outcome"
        and e.get("applicability") == "both"
        and e.get("passed")
    )

    process_total = sum(1 for e in expectations if not e.get("skipped") and e.get("kind") == "process")
    process_passed = sum(
        1 for e in expectations if not e.get("skipped") and e.get("kind") == "process" and e.get("passed")
    )

    return {
        "passed": passed,
        "failed": failed,
        "total": total,
        "pass_rate": (passed / total) if total else 0.0,
        "outcome_passed": outcome_passed,
        "outcome_total": outcome_total,
        "outcome_pass_rate": (outcome_passed / outcome_total) if outcome_total else 0.0,
        "process_pass_rate": (process_passed / process_total) if process_total else 0.0,
        "skipped": sum(1 for e in expectations if e.get("skipped")),
    }


def merge_timing(grading: dict[str, Any], timing: dict[str, Any]) -> dict[str, Any]:
    executor = timing.get("executor_duration_seconds")
    grader_existing = grading.get("timing", {}).get("grader_duration_seconds", 0.0) or 0.0
    if executor is None or executor == 0.0:
        # Fall back to grader-reported value, mark source as missing.
        executor = grading.get("timing", {}).get("executor_duration_seconds", 0.0) or 0.0
        source = "missing" if executor == 0.0 else "self_reported"
    else:
        source = "dispatcher"
    return {
        "executor_duration_seconds": float(executor),
        "grader_duration_seconds": float(grader_existing),
        "total_duration_seconds": float(executor) + float(grader_existing),
        "executor_duration_source": source,
    }


def finalize(run_dir: Path, *, workspace_root: Path | None = None) -> dict[str, Any]:
    grading_path = run_dir / "grading.json"
    timing_path = run_dir / "timing.json"
    metadata_path = run_dir.parents[1] / "eval_metadata.json"

    grading = _read_json(grading_path)
    timing = _read_json(timing_path)
    metadata = _read_json(metadata_path)

    configuration = run_dir.parent.name  # eval-x/<config>/run-N
    expectation_meta = _expectation_meta(metadata)
    raw_expectations = grading.get("expectations") or []

    # Tool calls — authoritative source is tool_calls.jsonl.
    tool_calls = _read_jsonl(run_dir / "outputs" / "tool_calls.jsonl")
    by_tool: dict[str, int] = {}
    kast_calls = 0
    search_calls = 0
    KAST_RE = re.compile(r"^kast(_[a-z_][a-z_0-9]*)?$", re.IGNORECASE)
    for call in tool_calls:
        name = call.get("tool", "")
        if not name:
            continue
        by_tool[name] = by_tool.get(name, 0) + 1
        if KAST_RE.match(name):
            kast_calls += 1
        if call.get("source") == "bash_search" or name in {"grep", "rg", "ripgrep", "find", "ls"}:
            search_calls += 1

    transcript_path = run_dir / "outputs" / "transcript.md"
    transcript_chars = transcript_path.stat().st_size if transcript_path.exists() else 0

    expectations, contradictions = normalize_expectations(raw_expectations, expectation_meta, configuration)
    summary_block = compute_summary(expectations)

    timing_block = merge_timing(grading, timing)

    integrity: dict[str, Any] = {
        "contradictions": contradictions,
        "baseline_isolation_violation": configuration == "without_skill" and kast_calls > 0,
        "attempts": int(timing.get("attempts", 1) or 1),
        "flaky": int(timing.get("attempts", 1) or 1) > 1,
    }
    if workspace_root and workspace_root.exists():
        integrity["git_sha_post"] = _git_head(workspace_root)
        integrity["workspace_dirty_post"] = _workspace_dirty(workspace_root)

    finalized = {
        "schema_version": 2,
        "status": grading.get("status") or "graded",
        "expectations": expectations,
        "summary": summary_block,
        "execution_metrics": {
            "tool_calls": by_tool,
            "tool_call_log": "outputs/tool_calls.jsonl",
            "total_tool_calls": sum(by_tool.values()),
            "total_steps": int(grading.get("execution_metrics", {}).get("total_steps", 0) or 0),
            "errors_encountered": int(grading.get("execution_metrics", {}).get("errors_encountered", 0) or 0),
            "output_chars": int(grading.get("execution_metrics", {}).get("output_chars", transcript_chars) or transcript_chars),
            "transcript_chars": transcript_chars,
            "kast_calls": kast_calls,
            "grep_or_find_calls": search_calls,
        },
        "timing": timing_block,
        "integrity": integrity,
    }

    grading_path.write_text(json.dumps(finalized, indent=2) + "\n")
    return finalized


def main() -> int:
    parser = argparse.ArgumentParser(description="Finalize an evaluation run's grading.json.")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--workspace-root", type=Path, default=None, help="Target Kotlin workspace; used to record git SHA + dirty state.")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero when contradictions or isolation violations are detected.")
    args = parser.parse_args()

    if not (args.run_dir / "grading.json").exists():
        print(f"error: missing grading.json in {args.run_dir}", file=sys.stderr)
        return 2

    finalized = finalize(args.run_dir, workspace_root=args.workspace_root)
    integrity = finalized.get("integrity", {})
    contradictions = integrity.get("contradictions", [])
    isolation_violation = integrity.get("baseline_isolation_violation", False)

    if contradictions:
        print(f"warning: {len(contradictions)} contradiction(s) in {args.run_dir}:", file=sys.stderr)
        for note in contradictions:
            print(f"  - {note}", file=sys.stderr)
    if isolation_violation:
        print(f"warning: baseline isolation violated in {args.run_dir} (kast_calls > 0 in without_skill)", file=sys.stderr)

    if args.strict and (contradictions or isolation_violation):
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
