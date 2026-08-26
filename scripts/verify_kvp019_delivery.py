#!/usr/bin/env python3
import pathlib


def verify_kvp019_delivery(root, program, normative_plan):
    task = next(item for item in program["tasks"] if item["id"] == "KVP-019")
    assert task["dependencyExpression"] == {
        "kind": "allOf",
        "taskIds": ["KVP-017", "KVP-018"],
    }
    assert task["authorities"] == ["READ_EPOCH"]
    assert task["publicInterface"] == "VfsPassiveReadCapability"
    assert task["internalImplementation"] == (
        "Admission from exact Project plus epoch with closed dumb, disposed, unavailable, "
        "and moved failures."
    )
    assert "Busy" not in task["internalImplementation"]
    assert task["outputs"] == [{
        "description": (
            "Admission reads only the IDE snapshot and returns a typed capability or closed "
            "rejection."
        ),
        "id": "kvp.019.proof",
        "kind": "PROOF_ARTIFACT",
        "path": "workspace/intellij-read/build/reports/KVP-019-vfs-passive.json",
    }]
    assert task["red"]["command"] == (
        './gradlew :workspace:intellij-read:test --tests "*VfsPassiveAdmissionNegativeTest"'
    )
    assert task["green"]["command"] == (
        './gradlew :workspace:intellij-read:test --tests "*VfsPassiveAdmissionTest"'
    )
    assert task["completionReceipt"] == {
        "outputPath": "build/reports/delivery/receipts/KVP-019-COMPLETE.receipt.json",
        "receiptId": "KVP-019-COMPLETE",
        "requiredDependencyReceipts": ["KVP-017-COMPLETE", "KVP-018-COMPLETE"],
        "requiredGateIds": ["KVP-019-GREEN", "KVP-019-RED"],
    }
    assert set(task["forbiddenWork"]) == {
        "VFS refresh",
        "Gradle import",
        "Background repair",
        "Per-event semantic job",
        "Event-triggered semantic work from a VFS listener",
    }
    assert {
        "workspace/contract",
        "workspace/intellij-read",
        "build-logic/src/main/kotlin/support/delivery",
        "build/reports/delivery/receipts",
        "scripts/verify_kvp019_delivery.py",
    } <= set(task["allowedReads"])
    assert {
        "workspace/contract",
        "workspace/intellij-read",
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
            "project/epoch/model/freshness"
        ),
        "scripts/verify_bundle.py",
        "scripts/verify_kvp019_delivery.py",
    } <= set(task["allowedWrites"])

    section = normative_plan.split(
        "### KVP-019: Issue a VFS-passive freshness capability\n",
        maxsplit=1,
    )[1].split("\n### KVP-020:", maxsplit=1)[0]
    assert "**Dependencies.** `KVP-017`, `KVP-018`." in section

    freshness_root = pathlib.Path(
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
        "project/epoch/model/freshness"
    )
    required = [
        freshness_root / "AGENTS.md",
        freshness_root / "Kvp019VfsPassiveReport.kt",
        freshness_root / "Kvp019ReceiptDependencies.kt",
        freshness_root / "Kvp019ReceiptProgression.kt",
        freshness_root / "Kvp019ReceiptRegistration.kt",
        freshness_root / "Kvp019ReceiptTasks.kt",
        freshness_root / "Kvp019ReportTasks.kt",
        pathlib.Path(
            "workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/"
            "VfsPassiveReadCapability.kt"
        ),
        pathlib.Path(
            "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/VfsPassiveReadAdmission.kt"
        ),
    ]
    assert all((root / path).is_file() for path in required)

    report = (root / freshness_root / "Kvp019VfsPassiveReport.kt").read_text()
    for fact in (
        'taskId = "KVP-019"',
        "Kvp019ReportAuthority.READ_EPOCH",
        "Kvp019PublicInterface.VfsPassiveReadCapability",
        "Kvp019AdmissionMode.IDE_SNAPSHOT_ONLY",
        "freshnessObservationCountPerAdmission = 1",
        "unavailableObservationFailureCount = Kvp019UnavailableObservationFailure.entries.size",
        "Kvp019UnavailableObservationFailure.entries",
        "Kvp019ObservationFailureStage.entries",
        "OBSERVATION_FAILED",
        "DUMB_MODE",
        "ADMITTED_SAME_SOURCE_EQUAL_STATE",
        "PROPAGATED_PLATFORM_CANCELLATION",
        "CANONICAL_ROOT",
        "ADMITTED_EPOCH",
        "VFS_REFRESH",
        "EVENT_TRIGGERED_SEMANTIC_WORK",
        "KVP_017_COMPLETE",
        "KVP_018_COMPLETE",
    ):
        assert fact in report

    registration = (root / freshness_root / "Kvp019ReceiptRegistration.kt").read_text()
    for artifact in (
        "VfsPassiveReadCapability.kt",
        "VfsPassiveReadAdmission.kt",
        "ProjectReadEpochSourceFactory.kt",
        "VfsPassiveAdmissionNegativeTest.kt",
        "VfsPassiveAdmissionTest.kt",
        "Kvp019VfsPassiveReport.kt",
        "Kvp019ReceiptDependencies.kt",
        "Kvp019ReceiptProgression.kt",
        "Kvp019ReceiptRegistration.kt",
        "Kvp019ReceiptTasks.kt",
        "Kvp019ReportTasks.kt",
        "verify_kvp019_delivery.py",
    ):
        assert artifact in registration
    assert "registerKvp019ReceiptProgression" in registration
    assert "verifyKVP017CompletionReceipt" in registration
    assert "verifyKVP018CompletionReceipt" in registration
    assert "verifyVfsPassiveReportNegative" in registration

    hosted_registration = (
        root
        / "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
        "project/epoch/model/Kvp018ReceiptRegistration.kt"
    ).read_text()
    epoch_registration = (
        root
        / "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
        "project/epoch/Kvp015ReceiptRegistration.kt"
    ).read_text()
    assert "registerKvp019ReceiptProgression" in hosted_registration
    assert "hostedTasks" in epoch_registration

    module_build = (root / "workspace/intellij-read/build.gradle.kts").read_text()
    assert "GenerateKvp019VfsPassiveReportTask" in module_build
    assert "VerifyKvp019VfsPassiveReportNegativeTask" in module_build
    assert '"kast.ide.vfs.passive.report"' in module_build
    assert "mustRunAfter(verifyVfsPassiveReportNegative)" in module_build
    for test_name in ("VfsPassiveAdmissionNegativeTest.kt", "VfsPassiveAdmissionTest.kt"):
        test = (
            root
            / "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/"
            "intellij/read/epoch"
            / test_name
        ).read_text()
        assert "assertExactVfsPassiveReport()" in test


if __name__ == "__main__":
    import json

    repository = pathlib.Path(__file__).resolve().parents[1]
    projection = json.loads(
        (repository / "gradle/delivery/kast-vfs-passive-reused-index-program.json").read_text()
    )
    plan = (repository / "docs/kast-vfs-passive-reused-index-delivery-program.md").read_text()
    verify_kvp019_delivery(repository, projection, plan)
    print("KVP-019 delivery contract: valid")
