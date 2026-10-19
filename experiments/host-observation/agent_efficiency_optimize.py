"""Compare attributable candidates with a champion through the existing runner."""

from __future__ import annotations

import argparse
import json
from dataclasses import replace
from pathlib import Path
from typing import Literal

from pydantic import Field, JsonValue, TypeAdapter

from agent_efficiency import Configuration, Environment, Record, execute, save
from agent_efficiency_metrics import InvalidInput, Trial, compare, parse_trial


class Candidate(Record):
    benchmark: str
    configuration: str


class OptimizationPlan(Record):
    champion: Candidate
    candidates: tuple[Candidate, ...] = Field(min_length=1)
    development_task_ids: tuple[str, ...] = Field(min_length=2)
    repeats: int = Field(default=2, ge=2)
    output: str
    cost_basis: Literal["CATALOG_ESTIMATE_NOT_BILLED"] = "CATALOG_ESTIMATE_NOT_BILLED"


def read_trials(directory: Path, arm: str) -> tuple[Trial, ...]:
    trials: list[Trial] = []
    for file in sorted(directory.glob(f"*-{arm}/catalog-trial.json")):
        value: object = json.loads(file.read_bytes())
        admitted = parse_trial(value)
        if isinstance(admitted, InvalidInput):
            raise ValueError(admitted.reason)
        trials.append(admitted.trial)
    return tuple(trials)


def comparable(champion: Candidate, candidate: Candidate) -> None:
    environments = tuple(
        Environment.model_validate_json((Path(item.benchmark) / "environment.json").read_bytes())
        for item in (champion, candidate)
    )
    if environments[0].workspace == environments[1].workspace:
        raise ValueError("INDEPENDENT_CANDIDATE_WORKTREE_NEEDED")
    keys = (
        "pi_package",
        "pi_version",
        "auth",
        "mcp_command",
        "model",
        "provider",
        "effort",
        "cache_policy",
    )
    if any(getattr(environments[0], key) != getattr(environments[1], key) for key in keys):
        raise ValueError("ENVIRONMENT_MISMATCH")
    manifests = tuple(
        TypeAdapter(dict[str, JsonValue]).validate_json(
            (Path(item.benchmark) / "manifest.json").read_bytes()
        )
        for item in (champion, candidate)
    )
    if any(manifests[0][key] != manifests[1][key] for key in ("corpusSha256", "revision")):
        raise ValueError("CORPUS_OR_REVISION_MISMATCH")
    configs = tuple(
        Configuration.model_validate_json(Path(item.configuration).read_bytes())
        for item in (champion, candidate)
    )
    budgets = (
        "maximum_requests",
        "maximum_tools",
        "wall_seconds",
        "maximum_reported_tokens",
        "maximum_payload_bytes",
        "maximum_output_tokens",
    )
    if any(getattr(configs[0], key) != getattr(configs[1], key) for key in budgets):
        raise ValueError("RESOURCE_LIMIT_MISMATCH")
    changed = sum(
        getattr(configs[0], key) != getattr(configs[1], key)
        for key in ("exposure", "onboarding", "skills")
    )
    if changed != 1:
        raise ValueError("ONE_ATTRIBUTABLE_CONFIGURATION_CHANGE_NEEDED")


def optimize(plan: OptimizationPlan) -> None:
    output = Path(plan.output).resolve()
    output.mkdir(mode=0o700)
    save(output / "plan.json", plan.model_dump(mode="json"))
    champion = plan.champion
    champion_run = output / "champion"
    execute(
        Path(champion.benchmark),
        Path(champion.configuration),
        champion_run,
        plan.development_task_ids,
        plan.repeats,
        False,
    )
    decisions: list[dict[str, JsonValue]] = []
    for index, candidate in enumerate(plan.candidates):
        comparable(champion, candidate)
        run = output / f"candidate-{index}"
        execute(
            Path(candidate.benchmark),
            Path(candidate.configuration),
            run,
            plan.development_task_ids,
            plan.repeats,
            False,
        )
        control = tuple(
            replace(item, arm="control") for item in read_trials(champion_run, "treatment")
        )
        treatment = read_trials(run, "treatment")
        measured = compare((*control, *treatment), expected_task_ids=plan.development_task_ids)
        # A candidate's final answers and file assertions do not audit every
        # intermediate selection or claim. Keep measured gains without an
        # automatic promotion that erases that outstanding obligation.
        decisions.append(
            {
                "candidate": index,
                "decision": "NOT_PROMOTED",
                "reason": "INDEPENDENT_SEMANTIC_AUDIT_AND_BILLED_COST_NEEDED",
                "comparison": TypeAdapter(JsonValue).validate_python(measured),
            }
        )
        save(output / f"candidate-{index}-comparison.json", decisions[-1])
    save(
        output / "result.json",
        {
            "type": "TARGET_UNMET",
            "champion": champion.model_dump(mode="json"),
            "decisions": decisions,
            "costBasis": plan.cost_basis,
        },
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--plan", required=True, type=Path)
    args = parser.parse_args()
    plan = OptimizationPlan.model_validate_json(args.plan.read_bytes())
    optimize(plan)


if __name__ == "__main__":
    main()
