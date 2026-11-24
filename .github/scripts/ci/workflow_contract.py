"""Every workflow and commit hook invokes a declared mise task."""

from __future__ import annotations

import re
import tomllib
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"


class Failure(StrEnum):
    UNADMITTED_STEP = "UNADMITTED_STEP"
    UNKNOWN_TASK = "UNKNOWN_TASK"
    UNUSED_TASK = "UNUSED_TASK"
    TASK_FILE_NOT_INCLUDED = "TASK_FILE_NOT_INCLUDED"
    DUPLICATE_TASK = "DUPLICATE_TASK"
    UNADMITTED_HOOK = "UNADMITTED_HOOK"
    SECOND_TASK_LIST = "SECOND_TASK_LIST"


@dataclass(frozen=True)
class Violation:
    failure: Failure
    location: str
    detail: str


# Current prose and scripts that tell a reader which task to run. Reports keep their recorded commands.
GUIDES = (
    "AGENTS.md",
    "*/AGENTS.md",
    "*/*/AGENTS.md",
    "docs/development.md",
    "docs/internal/documentation-structure.md",
    "docs/internal/tooling/rendered/README.md",
    "docs/internal/tooling/rendered/reader-journey.sh",
    ".github/scripts/ci/README.md",
    ".github/tools/mintlify/mint.sh",
)

_TASK = r"(?P<task>[a-z][a-z0-9-]*(?::[a-z0-9-]+)*)"
_TASK_STEP = re.compile(rf"mise run {_TASK}(?P<arguments>(?: [^\n]*)?)")
_HOOK = re.compile(rf"mise run {_TASK}")
_REFERENCE = re.compile(rf"(?<![\w/.])mise run {_TASK}")
_SUMMARY_SUFFIX = ' | tee -a "$GITHUB_STEP_SUMMARY"'
_SHELL_CONTROL = re.compile(r"[;&|<>`]|\$\(|(?:^|\s):::(?:\s|$)")


def normalize(command: str) -> str:
    """Keep shell command boundaries while joining explicit continuations."""
    return "\n".join(" ".join(line.split()) for line in command.replace("\\\n", " ").strip().splitlines())


def task_sources(root: Path) -> Iterator[tuple[str, dict]]:
    config = tomllib.loads((root / "mise.toml").read_text())
    yield "mise.toml", config.get("tasks", {})
    for path in sorted((root / "config/ci/tasks").glob("*.toml")):
        yield path.relative_to(root).as_posix(), tomllib.loads(path.read_text())


def tasks(root: Path = ROOT) -> dict[str, dict]:
    return {name: task for _, definitions in task_sources(root) for name, task in definitions.items()}


def references(definition: dict) -> Iterator[str]:
    steps = definition.get("run", [])
    for step in [steps] if isinstance(steps, str) else steps:
        if isinstance(step, str):
            yield from (match.group("task") for match in _REFERENCE.finditer(step))
        elif "task" in step:
            yield step["task"]
        else:
            yield from step["tasks"]
    yield from definition.get("depends", [])


def workflow_steps(directory: Path = WORKFLOWS) -> list[tuple[str, str]]:
    steps: list[tuple[str, str]] = []
    for path in sorted((*directory.glob("*.yml"), *directory.glob("*.yaml"))):
        document = yaml.safe_load(path.read_text())
        for job_id, job in (document.get("jobs") or {}).items():
            for index, step in enumerate(job.get("steps") or []):
                if "run" in step:
                    location = f"{path.name}:{job_id}:{step.get('name') or index}"
                    steps.append((location, normalize(step["run"])))
    return steps


def step_task(command: str) -> str | None:
    """The task a step runs, or None when the step is not an admitted task invocation."""
    body = command.removesuffix(_SUMMARY_SUFFIX).removesuffix(' > "$RUNNER_TEMP/knowledge-refresh.json"')
    match = _TASK_STEP.fullmatch(body)
    if match is None or _SHELL_CONTROL.search(match.group("arguments")):
        return None
    return match.group("task")


def hook_violations(path: Path, tasks: Mapping[str, object]) -> Iterator[Violation]:
    document = yaml.safe_load(path.read_text())
    for repository in document["repos"]:
        for hook in repository["hooks"]:
            entry = hook.get("entry", "")
            match = _HOOK.fullmatch(entry)
            if match is None:
                yield Violation(Failure.UNADMITTED_HOOK, f"{path.name}:{hook['id']}", entry[:200])
            elif match.group("task") not in tasks:
                yield Violation(Failure.UNKNOWN_TASK, f"{path.name}:{hook['id']}", match.group("task"))


def guide_violations(root: Path, tasks: Mapping[str, object]) -> Iterator[Violation]:
    for pattern in GUIDES:
        for path in sorted(root.glob(pattern)):
            location = path.relative_to(root).as_posix()
            text = path.read_text()
            if re.search(r"(?<![\w/.])\./check(?:\s|`)", text):
                yield Violation(Failure.SECOND_TASK_LIST, location, "use the declared mise task")
            for reference in _REFERENCE.finditer(text):
                if reference.group("task") not in tasks:
                    yield Violation(Failure.UNKNOWN_TASK, location, reference.group("task"))


def violations(root: Path = ROOT) -> list[Violation]:
    found: list[Violation] = []
    workflows = root / ".github/workflows"
    mise = root / "mise.toml"
    config = tomllib.loads(mise.read_text())
    includes = set(config.get("task_config", {}).get("includes", []))
    files: set[str] = set()
    declared: dict[str, dict] = {}
    for relative, definitions in task_sources(root):
        files.add(relative)
        for duplicate in sorted(declared.keys() & definitions.keys()):
            found.append(Violation(Failure.DUPLICATE_TASK, relative, duplicate))
        declared.update({name: value if isinstance(value, dict) else {"run": value}
                         for name, value in definitions.items()})
        if relative != "mise.toml" and relative not in includes:
            found.append(Violation(Failure.TASK_FILE_NOT_INCLUDED, relative, "mise.toml task_config.includes"))
    for missing in sorted(includes - files):
        found.append(Violation(Failure.TASK_FILE_NOT_INCLUDED, missing, "missing task file"))
    if (root / "check").exists():
        found.append(Violation(Failure.SECOND_TASK_LIST, "check", "declare workflows in mise"))
    used: set[str] = set()
    for location, command in workflow_steps(workflows):
        task = step_task(command)
        if task is None:
            found.append(Violation(Failure.UNADMITTED_STEP, location, command[:200]))
        elif task not in declared:
            found.append(Violation(Failure.UNKNOWN_TASK, location, task))
        else:
            used.add(task)
    for name, definition in declared.items():
        for dependency in references(definition):
            used.add(dependency)
            if dependency not in declared:
                found.append(Violation(Failure.UNKNOWN_TASK, name, dependency))
    # Namespaced tasks are CI steps or local CI aggregates. Unqualified tasks are developer entrypoints.
    for name in sorted(name for name in set(declared) - used if ":" in name and "local" not in declared[name].get("tags", [])):
        found.append(Violation(Failure.UNUSED_TASK, name, "no workflow step, task, or local aggregate uses it"))
    found.extend(hook_violations(root / ".pre-commit-config.yaml", declared))
    found.extend(guide_violations(root, declared))
    return found


def main() -> int:
    found = violations()
    for violation in found:
        print(f"{violation.failure}: {violation.location}: {violation.detail}")
    return 1 if found else 0


if __name__ == "__main__":
    raise SystemExit(main())
