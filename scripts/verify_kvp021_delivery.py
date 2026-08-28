#!/usr/bin/env python3
import json
import pathlib


def required_text(root, relative_path):
    path = root / relative_path
    assert path.is_file(), relative_path
    return path.read_text()


def verify_kvp021_delivery(root, program, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-021")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-019", "KVP-020"],
    }
    assert task["authorities"] == ["READ_RUNTIME"]
    assert task["publicInterface"] == "CancellableProjectReadExecutor"
    assert task["outputs"] == [{
        "description": (
            "Reads cancel for writes, reject dumb/disposed state, and release permits."
        ),
        "id": "kvp.021.proof",
        "kind": "PROOF_ARTIFACT",
        "path": "runtime/ide-read/build/reports/KVP-021-cancellable-read.json",
    }]
    assert task["red"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*CancellableReadNegativeTest"'
    )
    assert task["green"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*CancellableReadTest"'
    )
    assert task["completionReceipt"] == {
        "outputPath": "build/reports/delivery/receipts/KVP-021-COMPLETE.receipt.json",
        "receiptId": "KVP-021-COMPLETE",
        "requiredDependencyReceipts": ["KVP-019-COMPLETE", "KVP-020-COMPLETE"],
        "requiredGateIds": ["KVP-021-GREEN", "KVP-021-RED"],
    }

    expected_reads = {
        "AGENTS.md",
        "gradle/libs.versions.toml",
        "runtime/ide-read",
        "symbol/intellij",
        "workspace/contract",
        "workspace/intellij-read",
        "build-logic/src/main/kotlin/support/architecture",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "gradle/architecture",
        "gradle/delivery",
        "docs/AGENTS.md",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp021_delivery.py",
    }
    expected_writes = {
        "AGENTS.md",
        "runtime/ide-read",
        "symbol/intellij/src/test",
        "workspace/intellij-read/AGENTS.md",
        "workspace/intellij-read/build.gradle.kts",
        (
            "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/ExistingProjectAdmission.kt"
        ),
        (
            "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/AGENTS.md"
        ),
        (
            "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/epoch/AGENTS.md"
        ),
        (
            "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/epoch/execution"
        ),
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/"
            "registration/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/"
            "firewall/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/freshness/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/freshness/Kvp019ReceiptRegistration.kt"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/freshness/singleflight/AGENTS.md"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/freshness/singleflight/Kvp020ReceiptRegistration.kt"
        ),
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/"
            "plugin/project/epoch/model/freshness/singleflight/cancellable"
        ),
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp021_delivery.py",
    }
    assert expected_reads == set(task["allowedReads"])
    assert expected_writes == set(task["allowedWrites"])

    section = normative_plan.split(
        "### KVP-021: Execute cancellable smart reads\n",
        maxsplit=1,
    )[1].split("\n### KVP-022:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-019`, `KVP-020`." in section
    expected_read_line = "**Allowed reads.** " + ", ".join(
        f"`{path}`" for path in task["allowedReads"]
    ) + "."
    expected_write_line = "**Allowed writes.** " + ", ".join(
        f"`{path}`" for path in task["allowedWrites"]
    ) + "."
    assert expected_read_line in section
    assert expected_write_line in section
    assert (
        f"**Program fingerprint:** `{program['programFingerprint']}`"
        in normative_plan
    )

    architecture = json.loads(
        (root / "gradle/architecture/kast-architecture-policy.json").read_text()
    )
    runtime = next(
        module for module in architecture["modules"] if module["id"] == "RUNTIME_IDE_READ"
    )
    workspace = next(
        module
        for module in architecture["modules"]
        if module["id"] == "WORKSPACE_INTELLIJ_READ"
    )
    assert runtime["projectPath"] == ":runtime:ide-read"
    assert workspace["projectPath"] == ":workspace:intellij-read"
    assert ":workspace:intellij-read" in runtime["allowedProjectDependencies"]
    assert ":runtime:ide-read" not in workspace["allowedProjectDependencies"]
    assert runtime["role"] == workspace["role"] == "IDE_READ_ONLY"

if __name__ == "__main__":
    repository = pathlib.Path(__file__).resolve().parents[1]
    generated_program = json.loads(required_text(
        repository,
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
    ))
    plan = required_text(
        repository,
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
    )
    verify_kvp021_delivery(repository, generated_program, plan)
    runtime_root = repository / "runtime/ide-read/src/main/kotlin"
    runtime_source = "\n".join(
        path.read_text()
        for path in sorted(runtime_root.rglob("*.kt"))
    )
    assert "CancellableProjectReadExecutor" not in runtime_source
    print("KVP-021 delivery authority: valid")
