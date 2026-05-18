#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any

from dispatch_runs import DispatchOptions, dispatch_iteration
from finalize_grading import finalize
from render_prompts import load_json as load_json_object
from render_prompts import render_catalog
from run_value_proof import grading_is_complete, scaffold_workspace
from value_proof_aggregate import aggregate, write_outputs

SCRIPT_GRADER = Path(__file__).resolve().parent / "script_grader.py"


def shell_value(value: str | int | None) -> str:
    return shlex.quote("" if value is None else str(value))


def load_bindings(path: Path) -> dict[str, Any]:
    payload = load_json_object(path)
    workspace_root = payload.get("workspace_root")
    if not isinstance(workspace_root, str) or not workspace_root:
        raise ValueError(f"{path} must declare workspace_root.")
    return payload


def record_git_sha(bindings: dict[str, Any], bindings_path: Path) -> dict[str, Any]:
    workspace_root = Path(bindings["workspace_root"])
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=False,
    )
    updated = dict(bindings)
    if result.returncode == 0:
        updated["git_sha"] = result.stdout.strip()
        bindings_path.write_text(json.dumps(updated, indent=2) + "\n")
    return updated


def select_cases(catalog: dict[str, Any], case_ids: set[str]) -> dict[str, Any]:
    if not case_ids:
        return catalog
    filtered = [case for case in catalog.get("cases", []) if case.get("id") in case_ids]
    missing = sorted(case_ids - {case.get("id") for case in filtered})
    if missing:
        raise ValueError(f"Unknown evaluation case(s): {', '.join(missing)}")
    return {**catalog, "cases": filtered}


def render_command(template: str, run_dir: Path) -> str:
    eval_dir = run_dir.parent.parent.resolve()
    iteration_dir = eval_dir.parent.resolve()
    config_dir = run_dir.parent.resolve()
    run_dir = run_dir.resolve()
    transcript_path = (run_dir / "outputs" / "transcript.md").resolve()
    mechanical_path = (run_dir / "mechanical.json").resolve()
    llm_grade_input_path = (run_dir / "llm-grade-input.json").resolve()
    llm_grade_path = (run_dir / "llm-grade.json").resolve()
    grading_path = (run_dir / "grading.json").resolve()
    run_number = run_dir.name.removeprefix("run-")
    placeholders = {
        "iteration_dir": shell_value(iteration_dir),
        "eval_dir": shell_value(eval_dir),
        "eval_id": shell_value(eval_dir.name.removeprefix("eval-")),
        "run_dir": shell_value(run_dir),
        "transcript": shell_value(transcript_path),
        "configuration": shell_value(config_dir.name),
        "run_number": shell_value(run_number),
        "mechanical": shell_value(mechanical_path),
        "llm_grade_input": shell_value(llm_grade_input_path),
        "llm_grade": shell_value(llm_grade_path),
        "grading": shell_value(grading_path),
    }
    rendered = template
    for key, value in placeholders.items():
        rendered = rendered.replace(f"{{{key}}}", value)
    return rendered


def run_grade_step(
    *,
    iteration_dir: Path,
    bindings_path: Path,
    mechanical_grade_command_template: str | None,
    llm_grade_command_template: str | None,
    workspace_root: Path,
) -> None:
    for run_dir in sorted(iteration_dir.glob("eval-*/*/run-*")):
        if mechanical_grade_command_template:
            command = render_command(mechanical_grade_command_template, run_dir)
            completed = subprocess.run(
                command,
                cwd=run_dir,
                shell=True,
                text=True,
                capture_output=True,
                check=False,
            )
            if completed.returncode != 0:
                raise ValueError(
                    f"Mechanical grading failed for {run_dir}: {completed.stderr.strip() or completed.stdout.strip()}"
                )
        else:
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_GRADER),
                    "--run-dir",
                    str(run_dir),
                    "--bindings",
                    str(bindings_path),
                    "--output",
                    str(run_dir / "mechanical.json"),
                    "--llm-grade-input-output",
                    str(run_dir / "llm-grade-input.json"),
                ],
                cwd=run_dir,
                text=True,
                capture_output=True,
                check=False,
            )
            if completed.returncode != 0:
                raise ValueError(
                    f"Mechanical grading failed for {run_dir}: {completed.stderr.strip() or completed.stdout.strip()}"
                )
        if llm_grade_command_template:
            command = render_command(llm_grade_command_template, run_dir)
            completed = subprocess.run(
                command,
                cwd=run_dir,
                shell=True,
                text=True,
                capture_output=True,
                check=False,
            )
            if completed.returncode != 0:
                raise ValueError(
                    f"LLM grading failed for {run_dir}: {completed.stderr.strip() or completed.stdout.strip()}"
                )
        finalize(run_dir, workspace_root=workspace_root)


def aggregate_if_complete(iteration_dir: Path, *, skill_name: str) -> Path:
    grading_files = list(iteration_dir.glob("eval-*/*/run-*/grading.json"))
    if not grading_files or not all(grading_is_complete(path) for path in grading_files):
        raise ValueError(f"Cannot aggregate {iteration_dir}: grading.json files are incomplete.")
    benchmark = aggregate(
        iteration_dir,
        skill_name=skill_name,
        bindings_path=iteration_dir / "bindings.json",
        catalog_path=iteration_dir / "rendered-catalog.json",
    )
    write_outputs(iteration_dir, benchmark)
    return iteration_dir / "benchmark.json"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the consolidated evaluation workflow.")
    parser.add_argument("--catalog", required=True, type=Path, help="Catalog JSON to render")
    parser.add_argument("--bindings", required=True, type=Path, help="Bindings JSON to render against")
    parser.add_argument("--workspace", required=True, type=Path, help="Benchmark workspace root")
    parser.add_argument("--iteration", default="iteration-001", help="Iteration directory name")
    parser.add_argument("--runs-per-config", type=int, default=5, help="Runs per evaluation/configuration")
    parser.add_argument(
        "--configs",
        default="with_skill,tool_only,without_skill",
        help="Comma-separated configurations to scaffold",
    )
    parser.add_argument(
        "--case",
        action="append",
        dest="cases",
        default=[],
        help="Restrict the run to one or more case ids",
    )
    parser.add_argument(
        "--dispatch-command-template",
        help="Shell command template passed to dispatch_runs.py placeholders",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=4,
        help="Parallel worker count for dispatch; default: 4",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=1,
        help="Retries for failed or empty-transcript runs; default: 1",
    )
    parser.add_argument(
        "--mechanical-grade-command-template",
        help="Shell command template that writes mechanical.json for each run",
    )
    parser.add_argument(
        "--llm-grade-command-template",
        "--grade-command-template",
        dest="llm_grade_command_template",
        help="Shell command template that writes llm-grade.json for each run",
    )
    parser.add_argument("--skip-dispatch", action="store_true", help="Skip the dispatch phase")
    parser.add_argument("--skip-grade", action="store_true", help="Skip grading/finalization")
    parser.add_argument("--skip-aggregate", action="store_true", help="Skip aggregation")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    bindings = record_git_sha(load_bindings(args.bindings), args.bindings)
    rendered_catalog = render_catalog(
        select_cases(load_json_object(args.catalog), set(args.cases)),
        bindings,
    )
    rendered_catalog_path = args.workspace / f"{args.iteration}-rendered-catalog.json"
    rendered_catalog_path.parent.mkdir(parents=True, exist_ok=True)
    rendered_catalog_path.write_text(json.dumps(rendered_catalog, indent=2) + "\n")

    configs = [config.strip() for config in args.configs.split(",") if config.strip()]
    iteration_dir = scaffold_workspace(
        catalog_path=rendered_catalog_path,
        workspace_dir=args.workspace,
        runs_per_config=args.runs_per_config,
        configs=configs,
        iteration=args.iteration,
        aggregate=False,
    )

    if not args.skip_dispatch:
        if not args.dispatch_command_template:
            parser.error("--dispatch-command-template is required unless --skip-dispatch is set.")
        dispatch_iteration(
            iteration_dir,
            DispatchOptions(
                command_template=args.dispatch_command_template,
                concurrency=args.concurrency,
                max_retries=args.max_retries,
            ),
        )

    if not args.skip_grade:
        run_grade_step(
            iteration_dir=iteration_dir,
            bindings_path=args.bindings,
            mechanical_grade_command_template=args.mechanical_grade_command_template,
            llm_grade_command_template=args.llm_grade_command_template,
            workspace_root=Path(bindings["workspace_root"]),
        )

    if not args.skip_aggregate:
        benchmark_path = aggregate_if_complete(
            iteration_dir,
            skill_name=rendered_catalog.get("skill_name", "kast-value-proof"),
        )
        print(f"Generated: {benchmark_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
