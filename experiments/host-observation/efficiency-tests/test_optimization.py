"""Candidate admission keeps the paired experiment constraints."""

import json
from pathlib import Path

import pytest

from agent_efficiency import Configuration, Environment
from agent_efficiency_optimize import Candidate, comparable


def candidate(tmp_path: Path, name: str, onboarding: str) -> Candidate:
    folder = tmp_path / name
    folder.mkdir()
    env = Environment(
        workspace=str(folder / "workspace"),
        pi_package="/pi",
        pi_version="1.3.0",
        auth="/auth.json",
        mcp_command="/kast-mcp",
    )
    _ = (folder / "environment.json").write_text(env.model_dump_json())
    _ = (folder / "manifest.json").write_text(
        json.dumps({"corpusSha256": "a" * 64, "revision": "b" * 40})
    )
    config = Configuration.model_validate(
        {
            "name": name,
            "exposure": "codemode",
            "onboarding": onboarding,
            "hypothesis": "One change.",
        }
    )
    config_file = folder / "config.json"
    _ = config_file.write_text(config.model_dump_json())
    return Candidate(benchmark=str(folder), configuration=str(config_file))


def test_one_change_in_separate_worktrees_is_admitted(tmp_path: Path) -> None:
    champion = candidate(tmp_path, "champion", "canonical")
    challenger = candidate(tmp_path, "challenger", "none")
    comparable(champion, challenger)


def test_the_same_worktree_is_rejected(tmp_path: Path) -> None:
    champion = candidate(tmp_path, "champion", "canonical")
    with pytest.raises(ValueError, match="WORKTREE_NEEDED"):
        comparable(champion, champion)


def test_changed_resources_are_rejected(tmp_path: Path) -> None:
    champion = candidate(tmp_path, "champion", "canonical")
    challenger = candidate(tmp_path, "challenger", "none")
    file = Path(challenger.configuration)
    config = Configuration.model_validate_json(file.read_bytes())
    _ = file.write_text(config.model_copy(update={"maximum_requests": 3}).model_dump_json())
    with pytest.raises(ValueError, match="RESOURCE_LIMIT_MISMATCH"):
        comparable(champion, challenger)


def test_two_changes_are_rejected(tmp_path: Path) -> None:
    champion = candidate(tmp_path, "champion", "canonical")
    challenger = candidate(tmp_path, "challenger", "none")
    file = Path(challenger.configuration)
    config = Configuration.model_validate_json(file.read_bytes())
    _ = file.write_text(config.model_copy(update={"exposure": "direct"}).model_dump_json())
    with pytest.raises(ValueError, match="ONE_ATTRIBUTABLE"):
        comparable(champion, challenger)


def test_changed_corpus_is_rejected(tmp_path: Path) -> None:
    champion = candidate(tmp_path, "champion", "canonical")
    challenger = candidate(tmp_path, "challenger", "none")
    file = Path(challenger.benchmark) / "manifest.json"
    _ = file.write_text(json.dumps({"corpusSha256": "c" * 64, "revision": "b" * 40}))
    with pytest.raises(ValueError, match="CORPUS_OR_REVISION"):
        comparable(champion, challenger)
