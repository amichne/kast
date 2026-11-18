from dataclasses import asdict
from pathlib import Path

import pytest

from agent_efficiency import SessionReport, save
from agent_efficiency_metrics import Arm, Trial
from agent_efficiency_tokens import Row, analyze, ratio, summary


def test_failed_trials_consume_tokens():
    rows = (
        Row(
            task="a",
            repeat=0,
            arm="control",
            correct=True,
            known_tokens=100,
            total_tokens=100,
            requests=1,
            tools=1,
        ),
        Row(
            task="b",
            repeat=0,
            arm="control",
            correct=False,
            known_tokens=300,
            total_tokens=300,
            requests=2,
            tools=1,
        ),
    )
    measured = summary(rows)
    assert measured.total_tokens == 400
    assert measured.tokens_per_correct_completion == 400
    assert measured.median_tokens == 200
    assert measured.failures == 1


def test_incomplete_usage_is_unknown_instead_of_free():
    rows = (
        Row(
            task="a",
            repeat=0,
            arm="treatment",
            correct=True,
            known_tokens=100,
            total_tokens=100,
            requests=1,
            tools=1,
        ),
        Row(
            task="b",
            repeat=0,
            arm="treatment",
            correct=False,
            known_tokens=25,
            total_tokens=None,
            requests=2,
            tools=1,
        ),
    )
    measured = summary(rows)
    assert measured.known_tokens == 125
    assert measured.total_tokens is None
    assert measured.tokens_per_correct_completion is None
    assert measured.median_tokens is None
    assert measured.incomplete_usage_trials == 1


def test_no_correct_completion_has_no_finite_efficiency():
    row = Row(
        task="a",
        repeat=0,
        arm="control",
        correct=False,
        known_tokens=100,
        total_tokens=100,
        requests=1,
        tools=1,
    )
    assert summary((row,)).tokens_per_correct_completion is None
    assert ratio(None, 100) is None
    assert ratio(100, 0) is None
    assert ratio(600, 100) == 6


@pytest.mark.parametrize("unqualified", ("both_failed", "unknown_usage", None))
def test_aggregate_token_ranking_needs_every_correct_pair_with_complete_usage(
    tmp_path: Path, unqualified: str | None
) -> None:
    save(tmp_path / "run-manifest.json", {"tasks": ["first", "second"], "repeats": 1})
    arms: tuple[tuple[Arm, int], ...] = (("control", 100), ("treatment", 50))
    for task in ("first", "second"):
        correct = task == "first" or unqualified != "both_failed"
        for arm, tokens in arms:
            directory = tmp_path / f"{task}-0-{arm}"
            directory.mkdir()
            usage = (
                "INCOMPLETE"
                if unqualified == "unknown_usage" and task == "second" and arm == "treatment"
                else "FINALIZED_PROVIDER_USAGE"
            )
            trial = Trial(task, "source", arm, 0, correct, None, None, 1.0, None, tokens, 0, 0, 0)
            report = SessionReport(
                version=1,
                outcome="COMPLETED",
                wallSeconds=1.0,
                setupSeconds=0.0,
                provider="openai-codex",
                model="gpt-6.1-sol",
                effort="high",
                arm=arm,
                requests=1,
                toolCalls=0,
                retries=0,
                compactions=0,
                inputTokens=tokens,
                outputTokens=0,
                cacheReadTokens=0,
                cacheWriteTokens=0,
                totalTokens=tokens,
                payloadBytes=0,
                toolOutputBytes=0,
                catalogCostUsd=None,
                billedCostUsd=None,
                costQualification="BILLED_DOLLARS_UNAVAILABLE",
                usageQualification=usage,
                outputCapQualification="REQUESTED_BACKEND_ENFORCEMENT_UNPROVEN",
                planSha256="0" * 64,
                piPackageSha256="0" * 64,
                mcpExecutableSha256=None,
                onboardingSha256="0" * 64,
            )
            save(directory / "trial.json", asdict(trial))
            save(directory / "plan.json.result.json", {"report": report.model_dump(mode="json")})

    measured = analyze(tmp_path)
    assert measured.control.known_tokens == 200
    assert measured.treatment.known_tokens == 100
    assert measured.all_usage_complete == (unqualified != "unknown_usage")
    if unqualified is None:
        assert measured.paired_correct_median_ratio == 2
        assert measured.aggregate_tokens_per_correct_ratio == 2
        assert measured.aggregate_token_savings_percent == 50
    else:
        assert measured.paired_correct_control_median is None
        assert measured.paired_correct_treatment_median is None
        assert measured.paired_correct_median_ratio is None
        assert measured.aggregate_tokens_per_correct_ratio is None
        assert measured.aggregate_token_savings_percent is None
