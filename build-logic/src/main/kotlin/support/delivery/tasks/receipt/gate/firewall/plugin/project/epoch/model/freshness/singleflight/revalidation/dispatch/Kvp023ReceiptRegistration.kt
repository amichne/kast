package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp023ReceiptProgression(
    program: DeliveryProgram,
    configureRevalidation: Kvp022ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val firewall = taskReceiptRegistration(program, TaskId("KVP-009"))
    val detached = taskReceiptRegistration(program, TaskId("KVP-016"))
    val revalidation = taskReceiptRegistration(program, TaskId("KVP-022"))
    val dispatch = taskReceiptRegistration(program, TaskId("KVP-023"))
    val runtimeMain =
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeTest =
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val runtimeDispatch = runtimeMain + "dispatch/"
    val runtimeTestDispatch = runtimeTest + "revalidation/dispatch/"
    val reportRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/revalidation/dispatch/"
    val sharedArtifactPaths = listOf(
        "AGENTS.md",
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/build.gradle.kts",
        runtimeDispatch + "AGENTS.md",
        runtimeDispatch + "IdeReadOperationPorts.kt",
        runtimeDispatch + "IdeReadRuntimeBinding.kt",
        runtimeDispatch + "IdeReadRuntimeDispatch.kt",
        runtimeTestDispatch + "AGENTS.md",
        runtimeTestDispatch + "IdeReadRuntimeDispatchFixtures.kt",
        "protocol/registry/build.gradle.kts",
        "protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/" +
            "CanonicalOperationDefinitions.kt",
        "protocol/wire/build.gradle.kts",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "gradle/architecture/kast-architecture-policy.json",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "AGENTS.md",
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
            "project/epoch/model/freshness/singleflight/revalidation/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/revalidation/" +
            "Kvp022ReceiptRegistration.kt",
        reportRoot + "AGENTS.md",
        reportRoot + "Kvp023ReadOnlyGraphReport.kt",
        reportRoot + "Kvp023GateExecution.kt",
        reportRoot + "Kvp023MutationProof.kt",
        reportRoot + "Kvp023ReceiptDependencies.kt",
        reportRoot + "Kvp023ReceiptProgression.kt",
        reportRoot + "Kvp023ReceiptRegistration.kt",
        reportRoot + "Kvp023ReceiptTasks.kt",
        reportRoot + "Kvp023ReportTasks.kt",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp023_delivery.py",
    )
    val redArtifactPaths = sharedArtifactPaths +
        (runtimeTestDispatch + "IdeReadRuntimeDispatchNegativeTest.kt")
    val greenArtifactPaths = redArtifactPaths +
        (runtimeTestDispatch + "IdeReadRuntimeDispatchTest.kt")
    val redGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-023-red-gate.json",
    )
    val greenGateEvidence = layout.projectDirectory.file(
        "runtime/ide-read/build/reports/KVP-023-green-gate.json",
    )

    fun Kvp023ReceiptTaskBase.configureDispatch() {
        configureRevalidation()
        dispatchTaskId.set(dispatch.task.id.value)
        dispatchRedGateId.set(dispatch.redGate.id)
        dispatchGreenGateId.set(dispatch.greenGate.id)
        dispatchCompletionGateId.set(dispatch.completionGate.id)
        dispatchRedReceiptId.set(dispatch.redGate.outputReceiptId)
        dispatchGreenReceiptId.set(dispatch.greenGate.outputReceiptId)
        dispatchCompletionReceiptId.set(dispatch.completionGate.outputReceiptId)
        dispatchRedCommand.set(dispatch.redGate.command)
        dispatchGreenCommand.set(dispatch.greenGate.command)
        dispatchCompletionCommand.set(dispatch.completionGate.command)
        dispatchTaskInputDigest.set(dispatch.taskInputDigest)
        dispatchCompletionInputDigest.set(dispatch.completionInputDigest)
        dispatchProofReportPath.set(dispatch.task.outputs.single().path)
        dispatchRedGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-023-red-gate.json",
        )
        dispatchGreenGateEvidencePath.set(
            "runtime/ide-read/build/reports/KVP-023-green-gate.json",
        )
        dispatchRedArtifactPaths.set(redArtifactPaths)
        dispatchGreenArtifactPaths.set(greenArtifactPaths)
        dispatchRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        dispatchGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        directFirewallRedReceiptFile.set(firewall.redReceipt)
        directFirewallGreenReceiptFile.set(firewall.greenReceipt)
        directFirewallProofReportFile.set(firewall.proofReport)
        directFirewallCompletionReceiptFile.set(firewall.completionReceipt)
        directDetachedRedReceiptFile.set(detached.redReceipt)
        directDetachedGreenReceiptFile.set(detached.greenReceipt)
        directDetachedProofReportFile.set(detached.proofReport)
        directDetachedCompletionReceiptFile.set(detached.completionReceipt)
        directRevalidationRedReceiptFile.set(revalidation.redReceipt)
        directRevalidationGreenReceiptFile.set(revalidation.greenReceipt)
        directRevalidationProofReportFile.set(revalidation.proofReport)
        directRevalidationCompletionReceiptFile.set(revalidation.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP023RedReceipt",
        RecordKvp023RedReceiptTask::class.java,
    ) {
        configureDispatch()
        dependsOn(
            "verifyKVP009CompletionReceipt",
            "verifyKVP016CompletionReceipt",
            "verifyKVP022CompletionReceipt",
            ":runtime:ide-read:verifyReadOnlyGraphNegative",
        )
        redGateEvidenceFile.set(redGateEvidence)
        receiptFile.set(dispatch.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP023GreenReceipt",
        RecordKvp023GreenReceiptTask::class.java,
    ) {
        configureDispatch()
        dependsOn(
            recordRed,
            ":runtime:ide-read:test",
            ":runtime:ide-read:verifyReadOnlyGraph",
        )
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(dispatch.redReceipt)
        proofReportFile.set(dispatch.proofReport)
        receiptFile.set(dispatch.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP023Completion",
        DeriveKvp023CompletionReceiptTask::class.java,
    ) {
        configureDispatch()
        dependsOn(recordGreen)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(dispatch.redReceipt)
        greenReceiptFile.set(dispatch.greenReceipt)
        proofReportFile.set(dispatch.proofReport)
        receiptFile.set(dispatch.completionReceipt)
    }
    tasks.register(
        "verifyKVP023CompletionReceipt",
        VerifyKvp023CompletionReceiptTask::class.java,
    ) {
        configureDispatch()
        dependsOn(derive)
        redGateEvidenceFile.set(redGateEvidence)
        greenGateEvidenceFile.set(greenGateEvidence)
        redReceiptFile.set(dispatch.redReceipt)
        greenReceiptFile.set(dispatch.greenReceipt)
        proofReportFile.set(dispatch.proofReport)
        completionReceiptFile.set(dispatch.completionReceipt)
    }
    return setOf(dispatch.task.id)
}
