#!/usr/bin/env python3
"""Deterministic grader for catalog expectations marked ``graded_by=script``.

The Copilot runner can produce transcripts without an external LLM grader.
This script turns those transcripts, tool-call logs, bindings, and
eval_metadata.json assertions into a complete grading.json for the scriptable
portion of each case. Expectations marked ``graded_by=llm`` are intentionally
left out; they require an explicit grader.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from parse_tool_calls import parse_run_dir
from render_prompts import resolve_binding


LINE_CITATION_RE = re.compile(
    r"(?P<path>(?:/[^:\s]+|[\w./-]+\.kts?))"
    r"(?:(?::(?P<colon_line>\d+))|\s*(?:-|--|\u2013|\u2014)\s*line\s+(?P<word_line>\d+))",
    re.IGNORECASE,
)
POSITIVE_COMPILE_RE = re.compile(
    r"\b(build successful|compiled successfully|compilation succeeded|no compile errors?)\b",
    re.IGNORECASE,
)
NEGATIVE_RE = re.compile(r"\b(error|failed|missing|not found|unresolved|exception)\b", re.IGNORECASE)


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            rows.append(payload)
    return rows


def worktree_path(mechanical: dict[str, Any]) -> Path | None:
    candidates = [
        mechanical.get("identity", {}).get("worktree_path"),
        mechanical.get("repo_state", {}).get("worktree_path"),
        mechanical.get("repo_state", {}).get("post_run", {}).get("worktree_path"),
    ]
    for candidate in candidates:
        text = str(candidate or "").strip()
        if text:
            return Path(text)
    return None


def run_harness_probe(command: str, cwd: Path) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        cwd=cwd,
        shell=True,
        text=True,
        capture_output=True,
        check=False,
    )
    return {
        "command": command,
        "cwd": str(cwd),
        "exit_code": completed.returncode,
        "stdout": completed.stdout[-1000:],
        "stderr": completed.stderr[-1000:],
        "final_status": "passed" if completed.returncode == 0 else "failed",
    }


def compile_probe(
    *,
    oracle: Any,
    mechanical: dict[str, Any],
    fallback_workspace_root: Path | None,
) -> tuple[bool, str, dict[str, Any] | None]:
    command = str(oracle or "").strip()
    if not command:
        return False, "Compile command oracle was empty.", None
    commands = mechanical.get("build_test_iterations", {}).get("commands", []) or []
    for entry in commands:
        if not isinstance(entry, dict):
            continue
        if str(entry.get("command", "")).strip() != command:
            continue
        exit_code = entry.get("exit_code")
        passed = exit_code == 0
        return passed, f"Agent command exit code = {exit_code}.", None
    cwd = worktree_path(mechanical) or fallback_workspace_root
    if cwd is None or not cwd.exists():
        return False, "No worktree available for harness compile probe.", None
    probe = run_harness_probe(command, cwd)
    return probe["exit_code"] == 0, f"Harness probe exit code = {probe['exit_code']}.", probe


def touched_files(mechanical: dict[str, Any]) -> list[str]:
    files = mechanical.get("repo_state", {}).get("post_run", {}).get("touched_files", [])
    return [str(item) for item in files if str(item).strip()]


def assistant_visible_text(transcript: str) -> tuple[str, bool]:
    """Return assistant-facing text from SDK JSONL transcripts.

    Raw SDK events include encrypted/reasoning/tool payloads that can contain
    arbitrary file:line-looking substrings. Outcome checks should grade the
    answer text the agent actually emitted, while legacy plain-text transcripts
    remain supported.
    """
    parts: list[str] = []
    parsed_jsonl = False
    for line in transcript.splitlines():
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        try:
            event = json.loads(stripped)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        parsed_jsonl = True
        if event.get("type") != "assistant.message":
            continue
        data = event.get("data")
        if not isinstance(data, dict):
            continue
        content = data.get("content")
        if isinstance(content, str) and content.strip():
            parts.append(content.strip())
    return "\n\n".join(parts), parsed_jsonl


def outcome_grading_text(transcript: str) -> str:
    assistant_text, parsed_jsonl = assistant_visible_text(transcript)
    if assistant_text:
        return assistant_text
    return "" if parsed_jsonl else transcript


def flatten_expected(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value else []
    if isinstance(value, (int, float, bool)):
        return [str(value)]
    if isinstance(value, list):
        out: list[str] = []
        for item in value:
            out.extend(flatten_expected(item))
        return out
    if isinstance(value, dict):
        preferred = []
        for key in ("fqName", "file", "module", "symbol", "name"):
            item = value.get(key)
            if isinstance(item, (str, int, float, bool)) and str(item):
                preferred.append(str(item))
        if preferred:
            return preferred
        out: list[str] = []
        for item in value.values():
            out.extend(flatten_expected(item))
        return out
    return [str(value)]


def resolve_oracle(bindings: dict[str, Any], expression: str | None) -> Any:
    if not expression:
        return None
    return resolve_binding(bindings, expression)


def count_line_citations(transcript: str) -> int:
    return len(list(LINE_CITATION_RE.finditer(transcript)))


def citation_line_number(match: re.Match[str]) -> int:
    return int(match.group("colon_line") or match.group("word_line"))


def validate_line_citations(transcript: str, workspace_root: Path | None) -> tuple[bool, str]:
    citations = list(LINE_CITATION_RE.finditer(transcript))
    if not citations:
        return False, "No file:line citations found."
    if workspace_root is None:
        return True, f"{len(citations)} citation(s) found; no workspace root available for disk validation."

    invalid: list[str] = []
    for match in citations:
        raw_path = Path(match.group("path"))
        line_number = citation_line_number(match)
        path = raw_path if raw_path.is_absolute() else workspace_root / raw_path
        try:
            line_count = len(path.read_text(encoding="utf-8", errors="replace").splitlines())
        except OSError:
            invalid.append(f"{match.group(0)} missing file")
            continue
        if line_number < 1 or line_number > line_count:
            invalid.append(f"{match.group(0)} outside 1..{line_count}")
    if invalid:
        return False, "; ".join(invalid[:5])
    return True, f"{len(citations)} citation(s) resolve on disk."


def tool_names(tool_calls: list[dict[str, Any]]) -> list[str]:
    return [str(call.get("tool", "")).strip() for call in tool_calls if str(call.get("tool", "")).strip()]


def has_tool(names: list[str], *needles: str) -> bool:
    lowered = {name.lower().replace("-", "_") for name in names}
    return any(needle.lower().replace("-", "_") in lowered for needle in needles)


def grade_process(expectation_id: str, names: list[str]) -> tuple[bool, str]:
    checks = {
        "pm-uses-resolve": ("kast_resolve",),
        "pf-disambiguates": ("kast_resolve", "kast_callers"),
        "pi-resolve-first": ("kast_resolve",),
        "ps-semantic": ("kast_resolve", "kast_references", "kast_workspace_symbol", "kast_callers"),
        "pr-uses-kast-rename": ("kast_rename",),
        "pe-uses-kast-write-validate": ("kast_write_and_validate",),
        "pl-uses-scaffold": ("kast_scaffold",),
        "pc-trio": ("kast_resolve", "kast_references", "kast_callers"),
    }
    if expectation_id == "pw-single-call":
        count = sum(1 for name in names if name.lower().replace("-", "_") == "kast_workspace_files")
        return count == 1, f"kast_workspace_files call count = {count}."
    required = checks.get(expectation_id)
    if not required:
        return False, f"No deterministic process check is defined for {expectation_id}."
    present = [name for name in required if has_tool(names, name)]
    return bool(present), f"Observed tools: {', '.join(names) if names else 'none'}."


def grade_oracle_expectation(
    *,
    expectation_id: str,
    oracle: Any,
    transcript: str,
    mechanical: dict[str, Any],
    workspace_root: Path | None,
) -> tuple[bool, str]:
    if expectation_id in {"or-compiles", "oe-compiles"}:
        passed, evidence, _ = compile_probe(
            oracle=oracle,
            mechanical=mechanical,
            fallback_workspace_root=workspace_root,
        )
        return passed, evidence
    if expectation_id == "or-files-touched":
        expected = flatten_expected(oracle)
        touched = touched_files(mechanical)
        missing = [item for item in expected if item not in touched]
        return not missing, "All expected files were touched." if not missing else f"Missing touched files: {', '.join(missing[:5])}."
    if expectation_id == "or-files-extra":
        expected = set(flatten_expected(oracle))
        touched = touched_files(mechanical)
        extra = [item for item in touched if item not in expected]
        return not extra, "No unexpected files were touched." if not extra else f"Unexpected touched files: {', '.join(extra[:5])}."
    if isinstance(oracle, int):
        if expectation_id == "ol-token-savings":
            line_count = len([line for line in transcript.splitlines() if line.strip()])
            return line_count < oracle, f"Answer line count = {line_count}; raw file line count = {oracle}."
        count = count_line_citations(transcript)
        return count >= oracle, f"Found {count} file:line citation(s); expected at least {oracle}."

    expected = flatten_expected(oracle)
    if expectation_id in {"om-precision"} or "decoy" in expectation_id or "extra" in expectation_id:
        present = [item for item in expected if item and item in transcript]
        return not present, (
            "No forbidden values appeared." if not present else f"Forbidden values appeared: {', '.join(present[:5])}."
        )

    missing = [item for item in expected if item and item not in transcript]
    return not missing, (
        "All expected values appeared." if not missing else f"Missing expected values: {', '.join(missing[:5])}."
    )


def grade_no_oracle(
    *,
    expectation_id: str,
    transcript: str,
    workspace_root: Path | None,
) -> tuple[bool, str]:
    if expectation_id.endswith("citations-resolve") or expectation_id in {"oc-concrete-paths"}:
        return validate_line_citations(transcript, workspace_root)
    if expectation_id.endswith("compiles") or expectation_id in {"or-compiles", "oe-compiles"}:
        passed = bool(POSITIVE_COMPILE_RE.search(transcript)) and not bool(NEGATIVE_RE.search(transcript))
        return passed, "Compile success phrase found." if passed else "No deterministic compile-success evidence found."
    if expectation_id in {"or-imports-updated", "oe-annotation-present", "oe-no-orphan-edits", "oi-test-prod-split"}:
        passed = not bool(NEGATIVE_RE.search(transcript))
        return passed, "No negative evidence phrase found." if passed else "Negative evidence phrase found."
    if expectation_id in {"ol-no-hallucination", "ow-no-extra-modules"}:
        passed = not bool(re.search(r"\b(extra|unknown|hallucinat)\w*\b", transcript, re.IGNORECASE))
        return passed, "No extra or hallucination marker found." if passed else "Extra or hallucination marker found."
    return False, f"No deterministic outcome check is defined for {expectation_id}."


def compute_summary(expectations: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(expectations)
    passed = sum(1 for entry in expectations if entry.get("passed") is True)
    failed = total - passed
    outcome_total = sum(1 for entry in expectations if entry.get("kind") == "outcome")
    outcome_passed = sum(1 for entry in expectations if entry.get("kind") == "outcome" and entry.get("passed") is True)
    process_total = sum(1 for entry in expectations if entry.get("kind") == "process")
    process_passed = sum(1 for entry in expectations if entry.get("kind") == "process" and entry.get("passed") is True)
    return {
        "passed": passed,
        "failed": failed,
        "total": total,
        "pass_rate": (passed / total) if total else 0.0,
        "outcome_passed": outcome_passed,
        "outcome_total": outcome_total,
        "outcome_pass_rate": (outcome_passed / outcome_total) if outcome_total else 0.0,
        "process_pass_rate": (process_passed / process_total) if process_total else 0.0,
        "skipped": 0,
    }


def grade(run_dir: Path, bindings_path: Path) -> dict[str, Any]:
    start = time.time()
    metadata = read_json(run_dir.parents[1] / "eval_metadata.json")
    bindings = read_json(bindings_path)
    mechanical = read_json(run_dir / "mechanical.json")
    workspace_text = str(bindings.get("workspace_root", "")).strip()
    workspace_root = Path(workspace_text) if workspace_text else None
    transcript_path = run_dir / "outputs" / "transcript.md"
    transcript = transcript_path.read_text(encoding="utf-8", errors="replace") if transcript_path.exists() else ""
    outcome_text = outcome_grading_text(transcript)

    try:
        metrics = parse_run_dir(run_dir)
    except OSError:
        metrics = {}
    names = tool_names(read_jsonl(run_dir / "outputs" / "tool_calls.jsonl"))
    harness_validation: dict[str, Any] = {}

    graded: list[dict[str, Any]] = []
    for spec in metadata.get("assertions", []) or []:
        if not isinstance(spec, dict) or spec.get("graded_by") != "script":
            continue
        expectation_id = str(spec.get("id") or spec.get("text") or "").strip()
        if not expectation_id:
            continue
        try:
            oracle = resolve_oracle(bindings, spec.get("oracle"))
        except ValueError as exc:
            passed = False
            evidence = f"Oracle resolution failed: {exc}"
        else:
            if spec.get("kind") == "process":
                passed, evidence = grade_process(expectation_id, names)
            elif spec.get("oracle"):
                passed, evidence = grade_oracle_expectation(
                    expectation_id=expectation_id,
                    oracle=oracle,
                    transcript=outcome_text,
                    mechanical=mechanical,
                    workspace_root=worktree_path(mechanical) or workspace_root,
                )
                if expectation_id in {"or-compiles", "oe-compiles"}:
                    _, _, probe = compile_probe(
                        oracle=oracle,
                        mechanical=mechanical,
                        fallback_workspace_root=worktree_path(mechanical) or workspace_root,
                    )
                    if probe is not None:
                        harness_validation["compile_probe"] = probe
            else:
                passed, evidence = grade_no_oracle(
                    expectation_id=expectation_id,
                    transcript=outcome_text,
                    workspace_root=workspace_root,
                )
        record = {
            "id": expectation_id,
            "text": str(spec.get("text") or expectation_id),
            "passed": passed,
            "evidence": evidence,
            "kind": spec.get("kind", "outcome"),
            "applicability": spec.get("applicability", "both"),
            "graded_by": "script",
        }
        if "oracle" in spec:
            record["oracle"] = spec["oracle"]
        if "dimension" in spec:
            record["dimension"] = spec["dimension"]
        graded.append(record)

    elapsed = max(0.0, time.time() - start)
    payload = {
        "$schema": "https://github.com/amichne/kast/evaluation/mechanical.schema.json",
        "schema_version": 1,
        "status": "graded",
        "expectations": graded,
        "summary": compute_summary(graded),
        "execution_metrics": {
            "tool_calls": metrics.get("tool_calls", {}),
            "tool_call_log": metrics.get("tool_call_log", "outputs/tool_calls.jsonl"),
            "total_tool_calls": int(metrics.get("total_tool_calls", 0) or 0),
            "total_steps": 0,
            "errors_encountered": 0,
            "output_chars": len(outcome_text),
            "transcript_chars": len(transcript),
            "kast_calls": int(metrics.get("kast_calls", 0) or 0),
            "grep_or_find_calls": int(metrics.get("grep_or_find_calls", 0) or 0),
        },
        "timing": {
            "executor_duration_seconds": 0.0,
            "grader_duration_seconds": elapsed,
            "total_duration_seconds": elapsed,
            "executor_duration_source": "missing",
        },
        "harness_validation": harness_validation,
    }
    return payload


def build_llm_grade_input(
    *,
    run_dir: Path,
    bindings_path: Path,
    mechanical: dict[str, Any],
) -> dict[str, Any]:
    metadata = read_json(run_dir.parents[1] / "eval_metadata.json")
    final_answer_path = run_dir / "final-answer.md"
    final_answer = final_answer_path.read_text(encoding="utf-8", errors="replace").strip() if final_answer_path.exists() else ""
    if not final_answer:
        transcript_path = run_dir / "outputs" / "transcript.md"
        transcript = transcript_path.read_text(encoding="utf-8", errors="replace") if transcript_path.exists() else ""
        final_answer = outcome_grading_text(transcript)
    llm_expectations = [
        expectation
        for expectation in metadata.get("assertions", []) or []
        if isinstance(expectation, dict) and expectation.get("graded_by") == "llm"
    ]
    return {
        "schema_version": 1,
        "status": "ready",
        "prompt": str(metadata.get("prompt", "")),
        "final_answer": final_answer,
        "case_rubric": llm_expectations,
        "mechanical_summary": mechanical.get("summary", {}),
        "relevant_oracle_results": mechanical.get("expectations", []),
        "bindings_path": str(bindings_path),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Grade script-backed evaluation expectations.")
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--bindings", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=None, help="Output grading path; defaults to RUN_DIR/mechanical.json")
    parser.add_argument(
        "--llm-grade-input-output",
        type=Path,
        default=None,
        help="Output llm-grade-input path; defaults to RUN_DIR/llm-grade-input.json",
    )
    args = parser.parse_args(argv)

    payload = grade(args.run_dir, args.bindings)
    output = args.output or args.run_dir / "mechanical.json"
    existing = read_json(output)
    merged = {
        **existing,
        **payload,
        "expectations": payload.get("expectations", []),
        "summary": payload.get("summary", {}),
        "execution_metrics": payload.get("execution_metrics", {}),
        "timing": payload.get("timing", {}),
    }
    output.write_text(json.dumps(merged, indent=2) + "\n")
    llm_input_output = args.llm_grade_input_output or args.run_dir / "llm-grade-input.json"
    llm_input_output.write_text(json.dumps(build_llm_grade_input(run_dir=args.run_dir, bindings_path=args.bindings, mechanical=merged), indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
