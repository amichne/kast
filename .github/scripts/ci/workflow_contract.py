"""Admit workflow steps only when they run a named mise task that a developer can run locally.

A workflow step runs `mise run <task> [arguments]`, or it is one of the reviewed CI-only
bootstrap or reporting steps below. Tasks live in `config/ci/tasks/*.toml`, which `mise.toml`
includes. Every task is used by a workflow step, by another task, or is a declared local aggregate.
"""

from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"
TASKS = ROOT / "config/ci/tasks"
MISE = ROOT / "mise.toml"


class Failure(StrEnum):
    UNADMITTED_STEP = "UNADMITTED_STEP"
    UNKNOWN_TASK = "UNKNOWN_TASK"
    UNUSED_TASK = "UNUSED_TASK"
    TASK_FILE_NOT_INCLUDED = "TASK_FILE_NOT_INCLUDED"
    STALE_BOOTSTRAP = "STALE_BOOTSTRAP"
    UNCLASSIFIED_WORKFLOW = "UNCLASSIFIED_WORKFLOW"


@dataclass(frozen=True)
class Violation:
    failure: Failure
    location: str
    detail: str


# Check workflows produce verdicts on a change, so every step must be locally reproducible.
# Publication workflows release or maintain artifacts; they are outside this contract until they get
# their own. Every workflow file must be in exactly one set.
CHECK_WORKFLOWS = frozenset({"ci.yml", "docs.yml", "composed-linux.yml", "gradle-cache.yml"})
PUBLICATION_WORKFLOWS = frozenset(
    {"release.yml", "release-component.yml", "developer-release.yml", "openwiki-update.yml"}
)

# Steps that cannot be tasks: they install the task runner's own prerequisites, report to the
# GitHub step summary, or gate on another job's result. Each entry is an exact normalized command.
BOOTSTRAP: dict[str, str] = {
    "python3 -m pip install --disable-pip-version-check uv==0.12.9": "installs uv before any task",
    "python3 -m pip install uv==0.12.9": "installs uv before any task",
    'echo "Semantic fixture switch: $KAST_SEMANTIC_FIXTURES" >> "$GITHUB_STEP_SUMMARY" '
    'echo "macOS check switch: $KAST_MACOS_CHECKS; effective product assembly: $KAST_NATIVE_COMPILATION" >> "$GITHUB_STEP_SUMMARY"': (
        "reports the CI switches to the step summary"
    ),
    'test "$COMPLETE_PRODUCT_RESULT" = success': "gates on the complete-product job result",
    'test "$ROLLOUT_SUCCESS" = true': "gates on the frozen rollout results",
}

# Local aggregates run several CI jobs on one host. No workflow step runs them.
LOCAL_AGGREGATES = frozenset({"ci:pr", "ci:pr-full"})

_TASK_STEP = re.compile(r"mise run (?P<task>[a-z][a-z0-9-]*:[a-z0-9:-]+)(?P<arguments>(?: [^\n]*)?)")
_SUMMARY_SUFFIX = ' | tee -a "$GITHUB_STEP_SUMMARY"'
_SHELL_CONTROL = re.compile(r";|&&|\|\||\||<<|\$\(|`")
_TASK_REFERENCE = re.compile(r"mise run (?P<task>[a-z][a-z0-9-]*:[a-z0-9:-]+)")


def normalize(command: str) -> str:
    """Join continuation lines and collapse whitespace, so a step compares as one command."""
    return " ".join(command.replace("\\\n", " ").split())


def tasks(directory: Path = TASKS) -> dict[str, dict]:
    declared: dict[str, dict] = {}
    for path in sorted(directory.glob("*.toml")):
        declared.update(tomllib.loads(path.read_text()))
    return declared


def workflow_steps(directory: Path = WORKFLOWS) -> list[tuple[str, str]]:
    steps: list[tuple[str, str]] = []
    for path in sorted(directory.glob("*.yml")):
        if path.name not in CHECK_WORKFLOWS:
            continue
        document = yaml.safe_load(path.read_text())
        for job_id, job in (document.get("jobs") or {}).items():
            for index, step in enumerate(job.get("steps") or []):
                if "run" in step:
                    location = f"{path.name}:{job_id}:{step.get('name') or index}"
                    steps.append((location, normalize(step["run"])))
    return steps


def step_task(command: str) -> str | None:
    """The task a step runs, or None when the step is not an admitted task invocation."""
    body = command.removesuffix(_SUMMARY_SUFFIX)
    match = _TASK_STEP.fullmatch(body)
    if match is None or _SHELL_CONTROL.search(match.group("arguments")):
        return None
    return match.group("task")


def violations(
    workflows: Path = WORKFLOWS, task_directory: Path = TASKS, mise: Path = MISE
) -> list[Violation]:
    found: list[Violation] = []
    for path in sorted(workflows.glob("*.yml")):
        if (path.name in CHECK_WORKFLOWS) == (path.name in PUBLICATION_WORKFLOWS):
            found.append(Violation(Failure.UNCLASSIFIED_WORKFLOW, path.name, "classify it as a check or publication workflow"))
    declared = tasks(task_directory)
    used: set[str] = set()
    bootstrap_used: set[str] = set()
    for location, command in workflow_steps(workflows):
        if command in BOOTSTRAP:
            bootstrap_used.add(command)
            continue
        task = step_task(command)
        if task is None:
            found.append(Violation(Failure.UNADMITTED_STEP, location, command[:200]))
        elif task not in declared:
            found.append(Violation(Failure.UNKNOWN_TASK, location, task))
        else:
            used.add(task)
    for name, definition in declared.items():
        runs = definition.get("run", [])
        for command in [runs] if isinstance(runs, str) else runs:
            for reference in _TASK_REFERENCE.finditer(command):
                used.add(reference.group("task"))
                if reference.group("task") not in declared:
                    found.append(Violation(Failure.UNKNOWN_TASK, name, reference.group("task")))
        for dependency in definition.get("depends", []):
            used.add(dependency)
            if dependency not in declared:
                found.append(Violation(Failure.UNKNOWN_TASK, name, dependency))
    for name in sorted(set(declared) - used - LOCAL_AGGREGATES):
        found.append(Violation(Failure.UNUSED_TASK, name, "no workflow step, task, or local aggregate uses it"))
    for command in sorted(set(BOOTSTRAP) - bootstrap_used):
        found.append(Violation(Failure.STALE_BOOTSTRAP, "BOOTSTRAP", command[:200]))
    includes = set(tomllib.loads(mise.read_text()).get("task_config", {}).get("includes", []))
    for path in sorted(task_directory.glob("*.toml")):
        relative = path.relative_to(mise.parent).as_posix()
        if relative not in includes:
            found.append(Violation(Failure.TASK_FILE_NOT_INCLUDED, relative, "mise.toml task_config.includes"))
    return found


def main() -> int:
    found = violations()
    for violation in found:
        print(f"{violation.failure}: {violation.location}: {violation.detail}")
    return 1 if found else 0


if __name__ == "__main__":
    raise SystemExit(main())
