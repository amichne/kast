from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import workflow_contract as contract


def write(root: Path, workflow: str, tasks: str, includes: str = '["config/ci/tasks/ci.toml"]') -> None:
    workflows = root / ".github/workflows"
    task_directory = root / "config/ci/tasks"
    workflows.mkdir(parents=True)
    task_directory.mkdir(parents=True)
    (workflows / "ci.yml").write_text(workflow)  # ci.yml is a check workflow
    (task_directory / "ci.toml").write_text(tasks)
    mise = root / "mise.toml"
    mise.write_text(f"[task_config]\nincludes = {includes}\n")
    (root / ".pre-commit-config.yaml").write_text("repos: []\n")


def failures(workflow: str, tasks: str, includes: str = '["config/ci/tasks/ci.toml"]') -> list[contract.Failure]:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        write(root, workflow, tasks, includes)
        return [violation.failure for violation in contract.violations(root)]


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

    def test_a_second_shell_line_is_rejected(self) -> None:
        for command in ("mise run ci:check\npython3 other.py", "mise run ci:check ::: other"):
            with self.subTest(command=command):
                self.assertIsNone(contract.step_task(contract.normalize(command)))

    def test_unknown_task_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.UNKNOWN_TASK, contract.Failure.UNUSED_TASK],
            failures(job("mise run ci:missing"), TASK),
        )

    def test_task_used_only_by_another_task_is_admitted(self) -> None:
        tasks = TASK + '["ci:outer"]\nrun = [{ task = "ci:check" }]\n'
        self.assertEqual([], failures(job("mise run ci:outer"), tasks))

    def test_unused_task_is_rejected(self) -> None:
        tasks = TASK + '["ci:orphan"]\nrun = "python3 orphan.py"\n'
        self.assertEqual([contract.Failure.UNUSED_TASK], failures(job("mise run ci:check"), tasks))

    def test_task_file_missing_from_mise_includes_is_rejected(self) -> None:
        self.assertEqual(
            [contract.Failure.TASK_FILE_NOT_INCLUDED], failures(job("mise run ci:check"), TASK, includes="[]")
        )

    def test_root_task_and_included_task_share_the_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write(root, job("mise run ci:outer"), TASK)
            with (root / "mise.toml").open("a") as config:
                config.write('[tasks."ci:outer"]\nrun = [{ task = "ci:check" }]\n')
            self.assertEqual([], contract.violations(root))

    def test_each_workflow_including_publication_uses_tasks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write(root, job("mise run ci:check"), TASK)
            (root / ".github/workflows/new.yaml").write_text(job("python3 other.py"))
            self.assertEqual([contract.Failure.UNADMITTED_STEP], [v.failure for v in contract.violations(root)])

    def test_duplicates_hooks_and_guide_references_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write(root, job("mise run ci:check"), TASK, '["config/ci/tasks/ci.toml", "config/ci/tasks/other.toml"]')
            (root / "config/ci/tasks/other.toml").write_text(TASK)
            (root / ".pre-commit-config.yaml").write_text(
                "repos: [{repo: local, hooks: [{id: bad, entry: 'python3 other.py'}]}]\n"
            )
            (root / "AGENTS.md").write_text("Run `mise run missing` or `./check old`.")
            self.assertCountEqual(
                [contract.Failure.DUPLICATE_TASK, contract.Failure.UNADMITTED_HOOK,
                 contract.Failure.UNKNOWN_TASK, contract.Failure.SECOND_TASK_LIST],
                [v.failure for v in contract.violations(root)],
            )

    def test_continuation_lines_compare_as_one_command(self) -> None:
        self.assertEqual("mise run ci:check --base x", contract.normalize("mise run ci:check \\\n  --base x\n"))


if __name__ == "__main__":
    unittest.main()
