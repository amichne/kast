"""Provide one source-witness semantic variant per Promptfoo evaluation row."""

from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import asdict
from pathlib import Path
from typing import Literal

from pydantic import Field

import campaign_queries as wire
from hosted_timing import ObservationWindow, TimingFailure, collect_observations
from mcp_replay import (
    HERE,
    MatchedDiagnostic,
    McpReplaySession,
    ReplayCall,
    call_timing,
    correlate_diagnostic,
    digest,
    drain_workload,
    fresh,
    response_read_released,
    write,
)


class CampaignPlan(wire.Frozen):
    repository: Literal["spring-framework", "ktor", "kotlinx.coroutines", "kast"]
    fixture: Path
    cli: Path
    idea_log: Path
    output: Path
    tranche: Literal["baseline", "relations", "sources"] = "baseline"
    case_prefixes: tuple[str, ...] = ()
    max_calls: int = Field(default=64, ge=1, le=1024)
    timeout: int = Field(default=60, ge=1, le=300)


class Row(wire.Frozen):
    caseId: str


class Request(wire.Frozen):
    action: Literal["cases", "prepare", "trial", "finish"]
    plan: Path
    row: Row | None = None


SourceStatus = Literal["UNCHANGED"] | wire.Blocked
VariantOutcome = wire.Passed | wire.Failed | wire.Blocked
FINAL_SOURCE = "final-source-witness"


class VariantReceipt(wire.Frozen):
    id: str
    scope: Literal["VARIANT_ONLY"] = "VARIANT_ONLY"
    oracle: wire.Oracle
    outcome: VariantOutcome
    calls: int
    correlations: tuple[str, ...]
    responseStates: tuple[str | None, ...]


class RowReceipt(wire.Frozen):
    id: str
    scope: Literal["VARIANT_ONLY"] = "VARIANT_ONLY"
    outcome: VariantOutcome
    sourceAtEnd: SourceStatus


class NotRun(wire.Frozen):
    status: Literal["NOT_RUN"] = "NOT_RUN"
    reason: Literal["NOT_SELECTED", "MISSING_PROMPTFOO_ROW"]


class MissingReceipt(wire.Frozen):
    id: str
    outcome: NotRun


class Artifact(wire.Frozen):
    path: str
    sha256: str


class CampaignManifest(wire.Frozen):
    schemaVersion: Literal[2] = 2
    type: Literal["CHECKLIST_VARIANTS"] = "CHECKLIST_VARIANTS"
    repository: str
    fixture: str
    sourceWitness: wire.Corpus
    cli: Artifact
    harness: dict[str, str]
    authority: Literal["INSTALLED_CALLS_WITH_SOURCE_WITNESS_NOT_FULL_NATIVE_PIN"] = (
        "INSTALLED_CALLS_WITH_SOURCE_WITNESS_NOT_FULL_NATIVE_PIN"
    )
    checklistClaim: Literal["VARIANTS_ONLY_NO_CASE_OR_FAMILY_COMPLETION"] = (
        "VARIANTS_ONLY_NO_CASE_OR_FAMILY_COMPLETION"
    )
    tranche: Literal["baseline", "relations", "sources"]
    timeoutSeconds: int
    maxCalls: int
    sessionPolicy: Literal["FRESH_SESSION_PER_VARIANT"] = "FRESH_SESSION_PER_VARIANT"
    planSha256: str
    expectedVariants: tuple[str, ...]
    variants: tuple[RowReceipt | MissingReceipt, ...] = ()
    sourceAtEnd: SourceStatus | None = None


class FinalSource(wire.Frozen):
    sourceAtEnd: SourceStatus


def discovery_case(corpus: wire.Corpus) -> wire.Variant:
    return wire.variant(
        "B02-source-anchored-selection",
        wire.run(wire.search(corpus, corpus.name_selected)),
        wire.CompleteWitness(name=corpus.name_selected),
    )


def retention_case(selected: wire.Selected) -> wire.Variant:
    return wire.variant(
        "B05-retain-exact-seed",
        wire.run(dict(type="SYMBOL_REFS", symbolRefs=[selected.ref]), retain=True),
        wire.CompleteCount(count=1),
    )


def variants(
    tranche: Literal["baseline", "relations", "sources"],
    witness: wire.SourceWitness,
    selected: wire.Selected,
    retained: wire.Retained,
) -> tuple[wire.Variant, ...]:
    if tranche == "relations":
        return wire.relation_variants(witness, selected)
    if tranche == "sources":
        return wire.source_variants(witness.corpus)
    return (
        *wire.discovery_variants(witness.corpus),
        *wire.selected_variants(witness, selected, retained),
    )


def declarations(plan: CampaignPlan, corpus: wire.Corpus) -> tuple[wire.Variant, ...]:
    # Enumerate IDs without the host. The provider resolves the same
    # definitions with fresh references before it sends a request.
    witness = wire.SourceWitness(
        corpus=corpus, absolute_file=str(plan.fixture / corpus.file), utf16_offset=0, text=""
    )
    selected = wire.Selected(ref="placeholder", name=corpus.name_selected)
    retained = wire.Retained(result="placeholder")
    return (
        discovery_case(corpus),
        retention_case(selected),
        *variants(plan.tranche, witness, selected, retained),
    )


def ids(plan: CampaignPlan, corpus: wire.Corpus) -> tuple[str, ...]:
    discovery, retention, *cases = declarations(plan, corpus)
    admitted = tuple(
        case.id
        for case in cases
        if not plan.case_prefixes or case.id.startswith(plan.case_prefixes)
    )
    if not admitted:
        raise ValueError("UNKNOWN_CAMPAIGN_SELECTION")
    return discovery.id, retention.id, *admitted, FINAL_SOURCE


def prepare(plan: CampaignPlan, corpus: wire.Corpus, plan_path: Path) -> None:
    output = fresh(plan.output)
    manifest = CampaignManifest(
        repository=corpus.name,
        fixture=str(plan.fixture.resolve()),
        sourceWitness=corpus,
        cli=Artifact(path=str(plan.cli.resolve()), sha256=digest(plan.cli)),
        harness={
            name: digest(HERE / name)
            for name in (
                "campaign_promptfoo.py",
                "campaign_queries.py",
                "mcp_replay.py",
                "hosted_timing.py",
                "retained_pages.py",
            )
        },
        tranche=plan.tranche,
        timeoutSeconds=plan.timeout,
        maxCalls=plan.max_calls,
        planSha256=digest(plan_path),
        expectedVariants=ids(plan, corpus),
    )
    write(output / "campaign.json", manifest.model_dump(mode="json"))


def observe(
    plan: CampaignPlan,
    case: wire.Variant,
    witness: wire.SourceWitness,
    session: McpReplaySession,
    directory: Path,
) -> tuple[VariantOutcome, wire.JsonObject]:
    request = json.loads(case.request_json)
    fresh(directory)
    calls: list[ReplayCall] = []

    def invoke(request):
        call_dir = fresh(directory / f"call-{len(calls):04d}")
        try:
            before = plan.idea_log.stat()
        except OSError:
            before = None
        process = session.call(request)
        response = None
        try:
            envelope = json.loads(process["stdout"])
            if isinstance(envelope.get("error"), dict):
                response = dict(failure=str(envelope["error"].get("code")), status="rejected")
            else:
                response = envelope.get("result", {}).get("structuredContent")
            if not isinstance(response, dict):
                response = None
        except (ValueError, AttributeError):
            pass
        capture_started = time.monotonic_ns()
        observations = (
            collect_observations(
                plan.idea_log, before, ready=lambda window: response_read_released(response, window)
            )
            if before
            else ObservationWindow(failures=(TimingFailure.LOG_UNAVAILABLE,))
        )
        capture_nanos = time.monotonic_ns() - capture_started
        correlated = correlate_diagnostic(response, observations.diagnostics)
        diagnostics = (
            [correlated.document]
            if isinstance(correlated, MatchedDiagnostic)
            else list(observations.diagnostics)
        )
        correlation = (
            "MATCHED" if isinstance(correlated, MatchedDiagnostic) else correlated.failure.value
        )
        call = ReplayCall(
            request["request"]["type"],
            request,
            process,
            response,
            diagnostics,
            list(observations.phases),
            correlation,
            observations,
            capture_nanos,
        )
        calls.append(call)
        write(call_dir / "request.json", request)
        write(call_dir / "process.json", process)
        write(call_dir / "hosted-observations.json", asdict(observations))
        write(call_dir / "hosted-timing.json", asdict(call_timing(call)))
        if response is not None:
            write(call_dir / "response.json", response)
        return call

    if request["request"]["type"] == "RUN":
        drain_workload(request, invoke, plan.max_calls)
    else:
        invoke(request)
    documents = tuple(wire.json_object(json.dumps(call.response)) for call in calls)
    if any(isinstance(doc, wire.Blocked) for doc in documents):
        outcome = wire.Blocked(reason=wire.Failure.INVALID_RESPONSE)
    else:
        outcome = wire.assess(
            case.oracle, documents, all(c.process["outcome"] == "completed" for c in calls), witness
        )
    result = VariantReceipt(
        id=case.id,
        oracle=case.oracle,
        outcome=outcome,
        calls=len(calls),
        correlations=tuple(c.correlation for c in calls),
        responseStates=tuple(
            c.response.get("status", c.response.get("outcome")) if c.response else None
            for c in calls
        ),
    )
    write(directory / "variant.json", result.model_dump(mode="json"))
    return outcome, calls[-1].response or {}


def execute_variant(
    plan: CampaignPlan,
    corpus: wire.Corpus,
    case_id: str,
    witness: wire.SourceWitness,
    directory: Path,
) -> VariantOutcome:
    discovery = discovery_case(corpus)
    try:
        session = McpReplaySession(
            plan.cli.resolve(), plan.fixture.resolve(), directory, plan.timeout
        )
    except (OSError, ValueError):
        return wire.Blocked(reason=wire.Failure.PROCESS_UNAVAILABLE)
    try:
        try:
            catalog = session.catalog()
        except (OSError, ValueError):
            return wire.Blocked(reason=wire.Failure.PROCESS_UNAVAILABLE)
        write(directory / "catalog.json", catalog)
        outcome, response = observe(plan, discovery, witness, session, directory / discovery.id)
        if not isinstance(outcome, wire.Passed):
            return outcome
        selected = wire.select_exact(response, witness)
        if isinstance(selected, wire.Blocked):
            return selected
        if case_id == discovery.id:
            return outcome

        retention = retention_case(selected)
        outcome, response = observe(plan, retention, witness, session, directory / retention.id)
        if not isinstance(outcome, wire.Passed):
            return outcome
        retained = wire.retained_result(response)
        if isinstance(retained, wire.Blocked):
            return retained
        if case_id == retention.id:
            return outcome

        case = next(
            case
            for case in variants(plan.tranche, witness, selected, retained)
            if case.id == case_id
        )
        outcome, _ = observe(plan, case, witness, session, directory / case.id)
        return outcome
    finally:
        session.close()


def evaluate(
    plan: CampaignPlan, corpus: wire.Corpus, case_id: str, expected: tuple[str, ...]
) -> VariantOutcome:
    if case_id not in expected:
        raise ValueError("UNKNOWN_CAMPAIGN_VARIANT")
    directory = fresh(plan.output / case_id)
    witness = wire.load_witness(corpus, plan.fixture.resolve())
    if isinstance(witness, wire.Blocked):
        outcome, ending = witness, witness
    elif case_id == FINAL_SOURCE:
        outcome, ending = wire.Passed(assertion="UNCHANGED_SOURCE_WITNESS"), witness
    else:
        outcome = execute_variant(plan, corpus, case_id, witness, directory)
        ending = wire.load_witness(corpus, plan.fixture.resolve())
        if isinstance(ending, wire.Blocked):
            outcome = ending
    receipt = RowReceipt(
        id=case_id,
        outcome=outcome,
        sourceAtEnd=ending if isinstance(ending, wire.Blocked) else "UNCHANGED",
    )
    write(directory / "row.json", receipt.model_dump(mode="json"))
    return outcome


def finish(plan: CampaignPlan, corpus: wire.Corpus, manifest: CampaignManifest) -> FinalSource:
    receipts: list[RowReceipt | MissingReceipt] = []
    declared_ids = (*[case.id for case in declarations(plan, corpus)], FINAL_SOURCE)
    for case_id in declared_ids:
        row = plan.output / case_id / "row.json"
        if row.is_file():
            receipts.append(RowReceipt.model_validate_json(row.read_bytes()))
        else:
            reason = (
                "MISSING_PROMPTFOO_ROW" if case_id in manifest.expectedVariants else "NOT_SELECTED"
            )
            receipts.append(MissingReceipt(id=case_id, outcome=NotRun(reason=reason)))
    ending = wire.load_witness(corpus, plan.fixture.resolve())
    source_status = ending if isinstance(ending, wire.Blocked) else "UNCHANGED"
    closed = manifest.model_copy(update={"variants": tuple(receipts), "sourceAtEnd": source_status})
    write(plan.output / "campaign.json", closed.model_dump(mode="json"))
    return FinalSource(sourceAtEnd=source_status)


def main() -> None:
    os.umask(0o077)
    request = Request.model_validate_json(sys.stdin.buffer.read())
    plan = CampaignPlan.model_validate_json(request.plan.read_bytes())
    corpus = next(c for c in wire.CORPORA if c.name == plan.repository)
    if request.action == "cases":
        print(json.dumps([Row(caseId=case_id).model_dump() for case_id in ids(plan, corpus)]))
        return
    if request.action == "prepare":
        prepare(plan, corpus, request.plan)
        print("null")
        return
    manifest = CampaignManifest.model_validate_json((plan.output / "campaign.json").read_bytes())
    if manifest.planSha256 != digest(request.plan) or manifest.cli.sha256 != digest(plan.cli):
        raise ValueError("CAMPAIGN_PLAN_OR_ARTIFACT_CHANGED")
    if request.action == "trial":
        if request.row is None:
            raise ValueError("CAMPAIGN_ROW_REQUIRED")
        print(
            evaluate(plan, corpus, request.row.caseId, manifest.expectedVariants).model_dump_json()
        )
    else:
        print(finish(plan, corpus, manifest).model_dump_json())


if __name__ == "__main__":
    main()
