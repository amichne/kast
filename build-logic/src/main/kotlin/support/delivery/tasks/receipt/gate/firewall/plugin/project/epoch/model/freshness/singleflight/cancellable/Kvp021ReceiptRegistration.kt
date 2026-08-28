package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp021ReceiptProgression(
    program: DeliveryProgram,
    freshness: TaskReceiptRegistration,
    singleFlight: TaskReceiptRegistration,
    configureSingleFlight: Kvp020ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val cancellable = taskReceiptRegistration(program, TaskId("KVP-021"))
    val runtimeMain =
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeTest =
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeExecution = runtimeMain + "execution/"
    val workspaceExecution =
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/" +
            "read/epoch/execution/"
    val reportRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/cancellable/"
    val sharedArtifactPaths = listOf(
        "AGENTS.md",
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/build.gradle.kts",
        runtimeMain + "AGENTS.md",
        runtimeMain + "ProjectReadOutcomes.kt",
        runtimeMain + "ProjectReadPermit.kt",
        runtimeMain + "ProjectReadScope.kt",
        runtimeMain + "ProjectReadSingleFlight.kt",
        runtimeExecution + "AGENTS.md",
        runtimeExecution + "CancellableProjectReadExecutor.kt",
        runtimeExecution + "CancellableProjectReadOutcomes.kt",
        runtimeExecution + "CancellableProjectReadProcess.kt",
        runtimeTest + "AGENTS.md",
        runtimeTest + "CancellableReadFixtures.kt",
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/" +
            "read/ExistingProjectAdmission.kt",
        workspaceExecution + "AGENTS.md",
        workspaceExecution + "AdmittedProjectReadExecution.kt",
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
            "project/epoch/model/freshness/Kvp019ReceiptRegistration.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/Kvp020ReceiptRegistration.kt",
        reportRoot + "AGENTS.md",
        reportRoot + "Kvp021CancellableReadReport.kt",
        reportRoot + "Kvp021GateExecution.kt",
        reportRoot + "Kvp021MutationProof.kt",
        reportRoot + "Kvp021ReceiptDependencies.kt",
        reportRoot + "Kvp021ReceiptProgression.kt",
        reportRoot + "Kvp021ReceiptRegistration.kt",
        reportRoot + "Kvp021ReceiptTasks.kt",
        reportRoot + "Kvp021ReportPredecessors.kt",
        reportRoot + "Kvp021ReportTasks.kt",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp021_delivery.py",
    )
    val redArtifactPaths = sharedArtifactPaths +
        (runtimeTest + "CancellableReadNegativeTest.kt")
    val greenArtifactPaths = redArtifactPaths +
        (runtimeTest + "CancellableReadTest.kt")
    val redGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-021-red-gate.json",
    )
    val greenGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-021-green-gate.json",
    )

    fun Kvp021ReceiptTaskBase.configureCancellable() {
        configureSingleFlight()
        cancellableTaskId.set(cancellable.task.id.value)
        cancellableRedGateId.set(cancellable.redGate.id)
        cancellableGreenGateId.set(cancellable.greenGate.id)
        cancellableCompletionGateId.set(cancellable.completionGate.id)
        cancellableRedReceiptId.set(cancellable.redGate.outputReceiptId)
        cancellableGreenReceiptId.set(cancellable.greenGate.outputReceiptId)
        cancellableCompletionReceiptId.set(cancellable.completionGate.outputReceiptId)
        cancellableRedCommand.set(cancellable.redGate.command)
        cancellableGreenCommand.set(cancellable.greenGate.command)
        cancellableCompletionCommand.set(cancellable.completionGate.command)
        cancellableTaskInputDigest.set(cancellable.taskInputDigest)
        cancellableCompletionInputDigest.set(cancellable.completionInputDigest)
        cancellableProofReportPath.set(cancellable.task.outputs.single().path)
        cancellableRedGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-021-red-gate.json",
        )
        cancellableGreenGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-021-green-gate.json",
        )
        cancellableRedArtifactPaths.set(redArtifactPaths)
        cancellableGreenArtifactPaths.set(greenArtifactPaths)
        cancellableRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        cancellableGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        directSingleFlightRedReceiptFile.set(singleFlight.redReceipt)
        directSingleFlightGreenReceiptFile.set(singleFlight.greenReceipt)
        directSingleFlightProofReportFile.set(singleFlight.proofReport)
        directSingleFlightCompletionReceiptFile.set(singleFlight.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP021RedReceipt",
        RecordKvp021RedReceiptTask::class.java,
    ) {
        configureCancellable()
        dependsOn(
            "verifyKVP019CompletionReceipt",
            "verifyKVP020CompletionReceipt",
            ":runtime:ide-read:cancellableReadNegativeGate",
        )
        redGateEvidenceFile.set(redGateEvidence)
        receiptFile.set(cancellable.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP021GreenReceipt",
        RecordKvp021GreenReceiptTask::class.java,
    ) {
        configureCancellable()
        dependsOn(recordRed, ":runtime:ide-read:cancellableReadGate")
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(cancellable.redReceipt)
        proofReportFile.set(cancellable.proofReport)
        receiptFile.set(cancellable.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP021Completion",
        DeriveKvp021CompletionReceiptTask::class.java,
    ) {
        configureCancellable()
        dependsOn(recordGreen)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(cancellable.redReceipt)
        greenReceiptFile.set(cancellable.greenReceipt)
        proofReportFile.set(cancellable.proofReport)
        receiptFile.set(cancellable.completionReceipt)
    }
    tasks.register(
        "verifyKVP021CompletionReceipt",
        VerifyKvp021CompletionReceiptTask::class.java,
    ) {
        configureCancellable()
        dependsOn(derive)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(cancellable.redReceipt)
        greenReceiptFile.set(cancellable.greenReceipt)
        proofReportFile.set(cancellable.proofReport)
        completionReceiptFile.set(cancellable.completionReceipt)
    }
    val revalidationTasks = registerKvp022ReceiptProgression(program, cancellable) {
        configureCancellable()
    }
    return setOf(cancellable.task.id) + revalidationTasks
}
