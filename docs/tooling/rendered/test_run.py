"""Independent counts and rejection cases for rendered evidence."""

import hashlib
import json
from pathlib import Path

import pytest
from run import (
    Browser,
    BrowserFailure,
    DomEvidence,
    Footprint,
    Snapshot,
    State,
    footprint,
    is_dom,
    is_envelope,
    receipt,
)


def dom() -> DomEvidence:
    return {
        "title": "A page",
        "route": "/",
        "segments": [
            {"text": "A source-backed answer.", "initialViewport": True},
            {"text": "Keep the second panel.", "initialViewport": False},
        ],
        "code": ["first()\n\nsecond()\n"],
        "fonts": [],
        "browser": "fixture",
        "viewport": {"width": 1440, "height": 1000, "deviceScaleFactor": 1, "visualScale": 1},
        "theme": "light",
        "documentOverflow": False,
        "headingCount": 1,
        "missingImageAlternatives": 0,
        "focus": None,
        "controls": [],
    }


def test_whole_state_counts_include_text_outside_initial_viewport() -> None:
    assert footprint(dom()) == Footprint(W=7, L=2, initialViewportW=3)


def test_missing_scope_cannot_become_empty_current_evidence() -> None:
    assert not is_dom({})
    assert not is_dom(None)


def test_wrong_segment_type_rejects_before_count() -> None:
    value = json.loads(json.dumps(dom()))
    value["segments"][0]["initialViewport"] = "yes"
    assert not is_dom(value)


def test_unknown_fixed_fields_reject() -> None:
    value = json.loads(json.dumps(dom()))
    value["invented"] = True
    assert not is_dom(value)


def test_exact_dom_shape_admits() -> None:
    assert is_dom(dom())


def test_browser_response_rejects_false_success_shapes() -> None:
    assert is_envelope({"success": True, "data": {"result": {}}, "error": None})
    assert not is_envelope({"success": "yes", "data": {}, "error": None})
    assert not is_envelope({"success": True, "data": "empty", "error": None})


def test_failed_route_keeps_measurements_unknown() -> None:
    state = State(
        "/reference/callables/query-symbols",
        "initial",
        "failed",
        None,
        None,
        None,
        "SCHEMA_RENDER_FAILED",
    )
    assert state.footprint is None
    assert state.dom is None
    assert state.failure == "SCHEMA_RENDER_FAILED"


def test_partial_capture_rejects_publication_with_evidence(tmp_path: Path) -> None:
    path = tmp_path / "report.json"
    _ = path.write_text("{}\n")
    snapshot: Snapshot = {
        "type": "DOCUMENTATION_INPUTS",
        "sourceRevision": "a" * 40,
        "inputs": [],
        "inputsDigest": "b" * 64,
    }
    result = receipt(snapshot, [], tmp_path, input_match=True, root=tmp_path)
    assert result["status"] == "failed"
    assert result["sourceRevision"] == "a" * 40
    checks = result["checks"]
    encoded = json.dumps(checks)
    assert encoded.count('"status": "failed"') == 8
    assert hashlib.sha256(b"{}\n").hexdigest() in encoded


def test_effect_failure_is_explicit(tmp_path: Path) -> None:
    browser = Browser("case-owned-unavailable-browser")
    browser.binary = tmp_path / "missing-browser"
    with pytest.raises(BrowserFailure, match="FileNotFoundError"):
        _ = browser.call("open", "about:blank")


def test_success_without_measurements_rejects() -> None:
    with pytest.raises(ValueError, match="measured DOM evidence"):
        _ = State("/", "initial", "passed", None, None, None, None)


def test_unrun_state_cannot_claim_measurement() -> None:
    with pytest.raises(ValueError, match="cannot contain a measurement"):
        _ = State("/", "initial", "not-run", Footprint(1, 0, 1), dom(), None, "unavailable")
