package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp022ReceiptProgression(
    program: DeliveryProgram,
    cancellable: TaskReceiptRegistration,
    configureCancellable: Kvp021ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val revalidation = taskReceiptRegistration(program, TaskId("KVP-022"))
    val runtimeMain =
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeTest =
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeExecution = runtimeMain + "execution/"
    val runtimeRevalidation = runtimeMain + "revalidation/"
    val runtimeTestRevalidation = runtimeTest + "revalidation/"
    val reportRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/revalidation/"
    val sharedArtifactPaths = listOf(
        "AGENTS.md",
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/build.gradle.kts",
        runtimeMain + "AGENTS.md",
        runtimeExecution + "AGENTS.md",
        runtimeExecution + "CancellableProjectReadExecutor.kt",
        runtimeExecution + "CancellableProjectReadInvalidationMapping.kt",
        runtimeExecution + "CancellableProjectReadProcess.kt",
        runtimeRevalidation + "AGENTS.md",
        runtimeRevalidation + "ProjectReadEpochObserver.kt",
        runtimeRevalidation + "RevalidatedIdeReadResult.kt",
        runtimeTest + "AGENTS.md",
        runtimeTestRevalidation + "AGENTS.md",
        runtimeTestRevalidation + "EpochRevalidationFixtures.kt",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/cancellable/" +
            "Kvp021ReceiptRegistration.kt",
        reportRoot + "AGENTS.md",
        reportRoot + "Kvp022EpochRevalidationReport.kt",
        reportRoot + "Kvp022GateExecution.kt",
        reportRoot + "Kvp022MutationProof.kt",
        reportRoot + "Kvp022ReceiptDependencies.kt",
        reportRoot + "Kvp022ReceiptProgression.kt",
        reportRoot + "Kvp022ReceiptRegistration.kt",
        reportRoot + "Kvp022ReceiptTasks.kt",
        reportRoot + "Kvp022ReportTasks.kt",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp022_delivery.py",
    )
    val redArtifactPaths = sharedArtifactPaths +
        (runtimeTestRevalidation + "EpochRevalidationNegativeTest.kt")
    val greenArtifactPaths = redArtifactPaths +
        (runtimeTestRevalidation + "EpochRevalidationTest.kt")
    val redGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-022-red-gate.json",
    )
    val greenGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-022-green-gate.json",
    )

    fun Kvp022ReceiptTaskBase.configureRevalidation() {
        configureCancellable()
        revalidationTaskId.set(revalidation.task.id.value)
        revalidationRedGateId.set(revalidation.redGate.id)
        revalidationGreenGateId.set(revalidation.greenGate.id)
        revalidationCompletionGateId.set(revalidation.completionGate.id)
        revalidationRedReceiptId.set(revalidation.redGate.outputReceiptId)
        revalidationGreenReceiptId.set(revalidation.greenGate.outputReceiptId)
        revalidationCompletionReceiptId.set(revalidation.completionGate.outputReceiptId)
        revalidationRedCommand.set(revalidation.redGate.command)
        revalidationGreenCommand.set(revalidation.greenGate.command)
        revalidationCompletionCommand.set(revalidation.completionGate.command)
        revalidationTaskInputDigest.set(revalidation.taskInputDigest)
        revalidationCompletionInputDigest.set(revalidation.completionInputDigest)
        revalidationProofReportPath.set(revalidation.task.outputs.single().path)
        revalidationRedGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-022-red-gate.json",
        )
        revalidationGreenGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-022-green-gate.json",
        )
        revalidationRedArtifactPaths.set(redArtifactPaths)
        revalidationGreenArtifactPaths.set(greenArtifactPaths)
        revalidationRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        revalidationGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        directCancellableRedReceiptFile.set(cancellable.redReceipt)
        directCancellableGreenReceiptFile.set(cancellable.greenReceipt)
        directCancellableProofReportFile.set(cancellable.proofReport)
        directCancellableCompletionReceiptFile.set(cancellable.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP022RedReceipt",
        RecordKvp022RedReceiptTask::class.java,
    ) {
        configureRevalidation()
        dependsOn("verifyKVP021CompletionReceipt", ":runtime:ide-read:epochRevalidationNegativeGate")
        redGateEvidenceFile.set(redGateEvidence)
        receiptFile.set(revalidation.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP022GreenReceipt",
        RecordKvp022GreenReceiptTask::class.java,
    ) {
        configureRevalidation()
        dependsOn(recordRed, ":runtime:ide-read:epochRevalidationGate")
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(revalidation.redReceipt)
        proofReportFile.set(revalidation.proofReport)
        receiptFile.set(revalidation.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP022Completion",
        DeriveKvp022CompletionReceiptTask::class.java,
    ) {
        configureRevalidation()
        dependsOn(recordGreen)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(revalidation.redReceipt)
        greenReceiptFile.set(revalidation.greenReceipt)
        proofReportFile.set(revalidation.proofReport)
        receiptFile.set(revalidation.completionReceipt)
    }
    tasks.register(
        "verifyKVP022CompletionReceipt",
        VerifyKvp022CompletionReceiptTask::class.java,
    ) {
        configureRevalidation()
        dependsOn(derive)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(revalidation.redReceipt)
        greenReceiptFile.set(revalidation.greenReceipt)
        proofReportFile.set(revalidation.proofReport)
        completionReceiptFile.set(revalidation.completionReceipt)
    }
    return setOf(revalidation.task.id)
}
