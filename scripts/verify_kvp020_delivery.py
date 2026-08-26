#!/usr/bin/env python3
import json
import pathlib
import re


def required_text(root, relative_path):
    path = root / relative_path
    assert path.is_file(), relative_path
    return path.read_text()


def require_markers(source, markers):
    for marker in markers:
        assert marker in source, marker


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

    runtime_paths = (
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/build.gradle.kts",
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/AGENTS.md",
        (
            "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "ProjectReadOutcomes.kt"
        ),
        (
            "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "ProjectReadPermit.kt"
        ),
        (
            "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "ProjectReadSingleFlight.kt"
        ),
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/AGENTS.md",
        (
            "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "SingleFlightFixtures.kt"
        ),
        (
            "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "SingleFlightReportFixture.kt"
        ),
        (
            "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "SingleFlightNegativeTest.kt"
        ),
        (
            "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "SingleFlightTest.kt"
        ),
        (
            "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
            "SingleFlightTransitionEvidence.kt"
        ),
    )
    singleflight_root = (
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
        "project/epoch/model/freshness/singleflight/"
    )
    singleflight_names = (
        "AGENTS.md",
        "Kvp020ReceiptDependencies.kt",
        "Kvp020ReceiptProgression.kt",
        "Kvp020ReceiptRegistration.kt",
        "Kvp020ReceiptTasks.kt",
        "Kvp020ReportTasks.kt",
        "Kvp020SingleFlightReport.kt",
        "Kvp020SingleFlightTransitions.kt",
    )
    sources = {
        path: required_text(root, path)
        for path in runtime_paths + tuple(
            singleflight_root + name for name in singleflight_names
        )
    }

    settings = required_text(root, "settings.gradle.kts")
    assert '":runtime:ide-read"' in settings
    module_build = sources["runtime/ide-read/build.gradle.kts"]
    require_markers(module_build, (
        'id("kast.kotlin-library")',
        'kotlin("plugin.serialization")',
        'id("kast.role.ide-read-only")',
        'implementation(project(":workspace:contract"))',
        'findLibrary("serialization-json")',
        "GenerateKvp020SingleFlightReportTask",
        '"generateSingleFlightReport"',
        "VerifyKvp020SingleFlightReportNegativeTask",
        '"verifySingleFlightReportNegative"',
        'rootProject.tasks.named("verifyKVP014CompletionReceipt")',
        'rootProject.tasks.named("verifyKVP019CompletionReceipt")',
        '"reports/delivery/receipts/KVP-014-COMPLETE.receipt.json"',
        '"reports/delivery/receipts/KVP-019-COMPLETE.receipt.json"',
        'systemProperty("kast.ide.single.flight.report"',
        '"kast.ide.single.flight.kvp014.receipt"',
        '"kast.ide.single.flight.kvp019.receipt"',
        "mustRunAfter(verifySingleFlightReportNegative)",
        "dependsOn(verifySingleFlightReportNegative)",
    ))
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

    main_root = "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
    test_root = "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
    outcomes = sources[main_root + "ProjectReadOutcomes.kt"]
    permit = sources[main_root + "ProjectReadPermit.kt"]
    single_flight = sources[main_root + "ProjectReadSingleFlight.kt"]
    fixtures = sources[test_root + "SingleFlightFixtures.kt"]
    report_fixture = sources[test_root + "SingleFlightReportFixture.kt"]
    negative_selector = sources[test_root + "SingleFlightNegativeTest.kt"]
    positive_selector = sources[test_root + "SingleFlightTest.kt"]
    transition_evidence = sources[test_root + "SingleFlightTransitionEvidence.kt"]

    require_markers(outcomes, (
        "internal enum class ProjectReadCancellationCause",
        "internal enum class ProjectReadRetirementCause",
        "internal sealed interface ProjectReadAdmission",
        "internal sealed interface ProjectReadAdmissionFailure",
        "internal sealed interface ProjectReadPermitEnd",
        "internal sealed interface ProjectReadRetirement",
    ))
    require_markers(permit, (
        "class ProjectReadPermit private constructor(",
        "internal class QueuedRequest private constructor(",
        "internal class Controller private constructor(",
        "private var state: State = State.Idle(",
        "is State.ActiveAndQueued",
        "is State.Retired",
        "ProjectReadAdmissionFailure.Busy",
    ))
    require_markers(single_flight, (
        "internal typealias QueuedProjectReadRequest = ProjectReadPermit.QueuedRequest",
        "internal typealias ProjectReadSingleFlight = ProjectReadPermit.QueuedRequest.Controller",
    ))
    product_source = "\n".join((outcomes, permit, single_flight))
    assert re.search(r"\bfun\s+install\s*\(", product_source) is None
    require_markers(fixtures, (
        "ProjectReadSingleFlight::class.java.getDeclaredConstructor(",
        "constructor.isAccessible = true",
    ))
    require_markers(report_fixture, (
        "internal fun assertExactSingleFlightReport()",
        '"kast.ide.single.flight.report"',
        '"kast.ide.single.flight.kvp014.receipt"',
        '"kast.ide.single.flight.kvp019.receipt"',
        '"kast.ide.single.flight.expected.head"',
        "observeKvp020SingleFlightTransitions()",
        "@Serializable",
        ".serializer()",
    ))
    require_markers(transition_evidence, (
        "internal fun observeKvp020SingleFlightTransitions(): List<String>",
        "assertEquals(31, observed.size)",
        "ProjectReadCancellationCause.entries.forEach",
        "ProjectReadRetirementCause.entries.forEach",
        "RETIRED_ADMIT_REJECTS_RETAINING_FIRST_CAUSE_NO_MUTATION",
        "RETIRED_RETIRE_REPEATS_RETAINING_FIRST_CAUSE_NO_MUTATION",
    ))
    assert "assertExactSingleFlightReport()" in negative_selector
    assert "assertExactSingleFlightReport()" in positive_selector

    report_tasks = sources[singleflight_root + "Kvp020ReportTasks.kt"]
    report = sources[singleflight_root + "Kvp020SingleFlightReport.kt"]
    transitions = sources[singleflight_root + "Kvp020SingleFlightTransitions.kt"]
    dependencies = sources[singleflight_root + "Kvp020ReceiptDependencies.kt"]
    progression = sources[singleflight_root + "Kvp020ReceiptProgression.kt"]
    registration = sources[singleflight_root + "Kvp020ReceiptRegistration.kt"]
    receipt_tasks = sources[singleflight_root + "Kvp020ReceiptTasks.kt"]

    require_markers(report_tasks, (
        "GenerateKvp020SingleFlightReportTask",
        "VerifyKvp020SingleFlightReportNegativeTask",
        "abstract val kvp014CompletionReceipt: RegularFileProperty",
        "abstract val kvp019CompletionReceipt: RegularFileProperty",
        "observeKvp020ReportPredecessors(",
        "verifyKvp020SingleFlightReportMutations(",
    ))
    require_markers(report, (
        "Kvp020SingleFlightDocument.serializer()",
        "val transitions: List<Kvp020SingleFlightTransition>",
        "canonicalKvp020SingleFlightTransitions()",
        "Kvp020PredecessorReceiptId.KVP_014_COMPLETE",
        "Kvp020PredecessorReceiptId.KVP_019_COMPLETE",
    ))
    require_markers(transitions, (
        "internal enum class Kvp020SingleFlightTransition",
        "internal fun canonicalKvp020SingleFlightTransitions()",
        "Kvp020SingleFlightReportFailure.TRANSITION_SET_MISMATCH",
        "verifyKvp020TransitionSetMutation(",
    ))
    require_markers(dependencies, (
        "Kvp020DependencyMember.PROJECT_ADMISSION",
        "Kvp020DependencyMember.FRESHNESS",
        "Kvp020PredecessorReceiptId.KVP_014_COMPLETE",
        "Kvp020PredecessorReceiptId.KVP_019_COMPLETE",
    ))
    assert dependencies.index("Kvp020PredecessorReceiptId.KVP_014_COMPLETE") < (
        dependencies.index("Kvp020PredecessorReceiptId.KVP_019_COMPLETE")
    )
    require_markers(progression, (
        "private val predecessorDigests = predecessors.digestMap()",
        "projectAdmissionContexts(head)",
        "freshnessContexts(head)",
        "artifacts[proofReportPath] = sha256Bytes(",
        '"admittedDependencyReceiptCount" to "2"',
        '"admittedGateReceiptCount" to "2"',
        "directFreshnessCompletionReceiptFile",
    ))
    require_markers(receipt_tasks, (
        "RecordKvp020RedReceiptTask",
        "RecordKvp020GreenReceiptTask",
        "DeriveKvp020CompletionReceiptTask",
        "VerifyKvp020CompletionReceiptTask",
        "revalidateExactHead(root, head)",
    ))
    require_markers(registration, (
        "registerKvp020ReceiptProgression(",
        '"verifyKVP014CompletionReceipt"',
        '"verifyKVP019CompletionReceipt"',
        '"recordKVP020RedReceipt"',
        '"recordKVP020GreenReceipt"',
        '"deriveKVP020Completion"',
        '"verifyKVP020CompletionReceipt"',
        "directProjectCompletionReceiptFile.set(projectAdmission.completionReceipt)",
        "directFreshnessCompletionReceiptFile.set(freshness.completionReceipt)",
        "singleFlightRedArtifactFiles.from(",
        "singleFlightGreenArtifactFiles.from(",
    ))

    artifact_markers = (
        '"runtime/ide-read/AGENTS.md"',
        '"runtime/ide-read/build.gradle.kts"',
        'mainRoot + "AGENTS.md"',
        'mainRoot + "ProjectReadOutcomes.kt"',
        'mainRoot + "ProjectReadPermit.kt"',
        'mainRoot + "ProjectReadSingleFlight.kt"',
        'testRoot + "AGENTS.md"',
        'testRoot + "SingleFlightFixtures.kt"',
        'testRoot + "SingleFlightReportFixture.kt"',
        'testRoot + "SingleFlightTransitionEvidence.kt"',
        'testRoot + "SingleFlightNegativeTest.kt"',
        'testRoot + "SingleFlightTest.kt"',
    ) + tuple('reportRoot + "' + name + '"' for name in singleflight_names)
    require_markers(registration, artifact_markers)

    freshness_registration = required_text(
        root,
        (
            "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/"
            "project/epoch/model/freshness/Kvp019ReceiptRegistration.kt"
        ),
    )
    require_markers(freshness_registration, (
        "): Set<TaskId>",
        "val singleFlight = registerKvp020ReceiptProgression(",
        'taskReceiptRegistration(program, TaskId("KVP-014"))',
        "freshness,",
        "configureFreshness()",
        "return setOf(freshness.task.id, singleFlight)",
    ))


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
    print("KVP-020 delivery contract: valid")
