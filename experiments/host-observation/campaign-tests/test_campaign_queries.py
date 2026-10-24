"""Independent source and result oracles for campaign plans. No live-host claims."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import campaign_queries as c


def witness() -> c.SourceWitness:
    text = "// 😀\npublic fun selected() = 1\n"
    corpus = c.Corpus(
        name="case",
        file="src/Target.kt",
        sha256=hashlib.sha256(text.encode()).hexdigest(),
        source_set="main",
        name_selected="selected",
        anchor="public fun selected()",
        direct_witness="sink",
    )
    return c.SourceWitness(
        corpus=corpus, absolute_file="/repo/src/Target.kt", utf16_offset=16, text=text
    )


def complete(items: list[c.JsonValue]) -> c.JsonObject:
    return {
        "status": "complete",
        "coverage": {"exhaustive": True},
        "items": items,
        "failures": [],
        "omissions": [],
    }


def test_source_hash_and_unique_anchor_precede_selection(tmp_path: Path) -> None:
    expected = witness()
    path = tmp_path / expected.corpus.file
    path.parent.mkdir()
    path.write_text(expected.text)
    admitted = c.load_witness(expected.corpus, tmp_path)
    assert isinstance(admitted, c.SourceWitness)
    assert (
        admitted.utf16_offset
        == len(
            expected.text[
                : expected.text.index(expected.corpus.anchor) + len(expected.corpus.anchor) - 1
            ].encode("utf-16-le")
        )
        // 2
    )
    path.write_text(expected.text + "// moved\n")
    assert c.load_witness(expected.corpus, tmp_path) == c.Blocked(reason=c.Failure.SOURCE_CHANGED)


def test_exact_selection_uses_independent_file_and_offset() -> None:
    item: c.JsonObject = {
        "name": "selected",
        "ref": "exact:v5:issued",
        "location": {
            "file": "/repo/src/Target.kt",
            "range": {"startInclusive": 7, "endExclusive": 35},
        },
    }
    assert c.select_exact(complete([item]), witness()) == c.Selected(
        ref="exact:v5:issued", name="selected"
    )
    assert c.select_exact(complete([item, item]), witness()) == c.Blocked(
        reason=c.Failure.SELECTION_AMBIGUOUS
    )
    changed: c.JsonObject = {
        **item,
        "location": {
            "file": "/other/Target.kt",
            "range": {"startInclusive": 7, "endExclusive": 35},
        },
    }
    assert c.select_exact(complete([changed]), witness()) == c.Blocked(
        reason=c.Failure.SELECTION_UNAVAILABLE
    )


def test_incomplete_empty_result_cannot_pass_empty_oracle() -> None:
    partial = complete([])
    partial["status"] = "rejected"
    assert c.assess(c.CompleteCount(count=0), (partial,), True, witness()) == c.Failed(
        reason=c.Failure.INCOMPLETE_RESPONSE
    )
    assert isinstance(
        c.assess(c.CompleteCount(count=0), (complete([]),), True, witness()), c.Passed
    )


def test_complete_page_cannot_erase_rejected_producer() -> None:
    initial = complete([])
    initial["status"] = "rejected"
    assert c.assess(c.CompleteCount(count=0), (initial, complete([])), True, witness()) == c.Failed(
        reason=c.Failure.INCOMPLETE_RESPONSE
    )


def test_exact_rejection_does_not_accept_another_reason() -> None:
    expected = c.Rejection(codes=("region-absent",))
    rejected: c.JsonObject = {
        "status": "rejected",
        "rejection": {"detail": {"reason": "region-absent"}},
    }
    assert isinstance(c.assess(expected, (rejected,), True, witness()), c.Passed)
    rejected["rejection"] = {"detail": {"reason": "region-not-applicable"}}
    assert c.assess(expected, (rejected,), True, witness()) == c.Failed(
        reason=c.Failure.REJECTION_MISMATCH
    )


def test_rejection_oracle_needs_a_rejected_response() -> None:
    response = complete([{"type": "region-absent"}])
    assert c.assess(
        c.Rejection(codes=("region-absent",)), (response,), True, witness()
    ) == c.Failed(reason=c.Failure.REJECTION_MISMATCH)


def test_split_utf16_scalar_is_a_failed_source_oracle() -> None:
    response: c.JsonObject = {
        "status": "complete",
        "snapshot": {"canonicalRoot": "/repo", "file": "src/Target.kt"},
        "region": {"kind": "anchor"},
        "text": {
            "type": "returned",
            "text": "invented",
            "selection": {"range": {"startInclusive": 3, "endExclusive": 4}},
        },
    }
    assert c.assess(
        c.SourceSlice(region="ANCHOR", text_mode="COMPLETE"), (response,), True, witness()
    ) == c.Failed(reason=c.Failure.SOURCE_TEXT_MISMATCH)


def test_source_text_uses_utf16_range_and_exact_original_text() -> None:
    expected = witness()
    length = len(expected.text.encode("utf-16-le")) // 2
    response: c.JsonObject = {
        "status": "complete",
        "snapshot": {"canonicalRoot": "/repo", "file": "src/Target.kt"},
        "region": {"kind": "file"},
        "text": {
            "type": "returned",
            "text": expected.text,
            "selection": {"range": {"startInclusive": 0, "endExclusive": length}},
        },
    }
    oracle = c.SourceSlice(region="FILE", text_mode="COMPLETE")
    assert isinstance(c.assess(oracle, (response,), True, expected), c.Passed)
    text = response["text"]
    assert isinstance(text, dict)
    text["text"] = "invented"
    assert c.assess(oracle, (response,), True, expected) == c.Failed(
        reason=c.Failure.SOURCE_TEXT_MISMATCH
    )


def test_planned_variants_preserve_issued_refs_and_unique_ids() -> None:
    variants = c.selected_variants(
        witness(),
        c.Selected(ref="exact:v5:issued", name="selected"),
        c.Retained(result="result:v1:issued"),
    )
    assert len({v.id for v in variants}) == len(variants)
    assert all(v.scope == "VARIANT_ONLY" for v in variants)
    assert sum(v.id.startswith("B09-") for v in variants) == 28
    for variant in variants:
        request: c.JsonValue = json.loads(variant.request_json)
        assert isinstance(request, dict)
        action = request["request"]
        assert isinstance(action, dict)
        if action["type"] == "RUN":
            budget = action["executionBudget"]
            assert isinstance(budget, dict)
            if "maxWorkUnits" in budget:
                assert action.get("executionMode") == "BOUNDED"


def test_source_variant_ids_match_the_checklist_owners() -> None:
    for variant in c.source_variants(witness().corpus):
        request: c.JsonValue = json.loads(variant.request_json)
        assert isinstance(request, dict)
        action = request["request"]
        assert isinstance(action, dict)
        source = action["source"]
        assert isinstance(source, dict)
        owner = {"ALL_DECLARATIONS": "B03-", "SEARCH_TEXT": "B04-"}[str(source["type"])]
        assert variant.id.startswith(owner)


def test_rejected_retention_cannot_supply_composition_input() -> None:
    assert c.retained_result({"retention": {"kind": "not_requested"}}) == c.Blocked(
        reason=c.Failure.RETENTION_UNAVAILABLE
    )
    assert c.retained_result(
        {"retention": {"kind": "retained", "reference": "exact:v5:wrong-family"}}
    ) == c.Blocked(reason=c.Failure.RETENTION_UNAVAILABLE)


def test_empty_retained_selection_keeps_omitted_row_qualification() -> None:
    variants = c.selected_variants(
        witness(),
        c.Selected(ref="exact:v5:issued", name="selected"),
        c.Retained(result="result:v1:issued"),
    )
    selected = next(v for v in variants if v.id == "B05-retained-empty-selection")
    assert selected.oracle == c.Rejection(codes=("ROW_SELECTION_INCOMPLETE",))
    response: c.JsonObject = {
        "status": "rejected",
        "rejection": {
            "type": "COMPLETION_UNPROVEN",
            "detail": {"originalCoverage": {"limitations": ["ROW_SELECTION_INCOMPLETE"]}},
        },
    }
    assert isinstance(c.assess(selected.oracle, (response,), True, witness()), c.Passed)


def test_relation_oracle_keeps_exact_fact_and_one_hop_depth() -> None:
    relation: c.JsonObject = {
        "meaning": "callees",
        "coverage": "exact-compiler-confirmed",
        "provenance": "k2-authored-source",
        "source": {"name": "selected"},
        "target": {"name": "sink"},
        "occurrence": {
            "file": "/repo/src/Target.kt",
            "range": {"startInclusive": 8, "endExclusive": 20},
        },
    }
    for projection in ("OCCURRENCES", "TRAVERSAL_RECORDS"):
        oracle = c.RelationWitness(projection=projection, target="sink")
        item: c.JsonObject = (
            {"type": "occurrence", "relation": relation}
            if projection == "OCCURRENCES"
            else {"type": "traversal_record", "record": {"depth": 1, "relation": relation}}
        )
        assert isinstance(c.assess(oracle, (complete([item]),), True, witness()), c.Passed)
        relation["target"] = {"name": "unrelated"}
        assert c.assess(oracle, (complete([item]),), True, witness()) == c.Failed(
            reason=c.Failure.WITNESS_MISSING
        )
        relation["target"] = {"name": "sink"}
        if projection == "TRAVERSAL_RECORDS":
            item["record"] = {"depth": 2, "relation": relation}
            assert c.assess(oracle, (complete([item]),), True, witness()) == c.Failed(
                reason=c.Failure.WITNESS_MISSING
            )


def test_relations_tranche_keeps_baseline_separate_and_declares_negative_controls() -> None:
    variants = c.relation_variants(witness(), c.Selected(ref="exact:v5:issued", name="selected"))
    assert len(variants) == len({v.id for v in variants}) == 8
    assert {v.id[:3] for v in variants} == {"C07", "C08", "C09"}
    assert sum(isinstance(v.oracle, c.CompleteCount) and v.oracle.count == 0 for v in variants) == 3
    assert sum(isinstance(v.oracle, c.RelationWitness) for v in variants) == 2


def test_source_tranche_has_positive_and_negative_oracles_in_direct_scope() -> None:
    variants = c.source_variants(witness().corpus)
    assert len(variants) == len({v.id for v in variants}) == 4
    assert sum(isinstance(v.oracle, c.CompleteCount) and v.oracle.count == 0 for v in variants) == 2
    assert sum(isinstance(v.oracle, c.CompleteWitness) for v in variants) == 2
    for variant in variants:
        request = c.json_object(variant.request_json)
        assert isinstance(request, dict)
        action = request["request"]
        assert isinstance(action, dict)
        source = action["source"]
        assert isinstance(source, dict)
        if isinstance(variant.oracle, c.CompleteWitness) and source["type"] == "SEARCH_TEXT":
            assert source["word"] == witness().corpus.name_selected
        scope = source["scope"]
        assert isinstance(scope, dict)
        assert scope["includeSubdirectories"] is False
