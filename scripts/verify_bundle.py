#!/usr/bin/env python3
import hashlib
import json
import pathlib
import re
from verify_kvp017_report import verify_kvp017_report

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
        missing = set(schema.get("required", [])) - set(value)
        for name in sorted(missing):
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

root = pathlib.Path(__file__).resolve().parents[1]
program_path = root / "gradle/delivery/kast-vfs-passive-reused-index-program.json"
program = json.loads(program_path.read_text())
assert program["targetHead"] == "78262728313c90bb847e73425dc1a76d704397db"
assert program["requirementFingerprint"] == "de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"
assert len(program["tasks"]) == 43
assert len(program["gateGraph"]) == 129
assert program["terminal"]["taskId"] == "KVP-043"
for task in program["tasks"]:
    expected_receipt_path = (
        f"build/reports/delivery/receipts/{task['id']}-COMPLETE.receipt.json"
    )
    assert task["completionReceipt"]["outputPath"] == expected_receipt_path
    assert all(
        not path.startswith("gradle/delivery/receipts")
        for path in task["allowedReads"] + task["allowedWrites"]
    )
assert not (root / "gradle/delivery/receipts").exists()
assert program["terminal"]["derivedOnly"] is True
assert all("status" not in task for task in program["tasks"])
by_id = {t["id"]: t for t in program["tasks"]}
assert by_id["KVP-002"]["allowedWrites"][0] == (
    "build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt"
)
assert by_id["KVP-003"]["allowedWrites"][0] == (
    "build-logic/src/main/kotlin/support/delivery/model/DeliveryGraph.kt"
)
kvp_007_writes = set(by_id["KVP-007"]["allowedWrites"])
assert {
    "build-logic/src/main/kotlin/support/delivery/model/DeliveryReceipt.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/ReceiptIssuanceBoundary.kt",
    "build-logic/src/test/kotlin/support/delivery/proof/DeliveryReceiptTest.kt",
} <= kvp_007_writes
assert "build-logic/src/test/kotlin/support/delivery/DeliveryReceiptTest.kt" not in kvp_007_writes
assert by_id["KVP-008"]["allowedWrites"][0] == (
    "build-logic/src/main/kotlin/support/delivery/model/DeliveryState.kt"
)
kvp_014 = by_id["KVP-014"]
assert set(kvp_014["dependencyExpression"]["taskIds"]) == {"KVP-009", "KVP-012"}
assert {
    "settings.gradle.kts",
    "workspace/intellij-read",
    "build-logic/src/main/kotlin/kast.architecture.gradle.kts",
    "build-logic/src/main/kotlin/support/architecture/AGENTS.md",
    "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt",
    "gradle/architecture/kast-architecture-policy.json",
} <= set(kvp_014["allowedWrites"])
assert "build-logic/src/main/kotlin/support/delivery/tasks/receipt" not in set(
    kvp_014["allowedWrites"]
)
normative_plan = (
    root / "docs/kast-vfs-passive-reused-index-delivery-program.md"
).read_text()
assert f"**Program fingerprint:** `{program['programFingerprint']}`" in normative_plan
kvp_014_plan = normative_plan.split(
    "### KVP-014: Admit the existing open IntelliJ Project\n",
    maxsplit=1,
)[1].split("\n### KVP-015:", maxsplit=1)[0]
assert (
    "**Allowed reads.** "
    + ", ".join(f"`{entry}`" for entry in kvp_014["allowedReads"])
    + "."
) in kvp_014_plan
assert (
    "**Allowed writes.** "
    + ", ".join(f"`{entry}`" for entry in kvp_014["allowedWrites"])
    + "."
) in kvp_014_plan
assert f"**Review boundary.** {kvp_014['reviewBoundary']}" in kvp_014_plan
kvp_015 = by_id["KVP-015"]
assert kvp_015["dependencyExpression"]["taskIds"] == ["KVP-014"]
assert set(kvp_015["forbiddenWork"]) == {
    "Freezing an epoch contract from undocumented assumptions",
    "VFS refresh",
    "Gradle import or repair",
    "Recursive filesystem or VFS walk",
    "Source-content hashing",
    "Event-triggered semantic jobs",
    "Global Boolean readiness",
    "Production epoch policy",
    "Product behavior assigned to KVP-016 or KVP-017",
}
assert {
    "workspace/intellij-read/build.gradle.kts",
    "workspace/intellij-read/src/test",
    "docs/AGENTS.md",
    "docs/engineering",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/Kvp014ReceiptRegistration.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch",
} <= set(kvp_015["allowedWrites"])
kvp_015_plan = normative_plan.split(
    "### KVP-015: Characterize model and epoch signals\n",
    maxsplit=1,
)[1].split("\n### KVP-016:", maxsplit=1)[0]
assert (
    "**Allowed reads.** "
    + ", ".join(f"`{entry}`" for entry in kvp_015["allowedReads"])
    + "."
) in kvp_015_plan
assert (
    "**Allowed writes.** "
    + ", ".join(f"`{entry}`" for entry in kvp_015["allowedWrites"])
    + "."
) in kvp_015_plan
assert f"**Review boundary.** {kvp_015['reviewBoundary']}" in kvp_015_plan
kvp_016 = by_id["KVP-016"]
assert kvp_016["dependencyExpression"]["taskIds"] == ["KVP-014", "KVP-015"]
assert kvp_016["authorities"] == ["OPEN_PROJECT"]
assert set(kvp_016["forbiddenWork"]) == {
    "Leaking Project, VirtualFile, Module, SearchScope, PSI, Gradle DataNode, callback, or mutable collection",
    "Gradle import, link, prepare, or repair",
    "Blocking read action or synchronous nonblocking execution",
    "Waiting for smart mode",
    "VFS refresh",
    "Recursive filesystem or VFS walk",
    "Source-content hashing",
    "Unbounded model traversal",
    "Operation routing",
    "Production epoch identity or freshness policy",
}
assert "com.jetbrains.intellij.idea:ideaIC:262.9437.185@zip" in set(
    kvp_016["allowedReads"]
)
assert "runtime/ide-read" not in set(kvp_016["allowedWrites"])
assert {
    "workspace/intellij-read",
    "workspace/contract",
    "docs/engineering",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/Kvp014ReceiptRegistration.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/Kvp015ReceiptRegistration.kt",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model",
} <= set(kvp_016["allowedWrites"])
kvp_016_plan = normative_plan.split(
    "### KVP-016: Capture a detached existing-project model\n",
    maxsplit=1,
)[1].split("\n### KVP-017:", maxsplit=1)[0]
assert (
    "**Allowed reads.** "
    + ", ".join(f"`{entry}`" for entry in kvp_016["allowedReads"])
    + "."
) in kvp_016_plan
assert (
    "**Allowed writes.** "
    + ", ".join(f"`{entry}`" for entry in kvp_016["allowedWrites"])
    + "."
) in kvp_016_plan
assert f"**Review boundary.** {kvp_016['reviewBoundary']}" in kvp_016_plan
assert "KVP-017 owns epoch observation and identity" in kvp_016["goal"]
assert "KVP-019 owns freshness policy" in kvp_016["goal"]
kvp_017 = by_id["KVP-017"]
assert kvp_017["dependencyExpression"]["taskIds"] == ["KVP-015"]
assert kvp_017["authorities"] == ["READ_EPOCH"]
assert kvp_017["internalImplementation"] == (
    "Compiler-confined source-bound epoch, typed adapter-private signal state, pure bounded VFS "
    "classification, short cancellable IDEA observation, deterministic report, and exact-head "
    "receipt admission."
)
assert set(kvp_017["forbiddenWork"]) == {
    "Primitive counters crossing modules",
    "Epoch recreated by callers",
    "Treating dumb mode as an epoch value",
    "VFS refresh",
    "Gradle import or repair",
    "Repository or VFS traversal",
    "Source-content hashing",
    "Blocking wait",
    "Per-event semantic job",
    "Semantic work on the EDT",
    "Live Project, listener, counter, callback, or signal-state escape",
}
assert "com.jetbrains.intellij.idea:ideaIC:262.9437.185@zip" in set(
    kvp_017["allowedReads"]
)
assert {
    "workspace/contract",
    "workspace/intellij-read",
    "docs/engineering",
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch",
} <= set(kvp_017["allowedWrites"])
assert (
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/"
    "Kvp014ReceiptRegistration.kt"
) not in set(kvp_017["allowedWrites"])
kvp_017_plan = normative_plan.split(
    "### KVP-017: Define the project read epoch\n",
    maxsplit=1,
)[1].split("\n### KVP-018:", maxsplit=1)[0]
assert (
    "**Allowed reads.** "
    + ", ".join(f"`{entry}`" for entry in kvp_017["allowedReads"])
    + "."
) in kvp_017_plan
assert (
    "**Allowed writes.** "
    + ", ".join(f"`{entry}`" for entry in kvp_017["allowedWrites"])
    + "."
) in kvp_017_plan
assert f"**Review boundary.** {kvp_017['reviewBoundary']}" in kvp_017_plan
verify_kvp017_report(root)
assert "Event-triggered semantic work from a VFS listener" in by_id["KVP-019"]["forbiddenWork"]
epoch_ledger = (root / "docs/engineering/ide-read-epoch-ledger.md").read_text()
for expected_epoch_fact in (
    "WorkspaceModelTopics.CHANGED",
    "ExternalProjectInfo",
    "PsiModificationTracker.modificationCount",
    "VirtualFileManager.VFS_CHANGES",
    "ProjectRootModificationTracker.modificationCount",
    "DumbService.modificationTracker",
    "getModificationCount()` and",
    "getStructureModificationCount()` as constant zero",
    "1,000-event VFS storm",
    "zero semantic jobs",
    "zero semantic work on the EDT",
):
    assert expected_epoch_fact in epoch_ledger
workspace_read_module = next(
    module for module in program["modules"]
    if module["id"] == ":workspace:intellij-read"
)
assert workspace_read_module["role"] == "IDE_READ_ONLY"
assert set(workspace_read_module["dependencies"]) == {
    ":protocol:contract",
    ":workspace:contract",
}
seen = set(); order=[]
while len(order) < len(by_id):
    ready = sorted(i for i,t in by_id.items() if i not in seen and set(t["dependencyExpression"]["taskIds"]) <= seen)
    assert ready, "cycle or missing dependency"
    for i in ready: seen.add(i); order.append(i)
assert order[-1] == "KVP-043"
for t in program["tasks"]:
    assert t["red"]["command"].startswith("./gradlew ")
    assert t["green"]["command"].startswith("./gradlew ")
    assert set(t["completionReceipt"]["requiredGateIds"]) == {t["red"]["gateId"], t["green"]["gateId"]}
for effect in program["effects"]:
    if effect["id"] in {"PROCESS_START","GRADLE_IMPORT","VFS_REFRESH","SOURCE_WRITE","JDBC","TOPOLOGY_BUILD","NETWORK_READ","RUNTIME_ARCHIVE_READ"}:
        assert effect["owners"] == []
base = dict(program); fingerprint = base.pop("programFingerprint")
canonical = json.dumps(base, sort_keys=True, separators=(", ",":"), ensure_ascii=False)
assert hashlib.sha256(canonical.encode()).hexdigest() == fingerprint
requirements_path = root / "gradle/delivery/kast-vfs-passive-requirements.json"
requirements = json.loads(requirements_path.read_text())
assert requirements["programFingerprint"] == fingerprint
validate_document(program_path, root / "gradle/delivery/schema/delivery-program.schema.json")
validate_document(requirements_path, root / "gradle/delivery/schema/requirement-trace.schema.json")
endpoint_schema_path = root / "gradle/delivery/schema/ide-endpoint.schema.json"
endpoint_schema = json.loads(endpoint_schema_path.read_text())
endpoint_fields = [
    "schema",
    "canonicalRoot",
    "hostKind",
    "processId",
    "ideBuild",
    "kotlinPluginBuild",
    "kastPluginVersion",
    "runtimeProtocolIdentity",
    "operationRegistryDigest",
    "wireSchemaDigest",
    "socketPath",
    "framing",
    "runtimeEpoch",
    "capabilities",
]
assert endpoint_schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
assert endpoint_schema["additionalProperties"] is False
assert endpoint_schema["required"] == endpoint_fields
assert set(endpoint_schema["properties"]) == set(endpoint_fields)
endpoint_example = {
    "schema": "kast.ide.endpoint.v2",
    "canonicalRoot": "/Users/kast/project",
    "hostKind": "IDE_PROJECT",
    "processId": 7321,
    "ideBuild": "262.1.2",
    "kotlinPluginBuild": "262.1.2-IJ",
    "kastPluginVersion": "0.1.0",
    "runtimeProtocolIdentity": "kast.ide-hosted.runtime.v1",
    "operationRegistryDigest": f"sha256:{'a' * 64}",
    "wireSchemaDigest": f"sha256:{'b' * 64}",
    "socketPath": "/tmp/kast/project.sock",
    "framing": "length-prefixed-json-v1",
    "runtimeEpoch": 0,
    "capabilities": [
        "workspace.inspect",
        "symbol.discover",
        "symbol.resolve",
        "symbol.describe",
    ],
}
assert not list(schema_errors(endpoint_example, endpoint_schema, endpoint_schema))
endpoint_rejections = []
for field, invalid in (
    ("schema", "kast.runtime.endpoint.v2"),
    ("canonicalRoot", "/Users/kast/../project"),
    ("processId", 0),
    ("socketPath", "/tmp/kast//project.sock"),
    ("runtimeEpoch", -1),
    ("capabilities", list(reversed(endpoint_example["capabilities"]))),
):
    candidate = dict(endpoint_example)
    candidate[field] = invalid
    endpoint_rejections.append(list(schema_errors(candidate, endpoint_schema, endpoint_schema)))
extra_field = dict(endpoint_example)
extra_field["descriptorDigest"] = f"sha256:{'c' * 64}"
endpoint_rejections.append(list(schema_errors(extra_field, endpoint_schema, endpoint_schema)))
missing_field = dict(endpoint_example)
missing_field.pop("runtimeEpoch")
endpoint_rejections.append(list(schema_errors(missing_field, endpoint_schema, endpoint_schema)))
assert all(endpoint_rejections)
receipt_paths = sorted(
    (root / "build/reports/delivery/receipts").glob("*.receipt.json")
)
for receipt_path in receipt_paths:
    validate_document(receipt_path, root / "gradle/delivery/schema/proof-receipt.schema.json")
print(json.dumps({"valid": True, "programFingerprint": fingerprint, "tasks": len(by_id), "gates": len(program["gateGraph"]), "waves": program["waveCount"], "terminal": program["terminal"]["type"]}, indent=2))
print(f"json-schema: valid ({len(receipt_paths)} live receipts)")
