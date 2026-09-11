import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest

from kast_ide import Answer, Failure, Rejected, exchange, strict_json, valid_schema


class HostedClientTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kc-", dir=Path("/tmp").resolve())
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.root = self.home
        key = hashlib.sha256(str(self.root).encode()).hexdigest()[:32]
        self.directory = self.home / ".kast/ide-hosted" / key

    def reply(self, raw, request=None):
        self.directory.mkdir(parents=True, mode=0o700)
        path = self.directory / "host.sock"
        metadata = dict(type="KAST_IDE_ENDPOINT", protocol=3, host="00000000-0000-0000-0000-000000000001", querySchema="kast.query.run.v2", root=str(self.root), socket=str(path), hostPid=123,
                        operations=["DESCRIBE", "CLASS_LOOKUP", "DIRECT_SUPERTYPE", "QUERY_RUN", "SYMBOL_DISCOVER", "SYMBOL_INSPECT", "SOURCE_READ", "RELATION_READ", "TRAVERSAL_RUN", "DIAGNOSTIC_CHECK", "CHANGE_PLAN", "CHANGE_APPROVAL_PREPARE", "CHANGE_APPLY", "CHANGE_RECOVER"])
        (self.directory / "endpoint.json").write_text(json.dumps(metadata))
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
            server.bind(str(path))
            server.listen(1)
            server.settimeout(2)
            def serve():
                with server.accept()[0] as client:
                    # Consume the complete bounded request before delivering the test response.
                    header = client.makefile("rb")
                    header.read(struct.unpack(">I", header.read(4))[0])
                    client.sendall(struct.pack(">I", len(raw)) + raw)
            worker = threading.Thread(target=serve)
            worker.start()
            try:
                return exchange(self.root, request or dict(type="DESCRIBE"), self.home)
            finally:
                worker.join(timeout=3)
                self.assertFalse(worker.is_alive())

    def test_missing_host_fails_without_creating_endpoint_state(self):
        self.assertEqual(Rejected(Failure.HOST_UNAVAILABLE), exchange(self.root, dict(type="DESCRIBE"), self.home))
        self.assertFalse(self.directory.exists())

    def test_matching_host_and_empty_index_result_are_accepted(self):
        result = dict(schemaVersion=1, outcome="published", publication="request_local_same_source_epoch",
                      content="saved_committed_ide_vfs", scope="cached_gradle_source_folders", kind="classes",
                      stage="RESULT_DETACHED", workspaceRoot=str(self.root), host=dict(ideBuild="test", kotlinBuild="test"),
                      name="Absent", indexAuthority="existing_ide_kotlin_stub_index", declarations=[])
        self.assertEqual(Answer(result), self.reply(json.dumps(result).encode(), dict(type="CLASS_LOOKUP", name="Absent")))

    def test_host_identity_must_match_descriptor(self):
        result = dict(type="KAST_IDE_HOST", protocol=3, host="00000000-0000-0000-0000-000000000001", querySchema="kast.query.run.v2", root=str(self.root), hostPid=124,
                      operations=["DESCRIBE", "CLASS_LOOKUP", "DIRECT_SUPERTYPE", "QUERY_RUN", "SYMBOL_DISCOVER", "SYMBOL_INSPECT", "SOURCE_READ", "RELATION_READ", "TRAVERSAL_RUN", "DIAGNOSTIC_CHECK", "CHANGE_PLAN", "CHANGE_APPROVAL_PREPARE", "CHANGE_APPLY", "CHANGE_RECOVER"], indexAuthority="existing_ide_kotlin_stub_index")
        self.assertEqual(Rejected(Failure.RESPONSE_REJECTED), self.reply(json.dumps(result).encode()))

    def test_unknown_failure_is_rejected(self):
        self.assertEqual(Rejected(Failure.RESPONSE_REJECTED), self.reply(b'{"type":"HOST_REJECTED","failure":"UNKNOWN"}'))

    def test_known_failure_remains_host_data(self):
        result = dict(type="HOST_REJECTED", failure="WRONG_ROOT")
        self.assertEqual(Answer(result), self.reply(json.dumps(result).encode()))

    def supertype_result(self):
        def declaration(name):
            canonical = "".join(f"{len(field.encode())}:{field}" for field in ("canonical-signature-v1", "class-like", name))
            return dict(file=str(self.root / "Classes.kt"), compilerIdentity="canonical-signature-sha256-v1|" + hashlib.sha256(canonical.encode()).hexdigest(),
                        signature=dict(kind="class_like", qualifiedIdentity=name), canonicalSignature=canonical,
                        documentStamp=1, vfsStamp=1, module="fixture", gradleBuildRoot=str(self.root), gradleProject=":fixture",
                        sourceRoot="src", sourceKind="PRODUCTION", provenanceAuthority="cached_source_folder_flag")
        return dict(schemaVersion=1, outcome="published", publication="request_local_same_source_epoch",
                    content="saved_committed_ide_vfs", scope="cached_gradle_source_folders", kind="inheritors",
                    stage="RESULT_DETACHED", workspaceRoot=str(self.root), host=dict(ideBuild="test", kotlinBuild="test"),
                    supertype=declaration("example.Parent"), inheritor=declaration("example.Child"))

    def test_indexed_supertype_matches_the_requested_compiler_identity(self):
        result = self.supertype_result()
        self.assertEqual(Answer(result), self.reply(json.dumps(result).encode(), dict(type="DIRECT_SUPERTYPE", qualifiedName="example.Child")))

    def test_indexed_supertype_rejects_another_compiler_identity(self):
        result = self.supertype_result()
        self.assertEqual(Rejected(Failure.RESPONSE_REJECTED), self.reply(json.dumps(result).encode(), dict(type="DIRECT_SUPERTYPE", qualifiedName="other.Child")))

    def test_duplicate_fields_and_non_json_numbers_are_rejected(self):
        for raw in ('{"type":"HOST_REJECTED","type":"HOST_REJECTED","failure":"WRONG_ROOT"}', '{"n":NaN}'):
            with self.assertRaises(ValueError):
                strict_json(raw)

    def test_schema_closes_host_capabilities(self):
        document = dict(type="KAST_IDE_HOST", protocol=3, host="00000000-0000-0000-0000-000000000001", querySchema="kast.query.run.v2", root=str(self.root), hostPid=os.getpid(),
                        operations=["DESCRIBE", "IMPORT"], indexAuthority="existing_ide_kotlin_stub_index")
        self.assertFalse(valid_schema(document, "hosted-endpoint.schema.json"))


if __name__ == "__main__":
    unittest.main()
