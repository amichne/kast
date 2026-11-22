from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import workflow_contract as contract


def write(root: Path, workflow: str, tasks: str, includes: str = '["config/ci/tasks/ci.toml"]') -> tuple[Path, Path, Path]:
    workflows = root / ".github/workflows"
    task_directory = root / "config/ci/tasks"
    workflows.mkdir(parents=True)
    task_directory.mkdir(parents=True)
    (workflows / "ci.yml").write_text(workflow)  # ci.yml is a check workflow
    (task_directory / "ci.toml").write_text(tasks)
    mise = root / "mise.toml"
    mise.write_text(f"[task_config]\nincludes = {includes}\n")
    return workflows, task_directory, mise


def failures(workflow: str, tasks: str, includes: str = '["config/ci/tasks/ci.toml"]') -> list[contract.Failure]:
    with tempfile.TemporaryDirectory() as temporary:
        workflows, task_directory, mise = write(Path(temporary), workflow, tasks, includes)
        found = contract.violations(workflows, task_directory, mise)
    # The real bootstrap list is not used by these fixtures.
    return [violation.failure for violation in found if violation.failure is not contract.Failure.STALE_BOOTSTRAP]


TASK = '["ci:check"]\nrun = "python3 check.py"\n'


def job(*runs: str) -> str:
    steps = "".join(f"      - run: {run}\n" for run in runs)
    return f"jobs:\n  check:\n    runs-on: ubuntu-latest\n    steps:\n{steps}"


class WorkflowContractTest(unittest.TestCase):
    def test_repository_workflows_satisfy_the_contract(self) -> None:
        found = contract.violations()
        self.assertEqual([], [f"{v.failure}: {v.location}: {v.detail}" for v in found])

    def test_task_step_with_arguments_is_admitted(self) -> None:
        self.assertEqual([], failures(job('mise run ci:check --base "$BASE_SHA"'), TASK))

    def test_summary_suffix_is_admitted(self) -> None:
        self.assertEqual([], failures(job("'mise run ci:check | tee -a \"$GITHUB_STEP_SUMMARY\"'"), TASK))

    def test_inline_command_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.UNADMITTED_STEP, contract.Failure.UNUSED_TASK],
            failures(job("python3 check.py"), TASK),
        )

    def test_shell_control_in_task_arguments_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.UNADMITTED_STEP, contract.Failure.UNUSED_TASK],
            failures(job("'mise run ci:check && python3 other.py'"), TASK),
        )

    def test_unknown_task_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.UNKNOWN_TASK, contract.Failure.UNUSED_TASK],
            failures(job("mise run ci:missing"), TASK),
        )

    def test_task_used_only_by_another_task_is_admitted(self) -> None:
        tasks = TASK + '["ci:outer"]\nrun = ["mise run ci:check"]\n'
        self.assertEqual([], failures(job("mise run ci:outer"), tasks))

    def test_unused_task_is_rejected(self) -> None:
        tasks = TASK + '["ci:orphan"]\nrun = "python3 orphan.py"\n'
        self.assertEqual([contract.Failure.UNUSED_TASK], failures(job("mise run ci:check"), tasks))

    def test_task_file_missing_from_mise_includes_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.TASK_FILE_NOT_INCLUDED], failures(job("mise run ci:check"), TASK, includes="[]")
        )

    def test_unclassified_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            workflows, task_directory, mise = write(Path(temporary), job("mise run ci:check"), TASK)
            (workflows / "new.yml").write_text(job("python3 anything.py"))
            found = [v.failure for v in contract.violations(workflows, task_directory, mise)]
        self.assertIn(contract.Failure.UNCLASSIFIED_WORKFLOW, found)
        self.assertNotIn(contract.Failure.UNADMITTED_STEP, found)

    def test_continuation_lines_compare_as_one_command(self) -> None:
        self.assertEqual("mise run ci:check --base x", contract.normalize("mise run ci:check \\\n  --base x\n"))


if __name__ == "__main__":
    unittest.main()
