"""Dependency-free JSON-schema, historical-receipt, and KVP-017 boundaries."""
import hashlib
import json
import re
import subprocess
from datetime import datetime

LEGACY_PROGRAM_FINGERPRINT = "f564dea6a123a43320ae96933f370f446eb738b32de16fc53d2c94685ab89d44"
LEGACY_PROGRAM_FINGERPRINTS = {
    "001f2707e8b156cd504b5bab03a44ed88a904499486bd1db303e26502cd9b99d",
    "31fcef0d003e673781fe38c8aa52e9ad3c4aadec4a888764bbe17645abaf8888",
    LEGACY_PROGRAM_FINGERPRINT,
}
LEGACY_REQUIREMENT_FINGERPRINT = "de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"
LEGACY_BASE_REVISION = "78262728313c90bb847e73425dc1a76d704397db"
LEGACY_EXACT_HEAD = "4a323a97d93964dd6b49c27ce77c45bf651b29c4"
LEGACY_OBSERVED_HEADS = {
    "20f065332a349cc71ff88720781888b1549483e6",
    "22a314af3570687877e1627c5175287a5cf3b618",
    LEGACY_EXACT_HEAD,
}
LEGACY_RECEIPT_COUNT = 69
LEGACY_RECEIPT_MANIFEST_DIGEST = "51025d8f605270455155c7fdc9a2cbe48738884a7b2e236875d01566a4b95789"
LEGACY_SUPERSEDED_DIGESTS = {
    "KVP-001-COMPLETE": {"9d450171c1a97569f1430cba6ddf7ba0360c534465f13966e5d183616bd48f7c"},
    "KVP-002-COMPLETE": {"3c61e5a70bc27ee6475a99169cf6aab949bd6f8fd274ae6365e3d53644120e61"},
    "KVP-005-COMPLETE": {"5a8b49256e78a8535aefea9d52fda02d355fe8d7a7508b8ad18cdcc0204dcfde"},
    "KVP-006-COMPLETE": {"40e2d1882f35063357b270407ac51ba57b207cebbae6845c3f51102bab810241"},
    "KVP-009-COMPLETE": {"27fa87df8775c61a1c6c72dec8b2ed0147af12b8e6e531a02fb90dcf564bfe29"},
    "KVP-012-COMPLETE": {"c92ea098edb411f1ffc181a9f79067f400d5132558bb5583f96dfc97907a8729"},
    "KVP-014-COMPLETE": {"33184300ecf4cf8875038f23db937d511726a129b7024468ef99f63cd6aa6dfb"},
    "KVP-015-COMPLETE": {"bcba54183e7805e15da004ed3bf9a273d518a21b2ad725e0e98be1a4a0ba35a0"},
    "KVP-016-COMPLETE": {"d6820f2f68807953ad49d3340ef7c0c7d042e24572d618617ddbc6376b74cb61"},
    "KVP-017-RED-RECEIPT": {"4c35e211f36af1be8f302b507479f24937d73cbc4dedd8442aed378f5f1655a5"},
}
LEGACY_RECEIPT_FIELDS = {
    "schemaVersion", "receiptId", "baseRevision", "exactHead", "programFingerprint",
    "requirementFingerprint", "taskId", "gateId", "dependencyReceiptDigests",
    "declaredInputDigest", "commandDigest", "observedProofValues", "artifactDigests",
    "recordedAtUtc", "receiptDigest",
}

def schema_errors(value, schema, root_schema, path="$"):
    if "$ref" in schema:
        reference = schema["$ref"]
        assert reference.startswith("#/")
        resolved = root_schema
        for part in reference[2:].split("/"):
            resolved = resolved[part.replace("~1", "/").replace("~0", "~")]
        yield from schema_errors(value, resolved, root_schema, path)
        return
    declared_types = schema.get("type")
    if declared_types is not None:
        if isinstance(declared_types, str):
            declared_types = [declared_types]
        predicates = {
            "array": lambda candidate: isinstance(candidate, list),
            "boolean": lambda candidate: isinstance(candidate, bool),
            "integer": lambda candidate: isinstance(candidate, int) and not isinstance(candidate, bool),
            "null": lambda candidate: candidate is None,
            "object": lambda candidate: isinstance(candidate, dict),
            "string": lambda candidate: isinstance(candidate, str),
        }
        if not any(predicates[kind](value) for kind in declared_types):
            yield f"{path}: expected type {declared_types}, got {type(value).__name__}"
            return
    if "const" in schema and value != schema["const"]:
        yield f"{path}: expected constant {schema['const']!r}, got {value!r}"
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            yield f"{path}: string is shorter than minLength"
        maximum_length = schema.get("maxLength")
        if maximum_length is not None and len(value) > maximum_length:
            yield f"{path}: string is longer than maxLength"
        pattern = schema.get("pattern")
        if pattern is not None and re.search(pattern, value) is None:
            yield f"{path}: string does not match {pattern!r}"
    if isinstance(value, int) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            yield f"{path}: integer is less than minimum {schema['minimum']}"
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            yield f"{path}: array has fewer than minItems"
        if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            yield f"{path}: array items are not unique"
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(value):
                yield from schema_errors(item, item_schema, root_schema, f"{path}[{index}]")
    if isinstance(value, dict):
        if len(value) < schema.get("minProperties", 0):
            yield f"{path}: object has fewer than minProperties"
        properties = schema.get("properties", {})
        for name in sorted(set(schema.get("required", [])) - set(value)):
            yield f"{path}: missing required property {name!r}"
        additional = schema.get("additionalProperties", True)
        for name, item in value.items():
            if name in properties:
                yield from schema_errors(item, properties[name], root_schema, f"{path}.{name}")
            elif additional is False:
                yield f"{path}: unexpected property {name!r}"
            elif isinstance(additional, dict):
                yield from schema_errors(item, additional, root_schema, f"{path}.{name}")

def validate_document(document_path, schema_path):
    document = json.loads(document_path.read_text())
    schema = json.loads(schema_path.read_text())
    errors = list(schema_errors(document, schema, schema))
    assert not errors, "\n".join(errors)

def admit_legacy_receipt_prefix(receipts):
    by_receipt_id = {}
    for receipt in receipts:
        assert set(receipt) == LEGACY_RECEIPT_FIELDS
        assert receipt["schemaVersion"] == 1
        assert re.fullmatch(r"KVP-\d{3}", receipt["taskId"])
        assert 1 <= int(receipt["taskId"].removeprefix("KVP-")) <= 24
        assert re.fullmatch(
            rf"{receipt['taskId']}-(RED-RECEIPT|GREEN-RECEIPT|COMPLETE)",
            receipt["receiptId"],
        )
        assert re.fullmatch(
            rf"{receipt['taskId']}-(RED|GREEN|COMPLETE-GATE)", receipt["gateId"],
        )
        assert receipt["baseRevision"] == LEGACY_BASE_REVISION
        assert receipt["exactHead"] in LEGACY_OBSERVED_HEADS
        assert receipt["programFingerprint"] in LEGACY_PROGRAM_FINGERPRINTS
        assert receipt["requirementFingerprint"] == LEGACY_REQUIREMENT_FINGERPRINT
        for field in ("declaredInputDigest", "commandDigest", "receiptDigest"):
            assert re.fullmatch(r"[0-9a-f]{64}", receipt[field])
        assert receipt["observedProofValues"]
        for mapping in (receipt["dependencyReceiptDigests"], receipt["artifactDigests"]):
            assert all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in mapping.values())
        assert all(isinstance(value, str) for value in receipt["observedProofValues"].values())
        assert all(
            path and not path.startswith("/") and "\\" not in path and
            all(part not in {"", ".", ".."} for part in path.split("/"))
            for path in receipt["artifactDigests"]
        )
        assert datetime.fromisoformat(receipt["recordedAtUtc"].replace("Z", "+00:00"))
        unsigned = dict(receipt)
        expected_digest = unsigned.pop("receiptDigest")
        canonical = json.dumps(unsigned, sort_keys=True, separators=(", ", ":"), ensure_ascii=False)
        assert hashlib.sha256(canonical.encode()).hexdigest() == expected_digest
        assert receipt["receiptId"] not in by_receipt_id
        by_receipt_id[receipt["receiptId"]] = receipt
    manifest = "\n".join(
        f"{receipt_id}:{receipt['receiptDigest']}"
        for receipt_id, receipt in sorted(by_receipt_id.items())
    ) + "\n"
    assert len(by_receipt_id) == LEGACY_RECEIPT_COUNT
    assert hashlib.sha256(manifest.encode()).hexdigest() == LEGACY_RECEIPT_MANIFEST_DIGEST
    for receipt in receipts:
        for dependency_id, dependency_digest in receipt["dependencyReceiptDigests"].items():
            current_digest = by_receipt_id.get(dependency_id, {}).get("receiptDigest")
            assert dependency_digest == current_digest or dependency_digest in (
                LEGACY_SUPERSEDED_DIGESTS.get(dependency_id, set())
            )
    return len(receipts)

def verify_kvp024_completion_receipt(root, receipt, program, retirement_refined):
    assert receipt["receiptId"] == "KVP-024-COMPLETE"
    assert receipt["taskId"] == "KVP-024"
    assert receipt["gateId"] == "KVP-024-COMPLETE-GATE"
    expected_program = LEGACY_PROGRAM_FINGERPRINT if retirement_refined else program["programFingerprint"]
    assert receipt["programFingerprint"] == expected_program
    assert receipt["requirementFingerprint"] == LEGACY_REQUIREMENT_FINGERPRINT
    assert set(receipt["dependencyReceiptDigests"]) == {
        "KVP-013-COMPLETE", "KVP-023-COMPLETE", "KVP-024-RED-RECEIPT",
        "KVP-024-GREEN-RECEIPT",
    }
    expected_head = LEGACY_EXACT_HEAD if retirement_refined else subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True,
    ).strip()
    assert receipt["exactHead"] == expected_head

def verify_kvp017_report(root):
    page = (root / "docs/engineering/ide-project-read-epoch.md").read_text()
    report = json.loads((
        root / "workspace/intellij-read/src/test/resources/KVP-017-read-epoch.expected.json"
    ).read_text())
    assert report["ideBuild"] == "262.9437.185"
    assert report["signalComponents"] == [
        "PROJECT_MODEL", "PSI", "ROOT_FILTERED_VFS", "ROOT_MODEL", "DUMB_MODE_TRACKER",
    ]
    assert report["comparisonRelations"] == ["SAME", "MOVED", "INCOMPARABLE"]
    assert len(report["observationFailures"]) == 27
    assert len(report["cases"]) == 13
    assert all(case["sampleCount"] == 2 for case in report["cases"])
    assert all(case["expectedRelation"] == case["observedRelation"] for case in report["cases"])
    assert {
        "maxVfsEventsPerBatch": 4_096, "maxVfsPathCharacters": 4_096,
        "maxVfsPathUtf8Bytes": 8_192, "maxCachedGradleModels": 16,
    }.items() <= report.items()
    zero_fields = (
        "primitiveCounterEscapeCount", "callerEpochReconstructionCount",
        "repeatedValidationCount", "dumbModeEpochValueCount", "vfsRefreshCount",
        "gradleImportCount", "gradleRepairCount", "repositoryWalkCount",
        "vfsTraversalCount", "sourceHashCount", "semanticJobCount",
        "edtSemanticWorkCount", "blockingWaitCount", "liveObjectEscapeCount",
    )
    assert all(report[field] == 0 for field in zero_fields)
    page_facts = (
        "262.9437.185", "PROJECT_MODEL", "PSI", "ROOT_FILTERED_VFS", "ROOT_MODEL",
        "DUMB_MODE_TRACKER", "`SAME`", "`MOVED`", "`INCOMPARABLE`", "4,096 events",
        "4,096 characters", "8,192 UTF-8 bytes", "16 cached Gradle models",
        "1,000-event VFS storm", "VFS traversals",
    )
    assert all(fact in page for fact in page_facts)
