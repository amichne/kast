#!/usr/bin/env python3
import json
import pathlib


REPORT_PATH = "runtime/ide-read/build/reports/KVP-022-epoch-revalidation.json"
RECEIPT_PATH = "build/reports/delivery/receipts/KVP-022-COMPLETE.receipt.json"
RECEIPT_ROOT = (
    "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
    "plugin/project/epoch/model/freshness/singleflight/"
)


def required_text(root, relative_path):
    path = root / relative_path
    assert path.is_file(), relative_path
    return path.read_text()


def verify_kvp022_delivery(root, program, requirements, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-022")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-021"],
    }
    assert task["authorities"] == ["READ_EPOCH", "READ_RUNTIME"]
    assert task["publicInterface"] == "RevalidatedIdeReadResult"
    assert task["provesRequirements"] == [
        "KVP-REQ-010",
        "KVP-REQ-012",
        "KVP-REQ-015",
        "KVP-REQ-027",
    ]
    assert task["outputs"] == [{
        "description": (
            "Stable reads complete and moved reads return closed WorkspaceMoved rejection."
        ),
        "id": "kvp.022.proof",
        "kind": "PROOF_ARTIFACT",
        "path": REPORT_PATH,
    }]
    assert task["red"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*EpochRevalidationNegativeTest"'
    )
    assert task["green"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*EpochRevalidationTest"'
    )
    assert task["completionReceipt"] == {
        "outputPath": RECEIPT_PATH,
        "receiptId": "KVP-022-COMPLETE",
        "requiredDependencyReceipts": ["KVP-021-COMPLETE"],
        "requiredGateIds": ["KVP-022-GREEN", "KVP-022-RED"],
    }

    expected_reads = {
        "AGENTS.md",
        "gradle/libs.versions.toml",
        "runtime/ide-read",
        "workspace/contract",
        "workspace/intellij-read",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "gradle/delivery",
        "docs/AGENTS.md",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp022_delivery.py",
    }
    expected_writes = {
        "AGENTS.md",
        "runtime/ide-read",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        RECEIPT_ROOT.rsplit("firewall/", maxsplit=1)[0] + "registration/AGENTS.md",
        RECEIPT_ROOT.split("plugin/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("project/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("epoch/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("model/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("freshness/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT.split("singleflight/", maxsplit=1)[0] + "AGENTS.md",
        RECEIPT_ROOT + "AGENTS.md",
        RECEIPT_ROOT + "cancellable/Kvp021ReceiptRegistration.kt",
        RECEIPT_ROOT + "revalidation",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp022_delivery.py",
    }
    assert expected_reads == set(task["allowedReads"])
    assert expected_writes == set(task["allowedWrites"])
    assert not any(path.startswith("workspace/") for path in task["allowedWrites"])
    assert RECEIPT_ROOT + "cancellable" not in task["allowedWrites"]

    section = normative_plan.split(
        "### KVP-022: Revalidate the epoch before accepting a result\n",
        maxsplit=1,
    )[1].split("\n### KVP-023:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-021`." in section
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
        if gate["taskId"] == "KVP-022"
    }
    assert set(gates) == {"KVP-022-COMPLETE-GATE", "KVP-022-GREEN", "KVP-022-RED"}
    assert gates["KVP-022-RED"]["dependsOnReceiptIds"] == ["KVP-021-COMPLETE"]
    assert set(gates["KVP-022-GREEN"]["dependsOnReceiptIds"]) == {
        "KVP-021-COMPLETE",
        "KVP-022-RED-RECEIPT",
    }
    assert set(gates["KVP-022-COMPLETE-GATE"]["dependsOnReceiptIds"]) == {
        "KVP-021-COMPLETE",
        "KVP-022-GREEN-RECEIPT",
        "KVP-022-RED-RECEIPT",
    }

    assert requirements["programFingerprint"] == program["programFingerprint"]
    traced = {
        entry["requirementId"]: entry
        for entry in requirements["entries"]
        if entry["requirementId"] in task["provesRequirements"]
    }
    assert set(traced) == set(task["provesRequirements"])
    for entry in traced.values():
        assert "KVP-022" in entry["implementationTaskIds"]
        assert {"KVP-022-RED", "KVP-022-GREEN"} <= set(entry["enforcementGateIds"])

    build_script = required_text(root, "runtime/ide-read/build.gradle.kts")
    assert 'id("kast.role.ide-read-only")' in build_script
    assert 'implementation(project(":workspace:contract"))' in build_script
    assert 'implementation(project(":workspace:intellij-read"))' in build_script
    assert 'project(":runtime:composition")' not in build_script


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
    verify_kvp022_delivery(repository, generated_program, generated_requirements, plan)
    print("KVP-022 delivery authority: valid")
