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
- Detects contradictions (passed=true with evidence like "refs found = 0" / "missing" / "no ...").
- Sets schema_version=2 and integrity.attempts/flaky/git_sha_post if available.

The script is idempotent: running it twice produces the same output, modulo
the grader subprocess that produced grading.json.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

CONTRADICTION_PATTERNS = [
    re.compile(r"\b(?:found|present|counts?|refs?|references?|citations?)\s*=\s*0\b", re.IGNORECASE),
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


def _short_text(value: Any, *, limit: int = 500) -> str:
    text = str(value or "").strip()
    if len(text) <= limit:
        return text
    return text[-limit:]


def _event_message(event: dict[str, Any]) -> str:
    data = event.get("data")
    if not isinstance(data, dict):
        return _short_text(event)
    for key in ("message", "error", "reason"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return _short_text(value)
        if isinstance(value, dict):
            nested = value.get("message") or value.get("error") or value.get("reason")
            if nested:
                return _short_text(nested)
    return _short_text(data)


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


def _post_run_state(mechanical_source: dict[str, Any]) -> dict[str, Any]:
    repo_state = mechanical_source.get("repo_state", {})
    if not isinstance(repo_state, dict):
        return {}
    post_run = repo_state.get("post_run", {})
    return post_run if isinstance(post_run, dict) else {}


def _post_run_git_sha(
    source_integrity: dict[str, Any],
    post_run: dict[str, Any],
    workspace_root: Path | None,
) -> str:
    for value in (source_integrity.get("git_sha_post"), post_run.get("sha")):
        text = str(value or "").strip()
        if text:
            return text
    if workspace_root and workspace_root.exists():
        return _git_head(workspace_root)
    return ""


def _post_run_workspace_dirty(
    source_integrity: dict[str, Any],
    post_run: dict[str, Any],
    workspace_root: Path | None,
) -> bool | None:
    for value in (source_integrity.get("workspace_dirty_post"), post_run.get("dirty")):
        if isinstance(value, bool):
            return value
    if workspace_root and workspace_root.exists():
        return _workspace_dirty(workspace_root)
    return None


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


def maybe_cleanup_worktree(mechanical_payload: dict[str, Any], *, preserve_requested: bool) -> bool:
    worktree_text = str(
        mechanical_payload.get("identity", {}).get("worktree_path")
        or mechanical_payload.get("repo_state", {}).get("worktree_path")
        or mechanical_payload.get("repo_state", {}).get("post_run", {}).get("worktree_path")
        or ""
    ).strip()
    if not worktree_text:
        return False
    worktree = Path(worktree_text)
    if preserve_requested or not worktree.exists():
        return preserve_requested and worktree.exists()
    try:
        subprocess.run(
            ["git", "-C", str(worktree), "worktree", "remove", "--force", str(worktree)],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return False
    return False


def finalize(run_dir: Path, *, workspace_root: Path | None = None) -> dict[str, Any]:
    grading_path = run_dir / "grading.json"
    timing_path = run_dir / "timing.json"
    metadata_path = run_dir.parents[1] / "eval_metadata.json"

    grading = _read_json(grading_path)
    mechanical_path = run_dir / "mechanical.json"
    llm_grade_path = run_dir / "llm-grade.json"
    mechanical = _read_json(mechanical_path)
    llm_graded = _read_json(llm_grade_path)
    timing = _read_json(timing_path)
    metadata = _read_json(metadata_path)

    configuration = run_dir.parent.name  # eval-x/<config>/run-N
    expectation_meta = _expectation_meta(metadata)
    mechanical_source = mechanical or grading.get("mechanical") or grading
    llm_source = llm_graded or grading.get("llm_graded") or (grading if grading else {})
    raw_mechanical_expectations = mechanical_source.get("expectations") or []
    raw_llm_expectations = llm_source.get("expectations") or []

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
    transcript_present = transcript_chars > 0
    sdk_events = _read_jsonl(run_dir / "sdk-events.jsonl")
    hook_error_events = [
        event
        for event in sdk_events
        if event.get("type") == "hook.end"
        and isinstance(event.get("data"), dict)
        and event.get("data", {}).get("success") is False
    ]
    session_error_events = [event for event in sdk_events if event.get("type") == "session.error"]

    mechanical_expectations, contradictions = normalize_expectations(
        raw_mechanical_expectations,
        expectation_meta,
        configuration,
    )
    llm_expectations, llm_contradictions = normalize_expectations(
        raw_llm_expectations,
        expectation_meta,
        configuration,
    )
    combined_expectations = [*mechanical_expectations, *llm_expectations]
    summary_block = compute_summary(combined_expectations)
    mechanical_summary = compute_summary(mechanical_expectations)
    llm_summary = compute_summary(llm_expectations)

    timing_block = merge_timing(mechanical_source, timing)

    source_integrity = mechanical_source.get("integrity", {}) if isinstance(mechanical_source.get("integrity"), dict) else {}
    post_run = _post_run_state(mechanical_source)
    mock_backend_error_samples = [
        str(item)
        for item in source_integrity.get("mock_backend_error_samples", [])
        if str(item).strip()
    ]
    integrity: dict[str, Any] = {
        "contradictions": contradictions + llm_contradictions,
        "baseline_isolation_violation": configuration == "without_skill" and kast_calls > 0,
        "attempts": int(timing.get("attempts", 1) or 1),
        "flaky": int(timing.get("attempts", 1) or 1) > 1,
        "executor_status": str(timing.get("status", "") or "").strip() or "unknown",
        "executor_exit_code": timing.get("last_exit_code"),
        "executor_message": _short_text(timing.get("message", "")),
        "transcript_present": transcript_present,
        "hook_error_count": len(hook_error_events),
        "hook_error_samples": [_event_message(event) for event in hook_error_events[:3]],
        "session_error_count": len(session_error_events),
        "session_error_samples": [_event_message(event) for event in session_error_events[:3]],
        "mock_backend_error_count": int(source_integrity.get("mock_backend_error_count", 0) or 0),
        "mock_backend_error_samples": mock_backend_error_samples[:3],
    }
    git_sha_post = _post_run_git_sha(source_integrity, post_run, workspace_root)
    if git_sha_post:
        integrity["git_sha_post"] = git_sha_post
    workspace_dirty_post = _post_run_workspace_dirty(source_integrity, post_run, workspace_root)
    if workspace_dirty_post is not None:
        integrity["workspace_dirty_post"] = workspace_dirty_post

    execution_metrics = {
        "tool_calls": by_tool,
        "tool_call_log": "outputs/tool_calls.jsonl",
        "total_tool_calls": sum(by_tool.values()),
        "total_steps": int(mechanical_source.get("execution_metrics", {}).get("total_steps", 0) or 0),
        "errors_encountered": int(mechanical_source.get("execution_metrics", {}).get("errors_encountered", 0) or 0),
        "output_chars": int(
            mechanical_source.get("execution_metrics", {}).get("output_chars", transcript_chars) or transcript_chars
        ),
        "transcript_chars": transcript_chars,
        "kast_calls": kast_calls,
        "grep_or_find_calls": search_calls,
    }
    mechanical_payload = {
        **{k: v for k, v in mechanical_source.items() if k not in {"expectations", "summary", "execution_metrics", "timing", "integrity"}},
        "status": mechanical_source.get("status") or "graded",
        "expectations": mechanical_expectations,
        "summary": mechanical_summary,
        "execution_metrics": execution_metrics,
        "timing": timing_block,
        "integrity": integrity,
    }
    llm_payload = {
        **{k: v for k, v in llm_source.items() if k not in {"expectations", "summary"}},
        "status": llm_source.get("status") or "not_requested",
        "expectations": llm_expectations,
        "summary": llm_summary,
    }
    combined_payload = {
        "status": "graded",
        "expectations": combined_expectations,
        "summary": summary_block,
    }
    preserve_requested = os.getenv("KAST_EVAL_PRESERVE_WORKTREES", "").strip() == "1"
    repo_state = mechanical_payload.get("repo_state", {}) or {}
    repo_state["worktree_preserved_for_debugging"] = maybe_cleanup_worktree(
        mechanical_payload,
        preserve_requested=preserve_requested,
    )
    mechanical_payload["repo_state"] = repo_state

    finalized = {
        "$schema": "https://github.com/amichne/kast/evaluation/grading.schema.json",
        "schema_version": 3,
        "status": (
            mechanical_source.get("status") or "graded"
            if grading.get("status") == "pending_grading"
            else grading.get("status") or mechanical_source.get("status") or "graded"
        ),
        "mechanical": mechanical_payload,
        "llm_graded": llm_payload,
        "combined": combined_payload,
        "expectations": combined_expectations,
        "summary": summary_block,
        "execution_metrics": execution_metrics,
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
