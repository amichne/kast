"""Prose receipts preserve failures and source-bound advisory review."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path

from prose_assessment import (
    Command,
    Disposition,
    Failure,
    Finding,
    ProcessResult,
    State,
    assess_findings,
    assess_review_identity,
    findings,
    parse_dispositions,
    plan,
    run,
    run_command,
)


def test_valid_empty_vale_report_stays_distinct_from_malformed_output(tmp_path: Path) -> None:
    assert findings({}, tmp_path, "root") == ()
    assert isinstance(findings([], tmp_path, "root"), Failure)
    assert isinstance(findings({"missing.md": []}, tmp_path, "root"), Failure)


def test_malformed_severity_rejects_without_an_unhashable_exception(tmp_path: Path) -> None:
    (tmp_path / "page.md").write_text("Known prose.\n")
    for severity in ({"status": "warning"}, ["warning"]):
        raw = {
            "page.md": [
                {"Check": "Prose.Complexity", "Line": 1, "Message": "Review.", "Severity": severity}
            ]
        }
        assert isinstance(findings(raw, tmp_path, "root"), Failure)
        disposition = {
            "schemaVersion": 1,
            "dispositions": [
                {
                    "finding": {
                        "stage": "root",
                        "path": "page.md",
                        "sourceSha256": "a" * 64,
                        "rule": "Prose.Complexity",
                        "line": 1,
                        "message": "Review.",
                        "severity": severity,
                    },
                    "reason": "A review reason.",
                }
            ],
        }
        assert isinstance(parse_dispositions(disposition), Failure)


def test_review_identity_keeps_the_frozen_policy_digest() -> None:
    frozen = b'{"accepted":"initial"}'
    original = assess_review_identity("reviews/dispositions.json", frozen, frozen)
    changed = assess_review_identity("reviews/dispositions.json", frozen, b'{"accepted":"changed"}')
    assert original.status == State.PASSED
    assert changed.status == State.FAILED
    assert original.evidence == changed.evidence


def test_changed_prose_invalidates_accepted_advisory(tmp_path: Path) -> None:
    page = tmp_path / "page.md"
    page.write_text("A technical assertion.\n")
    raw = {
        "page.md": [
            {
                "Check": "Prose.Complexity",
                "Line": 1,
                "Message": "Review this sentence.",
                "Severity": "warning",
            }
        ]
    }
    initial = findings(raw, tmp_path, "reader-prose-3.25.1")
    assert not isinstance(initial, Failure)
    dispositions = (Disposition(initial[0], "The technical distinction needs its canonical name."),)
    assert assess_findings(initial, dispositions)[0] == State.PASSED
    page.write_text("A different technical assertion.\n")
    changed = findings(raw, tmp_path, "reader-prose-3.25.1")
    assert not isinstance(changed, Failure)
    assert assess_findings(changed, dispositions)[0] == State.FAILED


def test_advisory_without_explicit_disposition_fails() -> None:
    warning = Finding(
        "root", "page.md", "a" * 64, "Slopsentral.Grade", 1, "Grade exceeds 8.", "warning"
    )
    assert assess_findings((warning,), ())[0] == State.FAILED


def test_errors_cannot_be_admitted_by_dispositions() -> None:
    error = Finding(
        "root", "page.md", "a" * 64, "Kast.Terminology", 1, "Use the canonical term.", "error"
    )
    accepted = (Disposition(error, "This cannot override a blocking error."),)
    assert assess_findings((error,), accepted)[0] == State.FAILED
    raw = {"schemaVersion": 1, "dispositions": [asdict(item) for item in accepted]}
    assert isinstance(parse_dispositions(raw), Failure)


def test_disposition_parser_preserves_exact_finding_identity() -> None:
    finding = Finding(
        "reader-prose-3.25.1",
        "page.md",
        "a" * 64,
        "Prose.Complexity",
        7,
        "Necessary technical term.",
        "suggestion",
    )
    disposition = Disposition(finding, "The source owner needs this exact term.")
    encoded: object = json.loads(
        json.dumps({"schemaVersion": 1, "dispositions": [asdict(disposition)]})
    )
    assert parse_dispositions(encoded) == (disposition,)
    assert isinstance(parse_dispositions({"schemaVersion": True, "dispositions": []}), Failure)


def test_plan_keeps_both_engines_and_every_publication_obligation(tmp_path: Path) -> None:
    steps = plan(
        tmp_path,
        tmp_path / "build/prose",
        "/root/vale-3.24",
        "/reader/vale-3.25",
        "/tool/mint",
        ("docs/public/start.mdx",),
    )
    assert {step.name for step in steps} == {
        "vale",
        "terminology",
        "examples",
        "reader-audit",
        "mint-validate",
        "broken-links",
        "callable-parity",
        "glossary-parity",
    }
    assert steps[0].commands[0].arguments[0] == "/root/vale-3.24"
    assert "/reader/vale-3.25" in steps[3].commands[0].arguments
    assert all(
        "verify_documentation.py" not in part
        for step in steps
        for command in step.commands
        for part in command.arguments
    )


def test_real_process_adapter_preserves_nonzero_result(tmp_path: Path) -> None:
    result = run_command(
        tmp_path,
        Command((sys.executable, "-c", "import sys; print('reported failure'); sys.exit(3)")),
    )
    assert isinstance(result, ProcessResult)
    assert result.returncode == 3
    assert result.stdout == "reported failure\n"
    assert isinstance(run_command(tmp_path, Command(("kast-prose-no-such-executable",))), Failure)


def test_runner_rejects_evidence_writes_to_source(tmp_path: Path) -> None:
    result = run(
        tmp_path, tmp_path / "docs/prose", "vale", "vale", "mint", tmp_path / "dispositions.json"
    )
    assert isinstance(result, Failure)
    assert result.stage == "output"
