#!/usr/bin/env python3
"""Offline contract checks for the bounded native probe client."""
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location("native_fixture_probe", Path(__file__).with_name("native_fixture_probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeClientTest(unittest.TestCase):
    def response(self, request_id="id", command="OBSERVE"):
        return {"version": 1, "id": request_id, "command": command, "outcome": "COMPLETED", "evidence": {
            "savedSha256": "a" * 64, "documentSha256": "a" * 64,
            "documentState": "SAVED_COMMITTED", "syntax": "CLEAN", "undo": "PRODUCTION_CHANGE",
            "declarations": [{"name": "NativeChangeTarget", "container": "", "kind": "CLASS"}],
        }}

    def test_correlation_and_unknown_fields_reject(self):
        for key, value in (("id", "other"), ("command", "RESTORE_SAVED"), ("version", 2), ("payload", "forbidden")):
            response = self.response()
            response[key] = value
            with self.assertRaises(probe.NativeFixtureProbeError):
                probe.validate_response(response, "id", "OBSERVE")

    def test_bounded_evidence_and_closed_failures(self):
        self.assertEqual(self.response(), probe.validate_response(self.response(), "id", "OBSERVE"))
        for response in (
            {"version": 1, "id": "id", "command": "OBSERVE", "outcome": "REJECTED", "failure": "SOURCE_PAYLOAD"},
            {**self.response(), "evidence": {**self.response()["evidence"], "declarations": [{}] * 65}},
            {**self.response(), "evidence": {**self.response()["evidence"], "documentState": "UNKNOWN"}},
        ):
            with self.assertRaises(probe.NativeFixtureProbeError):
                probe.validate_response(response, "id", "OBSERVE")
        for outcome in ("REJECTED", "EFFECT_UNCERTAIN"):
            result = {"version": 1, "id": "id", "command": "OBSERVE", "outcome": outcome, "failure": "DOCUMENT_STATE_REJECTED"}
            self.assertEqual(result, probe.validate_response(result, "id", "OBSERVE"))

    def test_atomic_private_request_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "workspace").mkdir()
            for part in ("native-probe", "native-probe/requests", "native-probe/responses"):
                (root / part).mkdir(mode=0o700)
            ready = root / "native-probe/ready.json"
            ready.write_text('{"version":1,"outcome":"READY"}')
            ready.chmod(0o600)
            observations = []
            def server():
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    paths = list((root / "native-probe/requests").glob("*.json"))
                    if paths:
                        path = paths[0]
                        body = json.loads(path.read_text())
                        observations.append((path.stat().st_mode & 0o777, body))
                        response = root / "native-probe/responses" / path.name
                        temporary = response.with_suffix(".tmp")
                        temporary.write_text(json.dumps(self.response(body["id"], body["command"])))
                        temporary.chmod(0o600)
                        temporary.replace(response)
                        return
                    time.sleep(0.01)
            worker = threading.Thread(target=server)
            worker.start()
            result = probe.NativeFixtureProbe(root, root / "workspace").request("OBSERVE", "a" * 64, timeout=2)
            worker.join(timeout=2)
            self.assertEqual("COMPLETED", result["outcome"])
            self.assertEqual(0o600, observations[0][0])
            self.assertEqual({"version", "id", "command", "expectedPreimageSha256"}, set(observations[0][1]))

    def test_timeout_and_undo_guard_do_not_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "workspace").mkdir()
            client = probe.NativeFixtureProbe(root, root / "workspace")
            with self.assertRaisesRegex(probe.NativeFixtureProbeError, "IMAGE_GUARD_REQUIRED"):
                client.request("UNDO_PRODUCTION_CHANGE", "a" * 64)
            with self.assertRaisesRegex(probe.NativeFixtureProbeError, "PROBE_TIMEOUT"):
                client.request("OBSERVE", "a" * 64, timeout=0.01)
            self.assertFalse((root / "native-probe").exists())


if __name__ == "__main__":
    unittest.main()
