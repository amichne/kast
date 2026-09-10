import copy
import tempfile
import unittest
from pathlib import Path
import controller as c

RID = "00000000-0000-4000-8000-000000000001"
DIGEST = "a" * 64


def request():
    return dict(type="PREFLIGHT", version=1, requestId=RID, artifactSha256=DIGEST,
                expectedBuild="262.10315.125", outputDirectory="/private/tmp/observation")


def receipt():
    return dict(type="PREFLIGHT", version=1, requestId=RID, artifactSha256=DIGEST,
                host=dict(type="HOST", pid=123, startedAt="2026-09-09T12:00:00Z",
                          build="262.10315.125", jbr="25.0.4+1-b508.27"),
                engine=dict(type="ENGINE", factory="org.jetbrains.kotlin.jsr223.Jsr223KotlincScriptEngineFactory",
                            name="kotlin", version="2.4.10", pluginVersion="262.10315.125-IJ"),
                projects=[], registrations=0, capability="PREFLIGHT_ONLY")


class ReceiptAdmissionTest(unittest.TestCase):
    def test_foreign_receipt_is_rejected_not_admitted(self):
        value = receipt(); value["requestId"] = "00000000-0000-4000-8000-000000000002"
        self.assertEqual(c.Rejected(c.Reason.RECEIPT_REJECTED), c.verify_receipt(request(), value))

    def test_complete_preflight_is_observed_without_claiming_attachment(self):
        result = c.verify_receipt(request(), receipt())
        self.assertIsInstance(result, c.Observed)
        self.assertEqual("PREFLIGHT_ONLY", result.receipt["capability"])

    def test_loaded_project_with_152_content_roots_fits_preflight_budget(self):
        value = dict(type="PROJECT", locationHash="1234", basePath="/workspace",
                     contentRoots=[f"/workspace/module-{i:03}" for i in range(152)])
        self.assertTrue(c.valid_project(value))

    def test_missing_receipt_is_an_unmet_precondition(self):
        self.assertEqual(c.UnmetPrecondition(c.Reason.RECEIPT_UNAVAILABLE), c.verify_receipt(request(), None))

    def test_weakened_or_foreign_proof_is_rejected(self):
        for key, value in (("version", True), ("version", 2), ("artifactSha256", "b" * 64),
                           ("registrations", 1), ("capability", "ATTACH"), ("extra", "field")):
            with self.subTest(key=key):
                changed = receipt(); changed[key] = value
                self.assertIsInstance(c.verify_receipt(request(), changed), c.Rejected)
        for key in receipt():
            changed = receipt(); del changed[key]
            self.assertIsInstance(c.verify_receipt(request(), changed), c.Rejected)

    def test_wrong_host_and_unqualified_engine_are_rejected(self):
        for path, value in (("build", "262.1"), ("pid", 0), ("startedAt", "unknown")):
            changed = receipt(); changed["host"][path] = value
            self.assertIsInstance(c.verify_receipt(request(), changed), c.Rejected)
        changed = receipt(); changed["engine"]["factory"] = "other.Engine"
        self.assertIsInstance(c.verify_receipt(request(), changed), c.Rejected)

    def test_correlated_host_rejection_remains_a_rejection(self):
        value = {k: receipt()[k] for k in ("version", "requestId", "artifactSha256")}
        value.update(type="REJECTED", reason="TARGET_UNAVAILABLE", stage="PROJECTS", ownership="NOT_ACQUIRED")
        self.assertEqual(c.Rejected(c.Reason.TARGET_UNAVAILABLE), c.verify_receipt(request(), value))

class BoundaryTest(unittest.TestCase):
    def test_reader_rejects_duplicates_truncation_nonfinite_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'record'
            for raw in (b'{"type":"A","type":"B"}', b'{', b'{"value":NaN}', b'\xff', b' ' * 65):
                path.write_bytes(raw)
                with self.assertRaises((ValueError, UnicodeError)):
                    c.read_json(path, 64)
            path.unlink(); path.symlink_to(Path(tmp) / 'absent')
            with self.assertRaises(OSError):
                c.read_json(path, 64)

    def test_unsupported_and_weakened_event_is_not_admitted(self):
        valid = dict(type='VFS', paths=['/fixture/src/A.java'], batchSize=150, inspected=128,
                     limitations=['PATH_LIMIT', 'BATCH_LIMIT'])
        self.assertTrue(c.valid_event(valid))
        for key, bad in [('type','FUTURE'),('paths', ['/x'] * 33),('inspected',150),('limitations', []),('batchSize',True)]:
            with self.subTest(key=key):
                changed = dict(valid); changed[key] = bad
                self.assertFalse(c.valid_event(changed))
        self.assertFalse(c.valid_event(dict(type='INDEXING', activity='INDEX_STARTED', activityId=1, cancellation='NOT_CANCELLED')))

    def test_foreign_owner_does_not_change_local_request_or_receipt(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp).resolve() / 'request'
            self.assertIsInstance(c.prepare(destination), c.Prepared)
            original = (destination / 'request.json').read_bytes()
            self.assertEqual(c.Rejected(c.Reason.OUTPUT_REJECTED), c.prepare(destination))
            self.assertEqual(original, (destination / 'request.json').read_bytes())
            self.assertEqual(c.UnmetPrecondition(c.Reason.RECEIPT_UNAVAILABLE), c.verify_directory(destination))

    def test_journal_keeps_valid_prefix_and_reports_truncated_tail(self):
        import json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            record = dict(type='OBSERVATION', version=1, sessionId=RID, sequence=1, event=dict(type='DUMB', transition='ENTERED'))
            (directory / 'journal-0.jsonl').write_bytes(json.dumps(record).encode() + b'\n{"type":')
            status = c.Observed(dict(type='SESSION', state='DETACHED', lastSequence=2, coverage=dict(reasons=['INITIAL_GAP'])))
            attached = c.Observed(dict(sessionId=RID, sessionDirectory=str(directory)))
            with patch.object(c, 'read_status', return_value=status), patch.object(c, 'verify_directory', return_value=attached):
                result = c.inspect_journal(directory)
            self.assertEqual(1, result.receipt['retainedRecords'])
            self.assertEqual(['INITIAL_GAP','SEQUENCE_GAP','TRUNCATED_TAIL'], result.receipt['limitations'])
            self.assertEqual('NONE', result.receipt['semanticAuthority'])


    def test_rejection_cannot_erase_unconfirmed_ownership(self):
        value = dict(type="REJECTED", version=1, requestId=RID, artifactSha256=DIGEST,
                     reason="RETIREMENT_UNCONFIRMED", stage="ATTACH", ownership="RETIREMENT_UNCONFIRMED")
        self.assertEqual(c.Rejected(c.Reason.RETIREMENT_UNCONFIRMED), c.verify_receipt(request(), value))
        for key, invalid in (("ownership", "NOT_ACQUIRED"), ("stage", "EVALUATION"), ("reason", "EXECUTION_UNCONFIRMED")):
            changed = dict(value); changed[key] = invalid
            self.assertEqual(c.Rejected(c.Reason.RECEIPT_REJECTED), c.verify_receipt(request(), changed))

    def test_preflight_does_not_admit_session_status(self):
        from unittest.mock import patch
        with patch.object(c, "verify_directory", return_value=c.Observed(receipt())):
            self.assertEqual(c.UnmetPrecondition(c.Reason.TARGET_UNAVAILABLE), c.read_status(Path('/unused')))

    def test_terminal_status_requires_retired_callbacks_and_requested_stop(self):
        from unittest.mock import patch
        project = dict(type="PROJECT", locationHash="1", basePath="/fixture", contentRoots=["/fixture"])
        attached = dict(type="ATTACHED", sessionId=RID, host=receipt()["host"], project=project, sessionDirectory="/unused")
        terminal = dict(type="SESSION", version=1, sessionId=RID, host=attached["host"], project=project,
                        state="DETACHED", stopSignal=dict(type="REQUESTED", cause="REQUESTED"),
                        coverage=dict(type="COVERAGE", reasons=["INITIAL_GAP"], dropped=0), lastSequence=0, callbackCount=0)
        with patch.object(c, "verify_directory", return_value=c.Observed(attached)), patch.object(c, "retirement_evidence", return_value=c.Retirement.CONFIRMED):
            with patch.object(c, "read_json", return_value=terminal):
                self.assertIsInstance(c.read_status(Path('/unused')), c.Observed)
            for key, invalid in (("callbackCount",1), ("callbackCount",True), ("stopSignal",dict(type="ACTIVE")), ("state","FUTURE"),
                                 ("coverage",dict(type="COVERAGE",reasons=[],dropped=0))):
                changed = dict(terminal); changed[key] = invalid
                with patch.object(c, "read_json", return_value=changed):
                    self.assertEqual(c.Rejected(c.Reason.RECEIPT_REJECTED), c.read_status(Path('/unused')))


    def test_terminal_publication_does_not_erase_unreleased_admission(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            space = Path(tmp)
            location = space / 'sessions' / RID; location.mkdir(parents=True)
            attached = dict(sessionId=RID, sessionDirectory=str(location))
            self.assertIs(c.retirement_evidence(attached), c.Retirement.UNCONFIRMED)
            (location / 'retired.json').write_text(json.dumps(dict(type='RETIRED', version=1, sessionId=RID)))
            admission = space / 'admission'; admission.mkdir()
            self.assertIs(c.retirement_evidence(attached), c.Retirement.UNCONFIRMED)
            owner = admission / 'owner.json'; owner.write_text(json.dumps(dict(type='OWNER', sessionId=RID)))
            self.assertIs(c.retirement_evidence(attached), c.Retirement.UNCONFIRMED)
            owner.write_text(json.dumps(dict(type='OWNER', sessionId='00000000-0000-4000-8000-000000000002')))
            self.assertIs(c.retirement_evidence(attached), c.Retirement.CONFIRMED)
            owner.unlink(); admission.rmdir()
            self.assertIs(c.retirement_evidence(attached), c.Retirement.CONFIRMED)


    def test_correlated_engine_failure_remains_an_unmet_precondition(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp).resolve() / 'request'
            c.prepare(destination)
            request = c.read_json(destination / 'request.json', c.MAX_REQUEST)
            evaluation = dict(type='EVALUATION', stage='ENGINE', requestId=request['requestId'],
                              artifactSha256=request['artifactSha256'], outcome='RESOURCE_EXHAUSTED')
            path = destination / 'evaluation.json'; path.write_text(json.dumps(evaluation))
            self.assertEqual(c.UnmetPrecondition(c.Reason.RESOURCE_EXHAUSTED), c.verify_directory(destination))
            evaluation['requestId'] = RID; path.write_text(json.dumps(evaluation))
            self.assertEqual(c.UnmetPrecondition(c.Reason.RECEIPT_UNAVAILABLE), c.verify_directory(destination))


if __name__ == "__main__":
    unittest.main()
