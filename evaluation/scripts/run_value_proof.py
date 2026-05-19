#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

GRADING_SCHEMA_PATH = Path(__file__).resolve().parents[1] / "grading.schema.json"


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    if not isinstance(payload.get("cases"), list):
        raise ValueError(f"{path} must contain a cases array.")
    return payload


def load_grading_schema() -> dict[str, Any]:
    try:
        payload = json.loads(GRADING_SCHEMA_PATH.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {GRADING_SCHEMA_PATH}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{GRADING_SCHEMA_PATH} must contain a JSON object.")
    return payload


def run_instruction_text(config: str, prompt: str) -> str:
    if config == "with_skill":
        setup = "Open a Copilot Chat session with the Kast skill loaded."
    elif config == "tool_only":
        setup = "Open a Copilot Chat session with kast_* custom tools registered, but without loading the Kast skill instructions."
    elif config == "skill_only":
        setup = "Open a Copilot Chat session with the Kast skill loaded, but with kast_* custom tools disabled."
    elif config == "without_skill":
        setup = "Open a Copilot Chat session WITHOUT the Kast skill (or with Kast tools disabled)."
    else:
        setup = f"Open a Copilot Chat session for configuration `{config}`."
    return "\n".join(
        [
            f"# Run instructions: {config}",
            "",
            f"{setup}",
            "",
            "Paste this prompt:",
            "",
            "```text",
            prompt,
            "```",
            "",
            "Save the full transcript to `outputs/transcript.md`.",
            "After the grader runs, replace `grading.json` with the grader output and update `timing.json`.",
            "",
        ]
    )


def metadata_for_case(case: dict[str, Any]) -> dict[str, Any]:
    """Embed the full structured expectation list (with kind/applicability/oracle/graded_by)
    so finalize_grading.py can normalize raw grader output without re-loading the catalog."""
    expectations = case.get("expectations", []) or []
    return {
        "eval_id": case["id"],
        "eval_name": case.get("title", case["id"]),
        "prompt": case["prompt"],
        "assertions": expectations,
        "expected_output": case.get("expected_output", ""),
        "labels": case.get("labels", []),
        "stage": case.get("stage", "candidate"),
        "chain_id": case.get("chain_id"),
    }


def write_placeholder_grading(path: Path) -> None:
    schema = load_grading_schema()
    empty_summary = {
        "passed": 0,
        "failed": 0,
        "total": 0,
        "pass_rate": 0.0,
        "outcome_passed": 0,
        "outcome_total": 0,
        "outcome_pass_rate": 0.0,
        "process_pass_rate": 0.0,
        "skipped": 0,
    }
    empty_execution = {
        "tool_calls": {},
        "tool_call_log": "outputs/tool_calls.jsonl",
        "total_tool_calls": 0,
        "total_steps": 0,
        "errors_encountered": 0,
        "output_chars": 0,
        "transcript_chars": 0,
        "kast_calls": 0,
        "grep_or_find_calls": 0,
    }
    empty_timing = {
        "executor_duration_seconds": 0.0,
        "grader_duration_seconds": 0.0,
        "total_duration_seconds": 0.0,
        "executor_duration_source": "missing",
    }
    empty_integrity = {
        "contradictions": [],
        "baseline_isolation_violation": False,
        "attempts": 1,
        "flaky": False,
    }
    payload = {
        "$schema": "https://github.com/amichne/kast/evaluation/grading.schema.json",
        "schema_version": 3,
        "status": "pending_grading",
        "mechanical": {
            "status": "pending_capture",
            "expectations": [],
            "summary": empty_summary,
            "execution_metrics": empty_execution,
            "timing": empty_timing,
            "integrity": empty_integrity,
        },
        "llm_graded": {
            "status": "pending_llm_grading",
            "expectations": [],
            "summary": empty_summary,
        },
        "combined": {
            "status": "pending_grading",
            "expectations": [],
            "summary": empty_summary,
        },
        "expectations": [],
        "summary": empty_summary,
        "execution_metrics": empty_execution,
        "timing": empty_timing,
        "integrity": empty_integrity,
    }
    missing = sorted(set(schema.get("required", [])) - set(payload))
    if missing:
        raise ValueError(f"Placeholder grading is missing schema fields: {', '.join(missing)}")
    path.write_text(json.dumps(payload, indent=2) + "\n")


def write_placeholder_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2) + "\n")


def write_placeholder_mechanical(path: Path) -> None:
    write_placeholder_json(
        path,
        {
            "$schema": "https://github.com/amichne/kast/evaluation/mechanical.schema.json",
            "schema_version": 1,
            "status": "pending_capture",
            "artifacts": {
                "inputs": "inputs.json",
                "sdk_events": "sdk-events.jsonl",
                "otel": "otel.jsonl",
                "final_answer": "final-answer.md",
                "timing": "timing.json",
            },
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
            "execution_metrics": {
                "tool_calls": {},
                "tool_call_log": "outputs/tool_calls.jsonl",
                "total_tool_calls": 0,
                "total_steps": 0,
                "errors_encountered": 0,
                "output_chars": 0,
                "transcript_chars": 0,
                "kast_calls": 0,
                "grep_or_find_calls": 0,
            },
            "timing": {
                "executor_duration_seconds": 0.0,
                "grader_duration_seconds": 0.0,
                "total_duration_seconds": 0.0,
            },
            "integrity": {
                "contradictions": [],
                "baseline_isolation_violation": False,
                "attempts": 1,
                "flaky": False,
            },
        },
    )


def write_placeholder_llm_grade(path: Path) -> None:
    write_placeholder_json(
        path,
        {
            "$schema": "https://github.com/amichne/kast/evaluation/llm-grade.schema.json",
            "schema_version": 1,
            "status": "pending_llm_grading",
            "rubric_results": [],
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
        },
    )


def write_placeholder_llm_grade_input(path: Path) -> None:
    write_placeholder_json(
        path,
        {
            "schema_version": 1,
            "status": "pending_mechanical_grading",
        },
    )


def write_placeholder_inputs(path: Path) -> None:
    write_placeholder_json(
        path,
        {
            "schema_version": 1,
            "status": "pending_capture",
        },
    )


def write_placeholder_timing(path: Path) -> None:
    payload = {
        "status": "pending_execution",
        "executor_duration_seconds": 0.0,
        "grader_duration_seconds": 0.0,
        "total_duration_seconds": 0.0,
        "total_tokens": 0,
    }
    path.write_text(json.dumps(payload, indent=2) + "\n")


def scaffold_workspace(
    *,
    catalog_path: Path,
    workspace_dir: Path,
    runs_per_config: int = 5,
    configs: list[str] | None = None,
    iteration: str = "iteration-001",
    aggregate: bool = True,
) -> Path:
    if runs_per_config < 1:
        raise ValueError("runs_per_config must be at least 1.")
    selected_configs = configs or ["with_skill", "tool_only", "without_skill"]
    if not selected_configs:
        raise ValueError("At least one configuration is required.")

    catalog = load_catalog(catalog_path)
    iteration_dir = workspace_dir / iteration
    iteration_dir.mkdir(parents=True, exist_ok=True)
    persist_iteration_inputs(iteration_dir=iteration_dir, catalog_path=catalog_path, catalog=catalog)
    instruction_paths: list[Path] = []
    eval_manifest: dict[str, dict[str, str | None]] = {}

    for case in catalog["cases"]:
        case_id = case["id"]
        eval_dir = iteration_dir / f"eval-{case_id}"
        eval_dir.mkdir(parents=True, exist_ok=True)
        (eval_dir / "eval_metadata.json").write_text(json.dumps(metadata_for_case(case), indent=2) + "\n")
        eval_manifest[case_id] = {
            "dir": eval_dir.name,
            "chain_id": case.get("chain_id"),
        }

        for config in selected_configs:
            for run_number in range(1, runs_per_config + 1):
                run_dir = eval_dir / config / f"run-{run_number}"
                outputs_dir = run_dir / "outputs"
                outputs_dir.mkdir(parents=True, exist_ok=True)
                (run_dir / "run_instructions.md").write_text(run_instruction_text(config, case["prompt"]))
                instruction_paths.append(run_dir / "run_instructions.md")
                transcript = outputs_dir / "transcript.md"
                if not transcript.exists():
                    transcript.write_text("")
                for text_artifact in ("sdk-events.jsonl", "otel.jsonl", "final-answer.md"):
                    artifact_path = run_dir / text_artifact
                    if not artifact_path.exists():
                        artifact_path.write_text("")
                placeholder_writers = (
                    ("inputs.json", write_placeholder_inputs),
                    ("mechanical.json", write_placeholder_mechanical),
                    ("llm-grade-input.json", write_placeholder_llm_grade_input),
                    ("llm-grade.json", write_placeholder_llm_grade),
                    ("grading.json", write_placeholder_grading),
                    ("timing.json", write_placeholder_timing),
                )
                for artifact_name, writer in placeholder_writers:
                    artifact_path = run_dir / artifact_name
                    if not artifact_path.exists():
                        writer(artifact_path)

    write_run_manifest(iteration_dir, instruction_paths)
    write_iteration_manifest(iteration_dir, eval_manifest)
    print(f"Run instructions written for {len(instruction_paths)} runs:")
    for path in instruction_paths:
        print(f"  {path}")

    if aggregate:
        aggregate_if_graded(iteration_dir, catalog.get("skill_name", "kast-value-proof"))
    return iteration_dir


def persist_iteration_inputs(
    *,
    iteration_dir: Path,
    catalog_path: Path,
    catalog: dict[str, Any],
) -> None:
    rendered_catalog_path = iteration_dir / "rendered-catalog.json"
    rendered_catalog_path.write_text(json.dumps(catalog, indent=2) + "\n")

    bindings = catalog.get("bindings")
    if isinstance(bindings, dict):
        (iteration_dir / "bindings.json").write_text(json.dumps(bindings, indent=2) + "\n")


def write_run_manifest(iteration_dir: Path, instruction_paths: list[Path]) -> None:
    payload = {
        "iteration": iteration_dir.name,
        "run_count": len(instruction_paths),
        "instructions": [
            str(path.relative_to(iteration_dir))
            for path in instruction_paths
        ],
    }
    (iteration_dir / "run_manifest.json").write_text(json.dumps(payload, indent=2) + "\n")


def write_iteration_manifest(iteration_dir: Path, eval_manifest: dict[str, dict[str, str | None]]) -> None:
    payload = {
        "evals": eval_manifest,
    }
    (iteration_dir / "manifest.json").write_text(json.dumps(payload, indent=2) + "\n")


def grading_is_complete(path: Path) -> bool:
    try:
        payload = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return False
    if not isinstance(payload, dict):
        return False
    if payload.get("status") == "pending_grading" and not isinstance(payload.get("combined"), dict):
        return False
    combined = payload.get("combined", {})
    summary = payload.get("summary") or (combined.get("summary") if isinstance(combined, dict) else None)
    expectations = payload.get("expectations") or (combined.get("expectations") if isinstance(combined, dict) else None)
    return isinstance(summary, dict) and isinstance(expectations, list) and bool(expectations)


def aggregate_if_graded(iteration_dir: Path, skill_name: str) -> None:
    grading_files = list(iteration_dir.glob("eval-*/*/run-*/grading.json"))
    if not grading_files or not all(grading_is_complete(path) for path in grading_files):
        print(f"Workspace scaffolded at {iteration_dir}; skipping aggregation until all grading.json files are complete.")
        return

    evaluation_dir = Path(__file__).resolve().parents[1]
    aggregate_script = evaluation_dir / "scripts" / "value_proof_aggregate.py"
    catalog_path = iteration_dir / "rendered-catalog.json"
    bindings_path = iteration_dir / "bindings.json"
    cmd = [
        sys.executable,
        str(aggregate_script),
        str(iteration_dir),
        "--skill-name",
        skill_name,
    ]
    if catalog_path.exists():
        cmd.extend(["--catalog", str(catalog_path)])
    if bindings_path.exists():
        cmd.extend(["--bindings", str(bindings_path)])
    subprocess.run(cmd, check=True)


def parse_configs(value: str) -> list[str]:
    configs = [item.strip() for item in value.split(",") if item.strip()]
    if not configs:
        raise argparse.ArgumentTypeError("--configs must include at least one configuration.")
    return configs


def main() -> None:
    parser = argparse.ArgumentParser(description="Scaffold an evaluation benchmark workspace.")
    parser.add_argument("--catalog", required=True, type=Path, help="Rendered catalog JSON")
    parser.add_argument("--workspace", required=True, type=Path, help="Workspace root to create")
    parser.add_argument("--runs-per-config", type=int, default=5, help="Runs per eval/configuration. Defaults to 5 so paired Wilcoxon has signal — 3 is insufficient for stddev estimates.")
    parser.add_argument("--configs", type=parse_configs, default=["with_skill", "tool_only", "without_skill"], help="Comma-separated configurations")
    parser.add_argument("--iteration", default="iteration-001", help="Iteration directory name")
    parser.add_argument("--no-aggregate", action="store_true", help="Skip aggregation even when grading files are complete")
    args = parser.parse_args()

    iteration_dir = scaffold_workspace(
        catalog_path=args.catalog,
        workspace_dir=args.workspace,
        runs_per_config=args.runs_per_config,
        configs=args.configs,
        iteration=args.iteration,
        aggregate=not args.no_aggregate,
    )
    print(f"Created evaluation workspace: {iteration_dir}")


if __name__ == "__main__":
    main()
