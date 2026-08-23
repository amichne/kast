from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path, PurePosixPath


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
HOOK = REPOSITORY_ROOT / ".github" / "scripts" / "agents_md_turn_refresh.py"
SCAFFOLD = REPOSITORY_ROOT / ".github" / "scripts" / "scaffold_agents_md_turn_guides.py"
SCAFFOLD_SPEC = importlib.util.spec_from_file_location("scaffold_agents_md_turn_guides", SCAFFOLD)
assert SCAFFOLD_SPEC is not None and SCAFFOLD_SPEC.loader is not None
SCAFFOLD_MODULE = importlib.util.module_from_spec(SCAFFOLD_SPEC)
SCAFFOLD_SPEC.loader.exec_module(SCAFFOLD_MODULE)


class AgentsMdTurnRefreshTest(unittest.TestCase):
    def test_skips_wrapper_directory_without_direct_files(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(repository / "service" / "api" / "Api.kt", "old\n")
            commit_all(repository)

            run_hook(repository, "start")
            write(repository / "service" / "api" / "Api.kt", "new\n")

            result = run_hook(repository, "stop", expected_status=1)
            guides = [item["guide"] for item in json.loads(result.stdout)["operations"]]
            self.assertEqual(guides, ["service/api/AGENTS.md", "AGENTS.md"])

    def test_requires_removing_generated_wrapper_after_last_direct_file_is_deleted(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(
                repository / "wrapper" / "AGENTS.md",
                SCAFFOLD_MODULE.render(
                    PurePosixPath("wrapper/AGENTS.md"),
                    PurePosixPath("AGENTS.md"),
                ),
            )
            write(repository / "wrapper" / "Owned.kt", "old\n")
            write(repository / "wrapper" / "child" / "Child.kt", "content\n")
            commit_all(repository)

            run_hook(repository, "start")
            (repository / "wrapper" / "Owned.kt").unlink()

            result = run_hook(repository, "stop", expected_status=1)
            observed = [
                (item["guide"], item["requiredOutcome"])
                for item in json.loads(result.stdout)["operations"]
            ]
            self.assertEqual(
                observed,
                [
                    ("wrapper/AGENTS.md", "remove"),
                    ("AGENTS.md", "update-or-unchanged"),
                ],
            )

    def test_reverse_breadth_first_agents_work_queue(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(repository / "service" / "AGENTS.md", "# Services\n")
            write(repository / "service" / "api" / "AGENTS.md", "# API\n")
            write(repository / "service" / "api" / "src" / "Api.kt", "old\n")
            write(repository / "service" / "web" / "index.ts", "old\n")
            commit_all(repository)

            run_hook(repository, "start")
            write(repository / "service" / "api" / "src" / "Api.kt", "new\n")
            write(repository / "service" / "web" / "index.ts", "new\n")

            result = run_hook(repository, "stop", expected_status=1)
            plan = json.loads(result.stdout)
            observed = [
                (operation["guide"], operation["requiredOutcome"])
                for operation in plan["operations"]
            ]
            self.assertEqual(
                observed,
                [
                    ("service/api/src/AGENTS.md", "create"),
                    ("service/web/AGENTS.md", "create"),
                    ("service/api/AGENTS.md", "update-or-unchanged"),
                    ("service/AGENTS.md", "update-or-unchanged"),
                    ("AGENTS.md", "update-or-unchanged"),
                ],
                "reverse breadth-first AGENTS work queue",
            )

    def test_ignores_unchanged_edits_that_predate_the_turn(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(repository / "old" / "AGENTS.md", "# Old\n")
            write(repository / "old" / "File.kt", "committed\n")
            write(repository / "new" / "File.kt", "committed\n")
            commit_all(repository)
            write(repository / "old" / "File.kt", "dirty before turn\n")

            run_hook(repository, "start")
            write(repository / "new" / "File.kt", "changed this turn\n")

            result = run_hook(repository, "stop", expected_status=1)
            guides = [item["guide"] for item in json.loads(result.stdout)["operations"]]
            self.assertEqual(guides, ["new/AGENTS.md", "AGENTS.md"])

    def test_converges_after_guide_edits_and_unchanged_resolutions(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(repository / "service" / "AGENTS.md", "# Services\n")
            write(repository / "service" / "api" / "AGENTS.md", "# API\n")
            write(repository / "service" / "api" / "src" / "Api.kt", "old\n")
            write(repository / "service" / "web" / "index.ts", "old\n")
            commit_all(repository)

            run_hook(repository, "start")
            write(repository / "service" / "api" / "src" / "Api.kt", "new\n")
            write(repository / "service" / "web" / "index.ts", "new\n")
            run_hook(repository, "stop", expected_status=1)

            write(repository / "service" / "api" / "src" / "AGENTS.md", "# API sources\n")
            write(repository / "service" / "web" / "AGENTS.md", "# Web\n")
            write(repository / "service" / "api" / "AGENTS.md", "# API updated\n")
            run_hook(repository, "resolve", "--guide", "service/AGENTS.md", "--outcome", "unchanged")
            run_hook(repository, "resolve", "--guide", "AGENTS.md", "--outcome", "unchanged")

            result = run_hook(repository, "stop")
            self.assertEqual(json.loads(result.stdout)["operations"], [])

    def test_requires_removing_an_orphaned_guide(self) -> None:
        with repository_fixture() as repository:
            write(repository / "AGENTS.md", "# Repository\n")
            write(repository / "obsolete" / "AGENTS.md", "# Obsolete\n")
            write(repository / "obsolete" / "File.kt", "old\n")
            commit_all(repository)

            run_hook(repository, "start")
            (repository / "obsolete" / "File.kt").unlink()

            result = run_hook(repository, "stop", expected_status=1)
            operations = json.loads(result.stdout)["operations"]
            self.assertEqual(
                [(item["guide"], item["requiredOutcome"]) for item in operations],
                [
                    ("obsolete/AGENTS.md", "remove"),
                    ("AGENTS.md", "update-or-unchanged"),
                ],
            )

            (repository / "obsolete" / "AGENTS.md").unlink()
            run_hook(repository, "resolve", "--guide", "AGENTS.md", "--outcome", "unchanged")
            result = run_hook(repository, "stop")
            self.assertEqual(json.loads(result.stdout)["operations"], [])

    def test_codex_adapter_tracks_prompt_start_and_checks_stop(self) -> None:
        adapter = json.loads((REPOSITORY_ROOT / ".codex" / "hooks.json").read_text(encoding="utf-8"))
        start_commands = commands_for(adapter, "UserPromptSubmit")
        stop_commands = commands_for(adapter, "Stop")
        self.assertIn(
            "python3 .github/scripts/agents_md_turn_refresh.py start --repo .",
            start_commands,
        )
        self.assertEqual(
            stop_commands[0],
            "python3 .github/scripts/agents_md_turn_refresh.py stop --repo .",
        )
        self.assertIn(
            "python3 .github/scripts/check-repository-shape.py --root .",
            stop_commands,
        )


class ScaffoldAgentsMdTurnGuidesTest(unittest.TestCase):
    def test_prune_targets_only_generated_guides_without_direct_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            write(repository / "AGENTS.md", "# Repository\n")
            write(
                repository / "wrapper" / "AGENTS.md",
                SCAFFOLD_MODULE.render(
                    PurePosixPath("wrapper/AGENTS.md"),
                    PurePosixPath("AGENTS.md"),
                ),
            )
            write(repository / "wrapper" / "child" / "File.kt", "content\n")
            write(repository / "substantive" / "AGENTS.md", "# Substantive\n")
            write(repository / "substantive" / "child" / "File.kt", "content\n")
            extended = SCAFFOLD_MODULE.render(
                PurePosixPath("extended/AGENTS.md"),
                PurePosixPath("AGENTS.md"),
            )
            write(
                repository / "extended" / "AGENTS.md",
                extended + "\n## Durable child boundary\n\n- Preserve this rule.\n",
            )
            write(repository / "extended" / "child" / "File.kt", "content\n")
            write(
                repository / "orphan" / "child" / "AGENTS.md",
                SCAFFOLD_MODULE.render(
                    PurePosixPath("orphan/child/AGENTS.md"),
                    PurePosixPath("orphan/AGENTS.md"),
                ),
            )
            write(repository / "orphan" / "child" / "nested" / "File.kt", "content\n")
            write(
                repository / "leaf" / "AGENTS.md",
                SCAFFOLD_MODULE.render(
                    PurePosixPath("leaf/AGENTS.md"),
                    PurePosixPath("AGENTS.md"),
                ),
            )
            write(repository / "leaf" / "File.kt", "content\n")

            targets = SCAFFOLD_MODULE.empty_owner_guides(repository)

            self.assertEqual(
                targets,
                [
                    PurePosixPath("orphan/child/AGENTS.md"),
                    PurePosixPath("wrapper/AGENTS.md"),
                ],
            )

    def test_nearest_available_parent_is_selected(self) -> None:
        available = {
            PurePosixPath("AGENTS.md"),
            PurePosixPath("module/AGENTS.md"),
            PurePosixPath("module/src/AGENTS.md"),
        }

        owner = SCAFFOLD_MODULE.nearest_owner_guide(
            PurePosixPath("module/src/main/AGENTS.md"),
            available,
        )

        self.assertEqual(owner, PurePosixPath("module/src/AGENTS.md"))

    def test_render_uses_relative_owner_link_and_production_scope(self) -> None:
        text = SCAFFOLD_MODULE.render(
            PurePosixPath("module/src/main/AGENTS.md"),
            PurePosixPath("module/AGENTS.md"),
        )

        self.assertIn("production sources", text)
        self.assertIn("](../../AGENTS.md)", text)

    def test_render_is_stable(self) -> None:
        guide = PurePosixPath("module/src/test/AGENTS.md")
        owner = PurePosixPath("module/AGENTS.md")

        self.assertEqual(
            SCAFFOLD_MODULE.render(guide, owner),
            SCAFFOLD_MODULE.render(guide, owner),
        )


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def commit_all(repository: Path) -> None:
    subprocess.run(["git", "add", "."], cwd=repository, check=True)
    subprocess.run(
        ["git", "-c", "user.name=Codex Test", "-c", "user.email=codex@example.invalid", "commit", "-m", "fixture"],
        cwd=repository,
        check=True,
        stdout=subprocess.DEVNULL,
    )


class repository_fixture:
    def __enter__(self) -> Path:
        self.temporary_directory = tempfile.TemporaryDirectory()
        repository = Path(self.temporary_directory.name)
        subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
        self.repository = repository
        return repository

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.temporary_directory.cleanup()


def commands_for(adapter: dict, event: str) -> list[str]:
    return [
        hook["command"]
        for group in adapter["hooks"].get(event, [])
        for hook in group["hooks"]
    ]


def run_hook(
    repository: Path,
    command: str,
    *arguments: str,
    expected_status: int = 0,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["python3", str(HOOK), command, "--repo", str(repository), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != expected_status:
        raise AssertionError(
            f"hook {command} returned {result.returncode}, expected {expected_status}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result


if __name__ == "__main__":
    unittest.main()
