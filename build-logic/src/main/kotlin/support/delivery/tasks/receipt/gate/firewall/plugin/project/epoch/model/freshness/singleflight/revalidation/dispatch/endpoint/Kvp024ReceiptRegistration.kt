package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp024ReceiptProgression(
    program: DeliveryProgram,
    configureDispatch: Kvp023ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val descriptor = taskReceiptRegistration(program, TaskId("KVP-013"))
    val dispatch = taskReceiptRegistration(program, TaskId("KVP-023"))
    val publication = taskReceiptRegistration(program, TaskId("KVP-024"))
    val mainRoot = "ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/endpoint/"
    val testRoot = "ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/endpoint/"
    val ownerRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/revalidation/dispatch/endpoint/"
    val sharedArtifacts = listOf(
        "AGENTS.md",
        "ide-plugin/AGENTS.md",
        "ide-plugin/build.gradle.kts",
        "ide-plugin/src/main/resources/META-INF/plugin.xml",
        "ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/compatibility/" +
            "IdeHostCompatibilityMetadata.kt",
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/preparation/" +
            "AGENTS.md",
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/preparation/" +
            "HostedIdeReadRuntime.kt",
        mainRoot + "AGENTS.md",
        mainRoot + "ReadyIdeEndpoint.kt",
        mainRoot + "PreparedIdeEndpoint.kt",
        mainRoot + "IdeEndpointPreparation.kt",
        mainRoot + "IdeEndpointPublication.kt",
        mainRoot + "IdeEndpointPublicationFailure.kt",
        mainRoot + "IdeEndpointService.kt",
        testRoot + "AGENTS.md",
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "AGENTS.md",
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "IdeEndpointLocation.kt",
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "AGENTS.md",
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/" +
            "IdeEndpointLocationTest.kt",
        "build-logic/src/main/kotlin/support/architecture/ArchitectureModel.kt",
        "build-logic/src/main/kotlin/support/architecture/IdeReadFirewall.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/AGENTS.md",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt",
        "build-logic/src/main/kotlin/support/architecture/validation/AGENTS.md",
        "build-logic/src/main/kotlin/support/architecture/validation/" +
            "ArchitecturePolicyValidator.kt",
        "build-logic/src/main/kotlin/support/architecture/validation/ModulePolicyValidator.kt",
        "build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt",
        "build-logic/src/test/kotlin/support/architecture/policy/KastCleanSlatePolicyTest.kt",
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
            "project/epoch/model/freshness/singleflight/revalidation/dispatch/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/revalidation/dispatch/" +
            "Kvp023ReceiptRegistration.kt",
        ownerRoot + "AGENTS.md",
        ownerRoot + "Kvp024DescriptorBindings.kt",
        ownerRoot + "Kvp024EndpointPublicationReport.kt",
        ownerRoot + "Kvp024GateExecution.kt",
        ownerRoot + "Kvp024MutationProof.kt",
        ownerRoot + "Kvp024ReceiptDependencies.kt",
        ownerRoot + "Kvp024ReceiptProgression.kt",
        ownerRoot + "Kvp024ReceiptRegistration.kt",
        ownerRoot + "Kvp024ReceiptTasks.kt",
        ownerRoot + "Kvp024ReportTasks.kt",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp024_delivery.py",
    )
    val redArtifacts = sharedArtifacts + testRoot + "IdeEndpointPublicationNegativeTest.kt"
    val greenArtifacts = redArtifacts + testRoot + "IdeEndpointPublicationTest.kt"
    val redEvidence = layout.projectDirectory.file(
        "ide-plugin/build/reports/KVP-024-red-gate.json",
    )
    val greenEvidence = layout.projectDirectory.file(
        "ide-plugin/build/reports/KVP-024-green-gate.json",
    )

    fun Kvp024ReceiptTaskBase.configurePublication() {
        configureDispatch()
        endpointPublicationTaskId.set(publication.task.id.value)
        endpointPublicationRedGateId.set(publication.redGate.id)
        endpointPublicationGreenGateId.set(publication.greenGate.id)
        endpointPublicationCompletionGateId.set(publication.completionGate.id)
        endpointPublicationRedReceiptId.set(publication.redGate.outputReceiptId)
        endpointPublicationGreenReceiptId.set(publication.greenGate.outputReceiptId)
        endpointPublicationCompletionReceiptId.set(publication.completionGate.outputReceiptId)
        endpointPublicationRedCommand.set(publication.redGate.command)
        endpointPublicationGreenCommand.set(publication.greenGate.command)
        endpointPublicationCompletionCommand.set(publication.completionGate.command)
        endpointPublicationTaskInputDigest.set(publication.taskInputDigest)
        endpointPublicationCompletionInputDigest.set(publication.completionInputDigest)
        endpointPublicationProofReportPath.set(publication.task.outputs.single().path)
        endpointPublicationRedGateEvidencePath.set(
            "ide-plugin/build/reports/KVP-024-red-gate.json",
        )
        endpointPublicationGreenGateEvidencePath.set(
            "ide-plugin/build/reports/KVP-024-green-gate.json",
        )
        endpointPublicationRedArtifactPaths.set(redArtifacts)
        endpointPublicationGreenArtifactPaths.set(greenArtifacts)
        endpointPublicationRedArtifactFiles.from(redArtifacts.map(layout.projectDirectory::file))
        endpointPublicationGreenArtifactFiles.from(
            greenArtifacts.map(layout.projectDirectory::file),
        )
        directEndpointCompletionReceiptFile.set(descriptor.completionReceipt)
        directDispatchRedReceiptFile.set(dispatch.redReceipt)
        directDispatchGreenReceiptFile.set(dispatch.greenReceipt)
        directDispatchProofReportFile.set(dispatch.proofReport)
        directDispatchRedGateEvidenceFile.set(layout.projectDirectory.file(
            "runtime/ide-read/build/reports/KVP-023-red-gate.json",
        ))
        directDispatchGreenGateEvidenceFile.set(layout.projectDirectory.file(
            "runtime/ide-read/build/reports/KVP-023-green-gate.json",
        ))
        directDispatchCompletionReceiptFile.set(dispatch.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP024RedReceipt",
        RecordKvp024RedReceiptTask::class.java,
    ) {
        configurePublication()
        dependsOn(
            "verifyKVP013CompletionReceipt",
            "verifyKVP023CompletionReceipt",
            ":ide-plugin:verifyIdeEndpointPublicationNegative",
        )
        redGateEvidenceFile.set(redEvidence)
        receiptFile.set(publication.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP024GreenReceipt",
        RecordKvp024GreenReceiptTask::class.java,
    ) {
        configurePublication()
        dependsOn(recordRed, ":ide-plugin:verifyIdeEndpointPublication")
        redGateEvidenceFile.set(redEvidence)
        greenGateEvidenceFile.set(greenEvidence)
        redReceiptFile.set(publication.redReceipt)
        proofReportFile.set(publication.proofReport)
        receiptFile.set(publication.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP024Completion",
        DeriveKvp024CompletionReceiptTask::class.java,
    ) {
        configurePublication()
        dependsOn(recordGreen)
        redGateEvidenceFile.set(redEvidence)
        greenGateEvidenceFile.set(greenEvidence)
        redReceiptFile.set(publication.redReceipt)
        greenReceiptFile.set(publication.greenReceipt)
        proofReportFile.set(publication.proofReport)
        receiptFile.set(publication.completionReceipt)
    }
    tasks.register(
        "verifyKVP024CompletionReceipt",
        VerifyKvp024CompletionReceiptTask::class.java,
    ) {
        configurePublication()
        dependsOn(derive)
        redGateEvidenceFile.set(redEvidence)
        greenGateEvidenceFile.set(greenEvidence)
        redReceiptFile.set(publication.redReceipt)
        greenReceiptFile.set(publication.greenReceipt)
        proofReportFile.set(publication.proofReport)
        completionReceiptFile.set(publication.completionReceipt)
    }
    return setOf(publication.task.id)
}
