#!/usr/bin/env python3
import json
import pathlib


def verify_kvp020_delivery(root, program, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-020")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-014", "KVP-019"],
    }
    assert task["authorities"] == ["READ_RUNTIME"]
    assert task["publicInterface"] == "ProjectReadPermit"
    assert task["outputs"] == [{
        "description": (
            "Active and queued bounds hold and cancellation releases authority exactly once."
        ),
        "id": "kvp.020.proof",
        "kind": "PROOF_ARTIFACT",
        "path": "runtime/ide-read/build/reports/KVP-020-single-flight.json",
    }]
    assert task["red"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*SingleFlightNegativeTest"'
    )
    assert task["green"]["command"] == (
        './gradlew :runtime:ide-read:test --tests "*SingleFlightTest"'
    )
    assert task["completionReceipt"] == {
        "outputPath": "build/reports/delivery/receipts/KVP-020-COMPLETE.receipt.json",
        "receiptId": "KVP-020-COMPLETE",
        "requiredDependencyReceipts": ["KVP-014-COMPLETE", "KVP-019-COMPLETE"],
        "requiredGateIds": ["KVP-020-GREEN", "KVP-020-RED"],
    }

    required_reads = {
        "AGENTS.md",
        "settings.gradle.kts",
        "runtime/ide-read",
        "workspace/contract",
        "build-logic/src/main/kotlin/support/architecture",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "gradle/architecture",
        "gradle/delivery",
        "scripts/verify_kvp020_delivery.py",
    }
    required_writes = {
        "AGENTS.md",
        "settings.gradle.kts",
        "runtime/ide-read",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt",
        "gradle/architecture/kast-architecture-policy.json",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
            "project/epoch/model/freshness/singleflight"
        ),
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp020_delivery.py",
    }
    assert required_reads <= set(task["allowedReads"])
    assert required_writes <= set(task["allowedWrites"])

    section = normative_plan.split(
        "### KVP-020: Enforce single-flight project read admission\n",
        maxsplit=1,
    )[1].split("\n### KVP-021:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-014`, `KVP-019`." in section

    settings = (root / "settings.gradle.kts").read_text()
    assert '":runtime:ide-read"' in settings
    module_build = (root / "runtime/ide-read/build.gradle.kts").read_text()
    assert 'id("kast.kotlin-library")' in module_build
    assert 'id("kast.role.ide-read-only")' in module_build
    assert 'implementation(project(":workspace:contract"))' in module_build
    for forbidden in ("api(project(", ":workspace:intellij-read", ":ide-plugin", "kotlinx"):
        assert forbidden not in module_build

    architecture = json.loads(
        (root / "gradle/architecture/kast-architecture-policy.json").read_text()
    )
    runtime = next(
        module for module in architecture["modules"] if module["id"] == "RUNTIME_IDE_READ"
    )
    assert runtime["projectPath"] == ":runtime:ide-read"
    assert runtime["lifecycle"] == "ACTIVE"
    assert runtime["role"] == "IDE_READ_ONLY"
    assert (root / "runtime/ide-read/AGENTS.md").is_file()


if __name__ == "__main__":
    repository_root = pathlib.Path(__file__).resolve().parent.parent
    verify_kvp020_delivery(
        repository_root,
        json.loads(
            (repository_root / "gradle/delivery/kast-vfs-passive-reused-index-program.json")
            .read_text()
        ),
        (
            repository_root / "docs/kast-vfs-passive-reused-index-delivery-program.md"
        ).read_text(),
    )
    print("KVP-020 delivery authority: valid")
