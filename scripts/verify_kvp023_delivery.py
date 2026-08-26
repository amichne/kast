#!/usr/bin/env python3
import json
import pathlib


REPORT_PATH = "runtime/ide-read/build/reports/KVP-023-read-runtime.json"
RECEIPT_PATH = "build/reports/delivery/receipts/KVP-023-COMPLETE.receipt.json"
RECEIPT_ROOT = (
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
    "plugin/project/epoch/model/freshness/singleflight/revalidation/"
)


def required_text(root, relative_path):
    path = root / relative_path
    assert path.is_file(), relative_path
    return path.read_text()


def verify_kvp023_delivery(root, program, requirements, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-023")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-009", "KVP-016", "KVP-022"],
    }
    assert task["authorities"] == ["OPERATION_REGISTRY", "READ_RUNTIME"]
    assert task["publicInterface"] == "IdeReadRuntimeDispatch"
    assert task["provesRequirements"] == [
        "KVP-REQ-009",
        "KVP-REQ-016",
        "KVP-REQ-018",
    ]
    assert task["outputs"] == [{
        "description": "The graph contains exactly four operations and only read effects.",
        "id": "kvp.023.proof",
        "kind": "PROOF_ARTIFACT",
        "path": REPORT_PATH,
    }]
    assert task["red"]["command"] == (
        "./gradlew :runtime:ide-read:verifyReadOnlyGraphNegative"
    )
    assert task["green"]["command"] == (
        "./gradlew :runtime:ide-read:test :runtime:ide-read:verifyReadOnlyGraph"
    )
    assert task["completionReceipt"] == {
        "outputPath": RECEIPT_PATH,
        "receiptId": "KVP-023-COMPLETE",
        "requiredDependencyReceipts": [
            "KVP-009-COMPLETE",
            "KVP-016-COMPLETE",
            "KVP-022-COMPLETE",
        ],
        "requiredGateIds": ["KVP-023-GREEN", "KVP-023-RED"],
    }

    expected_reads = {
        "AGENTS.md",
        "settings.gradle.kts",
        "gradle/libs.versions.toml",
        "runtime/ide-read",
        "runtime/server",
        "runtime/composition",
        "workspace/contract",
        "workspace/intellij-read",
        "workspace/service",
        "symbol/contract",
        "symbol/service",
        "protocol",
        "build-logic/src/main/kotlin/support/architecture",
        "build-logic/src/test/kotlin/support/architecture",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "gradle/architecture",
        "gradle/delivery",
        "docs/AGENTS.md",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp023_delivery.py",
    }
    expected_writes = {
        "AGENTS.md",
        "runtime/ide-read",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md",
        RECEIPT_ROOT.split("project/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("epoch/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("model/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("freshness/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("singleflight/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("revalidation/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT + "AGENTS.md",
        RECEIPT_ROOT + "Kvp022ReceiptRegistration.kt",
        RECEIPT_ROOT + "dispatch",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp023_delivery.py",
    }
    assert expected_reads == set(task["allowedReads"])
    assert expected_writes == set(task["allowedWrites"])
    assert "settings.gradle.kts" not in task["allowedWrites"]
    for forbidden_write_root in (
        "runtime/server",
        "runtime/composition",
        "workspace/",
        "symbol/",
        "protocol/",
    ):
        assert not any(
            path.startswith(forbidden_write_root)
            for path in task["allowedWrites"]
        )

    section = normative_plan.split(
        "### KVP-023: Assemble the physically read-only IDE runtime\n",
        maxsplit=1,
    )[1].split("\n### KVP-024:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-009`, `KVP-016`, `KVP-022`." in section
    expected_read_line = "**Allowed reads.** " + ", ".join(
        f"`{path}`" for path in task["allowedReads"]
    ) + "."
    expected_write_line = "**Allowed writes.** " + ", ".join(
        f"`{path}`" for path in task["allowedWrites"]
    ) + "."
    assert expected_read_line in section
    assert expected_write_line in section
    assert f"**Program fingerprint:** `{program['programFingerprint']}`" in normative_plan

    gates = {
        gate["id"]: gate
        for gate in program["gateGraph"]
        if gate["taskId"] == "KVP-023"
    }
    assert set(gates) == {"KVP-023-COMPLETE-GATE", "KVP-023-GREEN", "KVP-023-RED"}
    direct_receipts = {
        "KVP-009-COMPLETE",
        "KVP-016-COMPLETE",
        "KVP-022-COMPLETE",
    }
    assert set(gates["KVP-023-RED"]["dependsOnReceiptIds"]) == direct_receipts
    assert set(gates["KVP-023-GREEN"]["dependsOnReceiptIds"]) == direct_receipts | {
        "KVP-023-RED-RECEIPT",
    }
    assert set(gates["KVP-023-COMPLETE-GATE"]["dependsOnReceiptIds"]) == (
        direct_receipts | {"KVP-023-GREEN-RECEIPT", "KVP-023-RED-RECEIPT"}
    )

    assert requirements["programFingerprint"] == program["programFingerprint"]
    traced = {
        entry["requirementId"]: entry
        for entry in requirements["entries"]
        if entry["requirementId"] in task["provesRequirements"]
    }
    assert set(traced) == set(task["provesRequirements"])
    for entry in traced.values():
        assert "KVP-023" in entry["implementationTaskIds"]
        assert {"KVP-023-RED", "KVP-023-GREEN"} <= set(entry["enforcementGateIds"])

    build_script = required_text(root, "runtime/ide-read/build.gradle.kts")
    assert 'id("kast.role.ide-read-only")' in build_script
    for forbidden_project in (
        ":runtime:server",
        ":runtime:composition",
        ":workspace:service",
        ":symbol:service",
        ":symbol:intellij",
        ":evidence:sqlite",
        ":change:",
        ":topology:",
    ):
        assert f'project("{forbidden_project}' not in build_script
    settings = required_text(root, "settings.gradle.kts")
    assert '":runtime:ide-read"' in settings
    architecture = json.loads(required_text(
        root,
        "gradle/architecture/kast-architecture-policy.json",
    ))
    ide_read = next(
        module for module in architecture["modules"]
        if module["id"] == "RUNTIME_IDE_READ"
    )
    assert ide_read["projectPath"] == ":runtime:ide-read"
    assert ide_read["role"] == "IDE_READ_ONLY"
    assert ide_read["cost"] == "BOUNDED_READ"
    assert ide_read["allowedEffects"] == ["INTELLIJ_PLATFORM"]
    assert ide_read["allowedProjectDependencies"] == [
        ":kernel",
        ":protocol:contract",
        ":protocol:registry",
        ":protocol:wire",
        ":symbol:contract",
        ":workspace:contract",
        ":workspace:intellij-read",
    ]
    requirement = next(
        entry for entry in requirements["entries"]
        if entry["requirementId"] == "KVP-REQ-009"
    )
    assert requirement["statement"] == (
        "The MVP endpoint supports exactly workspace.inspect, symbol.discover, "
        "symbol.resolve, and symbol.describe."
    )
    endpoint_schema = json.loads(required_text(
        root,
        "gradle/delivery/schema/ide-endpoint.schema.json",
    ))
    assert endpoint_schema["properties"]["capabilities"]["const"] == [
        "workspace.inspect",
        "symbol.discover",
        "symbol.resolve",
        "symbol.describe",
    ]
    bundle = required_text(root, "scripts/verify_bundle.py")
    assert "from verify_kvp023_delivery import verify_kvp023_delivery" in bundle
    assert "verify_kvp023_delivery(root, program, requirements, normative_plan)" in bundle


if __name__ == "__main__":
    repository = pathlib.Path(__file__).resolve().parents[1]
    generated_program = json.loads(required_text(
        repository,
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
    ))
    generated_requirements = json.loads(required_text(
        repository,
        "gradle/delivery/kast-vfs-passive-requirements.json",
    ))
    plan = required_text(
        repository,
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
    )
    verify_kvp023_delivery(repository, generated_program, generated_requirements, plan)
    runtime_root = repository / "runtime/ide-read/src/main/kotlin"
    runtime_source = "\n".join(
        path.read_text()
        for path in sorted(runtime_root.rglob("*.kt"))
    )
    assert "IdeReadRuntimeDispatch" not in runtime_source
    print("KVP-023 delivery authority: valid")
