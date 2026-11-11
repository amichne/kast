"""Independent counts and rejection cases for rendered evidence."""

import hashlib
import json
import os
import select
import signal
import subprocess
import sys
import time
from collections.abc import Iterator
from contextlib import contextmanager
from http.client import HTTPConnection
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
    http_readiness,
    is_dom,
    is_envelope,
    owned_preview,
    receipt,
    schema_scopes_complete,
    stop_preview_group,
    wait_for_preview,
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


def test_known_outcome_unknown_keeps_native_failure_classification() -> None:
    assert is_envelope(
        {
            "success": False,
            "data": None,
            "error": "The command reached the daemon without a response.",
            "code": "outcome_unknown",
        }
    )
    assert not is_envelope({"success": True, "data": {}, "error": None, "code": "outcome_unknown"})


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


def test_repeated_route_cannot_replace_missing_representatives(tmp_path: Path) -> None:
    _ = (tmp_path / "report.json").write_text("{}\n")
    _ = (tmp_path / "capture.png").write_bytes(b"fixture-image")
    snapshot: Snapshot = {
        "type": "DOCUMENTATION_INPUTS",
        "sourceRevision": "a" * 40,
        "inputs": [],
        "inputsDigest": "b" * 64,
    }
    names = (
        "desktop-light",
        "desktop-dark",
        "mobile-light",
        "mobile-dark",
        "browser-zoom-200-dark",
        "keyboard-focus",
    )
    states = [
        State("/", name, "passed", Footprint(2, 0, 2), dom(), "capture.png", None)
        for _ in range(5)
        for name in names
    ]
    result = receipt(snapshot, states, tmp_path, input_match=True, root=tmp_path)
    assert '"status": "passed"' not in json.dumps(result["checks"])


def test_external_preview_keeps_unassessed_provenance_explicit(tmp_path: Path) -> None:
    _ = (tmp_path / "report.json").write_text("{}\n")
    snapshot: Snapshot = {
        "type": "DOCUMENTATION_INPUTS",
        "sourceRevision": "a" * 40,
        "inputs": [],
        "inputsDigest": "b" * 64,
    }
    result = receipt(snapshot, [], tmp_path, input_match=True, root=tmp_path)
    assert '"name": "candidate-preview-provenance"' in json.dumps(result["checks"])


@contextmanager
def http_child(
    *, status: int = 200, content_type: str = "text/html", supervised: bool = False
) -> Iterator[tuple[subprocess.Popen[str], str]]:
    """The child proves only the readiness transport, not Mintlify rendering."""
    program = """
import http.server, sys
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(int(sys.argv[1]))
        self.send_header('Content-Type', sys.argv[2])
        self.end_headers()
        self.wfile.write(b'<!doctype html><html><title>fixture</title></html>')
    def log_message(self, *args):
        pass
server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
print(server.server_port, flush=True)
server.serve_forever()
"""
    command = [sys.executable, "-c", program, str(status), content_type]
    if supervised:
        supervisor = "import subprocess,sys; child=subprocess.Popen(sys.argv[1:]); sys.stdin.read(); child.terminate(); child.wait()"
        command = [sys.executable, "-c", supervisor, *command]
    with subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        start_new_session=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ) as process:
        try:
            assert process.stdout is not None
            port = process.stdout.readline().strip()
            assert port.isdecimal(), "The HTTP fixture did not start."
            yield process, "http://127.0.0.1:" + port
        finally:
            os.killpg(process.pid, signal.SIGTERM)
            _ = process.wait(timeout=5)


def test_silent_http_child_establishes_transport_readiness() -> None:
    with http_child() as (process, origin):
        connection = HTTPConnection("127.0.0.1", int(origin.rsplit(":", 1)[1]), timeout=1)
        try:
            connection.request("GET", "/")
            response = connection.getresponse()
            assert response.status == 200
            assert response.read().startswith(b"<!doctype html>")
        finally:
            connection.close()
        _ = wait_for_preview(origin, process, time.monotonic() + 0.05)


def test_ready_log_cannot_replace_failed_http() -> None:
    with http_child(status=503) as (process, origin):
        with pytest.raises(BrowserFailure, match="PREVIEW_START"):
            _ = wait_for_preview(origin, process, time.monotonic() + 0.05)


def test_exited_preview_cannot_use_a_ready_log() -> None:
    with subprocess.Popen([sys.executable, "-c", "raise SystemExit(7)"], text=True) as process:
        assert process.wait(timeout=5) == 7
        with pytest.raises(BrowserFailure, match="PREVIEW_EXITED:7"):
            _ = wait_for_preview("http://127.0.0.1:1", process, time.monotonic() + 1)


def test_unrelated_live_child_cannot_own_http_readiness() -> None:
    with http_child() as (_, origin):
        with subprocess.Popen(
            [sys.executable, "-c", "import sys;sys.stdin.read()"],
            stdin=subprocess.PIPE,
            text=True,
            start_new_session=True,
        ) as unrelated:
            try:
                with pytest.raises(BrowserFailure, match="PREVIEW_LISTENER_NOT_OWNED"):
                    _ = wait_for_preview(origin, unrelated, time.monotonic() + 1)
            finally:
                assert unrelated.stdin is not None
                unrelated.stdin.close()
                _ = unrelated.wait(timeout=5)


@pytest.mark.parametrize("port", [-1, 0, 65536])
def test_invalid_http_port_is_a_finite_failure(port: int) -> None:
    with pytest.raises(BrowserFailure, match="PREVIEW_ORIGIN_INVALID"):
        _ = http_readiness("http://localhost:" + str(port), 0.01)


def test_listener_from_actual_descendant_is_admitted() -> None:
    with http_child(supervised=True) as (process, origin):
        owner = wait_for_preview(origin, process, time.monotonic() + 1)
        assert process.pid not in owner.listeners
        assert len(owner.listeners) == 1
        owner.examine()


def test_preoccupied_port_is_rejected_before_mintlify_launch() -> None:
    from collections import deque

    with http_child() as (_, origin):
        port = int(origin.rsplit(":", 1)[1])
        with pytest.raises(BrowserFailure, match="PREVIEW_PORT_OCCUPIED"):
            with owned_preview(port, deque()):
                pytest.fail("A different server cannot create a preview witness.")


def test_listener_witness_expires_when_its_process_exits() -> None:
    with http_child() as (process, origin):
        owner = wait_for_preview(origin, process, time.monotonic() + 1)
    with pytest.raises(BrowserFailure, match="PREVIEW_EXITED"):
        owner.examine()


def test_browser_command_rejects_expired_preview_before_effect() -> None:
    with http_child() as (process, origin):
        owner = wait_for_preview(origin, process, time.monotonic() + 1)
    browser = Browser("unused-expired-preview-session")
    browser.preview_owner = owner
    with pytest.raises(BrowserFailure, match="PREVIEW_EXITED"):
        _ = browser.call("version")


def test_cleanup_stops_surviving_descendant_after_root_exit() -> None:
    descendant = "import signal;signal.signal(signal.SIGTERM, signal.SIG_IGN);print('ready',flush=True);signal.pause()"
    launcher = "import subprocess,sys;subprocess.Popen([sys.executable,'-c',sys.argv[1]])"
    with http_child() as (unrelated, unrelated_origin):
        with subprocess.Popen(
            [sys.executable, "-c", launcher, descendant],
            stdout=subprocess.PIPE,
            text=True,
            start_new_session=True,
        ) as process:
            try:
                assert process.stdout is not None
                assert select.select([process.stdout], [], [], 5)[0], "The fixture did not start."
                assert process.stdout.readline() == "ready\n"
                assert process.wait(timeout=5) == 0
                assert not select.select([process.stdout], [], [], 0)[0]
                stop_preview_group(process)
                assert select.select([process.stdout], [], [], 1)[0], (
                    "An owned descendant keeps the pipe open."
                )
                assert process.stdout.read() == ""
                assert unrelated.poll() is None
                assert http_readiness(unrelated_origin, 1).value == "ready"
            finally:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


def test_schema_scope_receipts_reject_stale_missing_or_duplicate_evidence() -> None:
    from dataclasses import replace

    from run import ROOT, ROUTES
    from schema_scope import SchemaScope, expected_scope

    source = (ROOT / "docs/public/reference/callables.openapi.json").read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    snapshot: Snapshot = {
        "type": "DOCUMENTATION_INPUTS",
        "sourceRevision": "a" * 40,
        "inputs": [{"path": "docs/public/reference/callables.openapi.json", "sha256": digest}],
        "inputsDigest": "b" * 64,
    }
    states: list[State] = []
    for route in ROUTES[4:]:
        nodes = expected_scope(source, route.rsplit("/", 1)[1].replace("-", "_"))
        scope = SchemaScope(2, digest, nodes, nodes)
        states.append(
            State(
                route,
                "schema-depth-2-all-variants",
                "passed",
                Footprint(1, 0, 1),
                dom(),
                None,
                None,
                scope,
            )
        )
    assert schema_scopes_complete(snapshot, states)
    assert not schema_scopes_complete(snapshot, states[:-1])
    assert not schema_scopes_complete(snapshot, states + [states[0]])
    original = states[0].schemaScope
    assert original is not None
    states[0] = replace(states[0], schemaScope=replace(original, sourceSha256="0" * 64))
    assert not schema_scopes_complete(snapshot, states)


def test_reader_link_rejects_wrong_route_before_click() -> None:
    from run import follow_reader_link

    class BrowserProbe:
        def evaluate(self, _source: str) -> object:
            return "http://localhost:3339/wrong"

        def call(self, *arguments: str) -> dict[str, object]:
            raise AssertionError(f"must reject before browser action: {arguments}")

    with pytest.raises(BrowserFailure, match="READER_LINK_TARGET"):
        follow_reader_link(BrowserProbe(), "http://localhost:3339", "next", "/start")
