package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp012ReceiptProgression(
    program: DeliveryProgram,
    typeModel: TaskReceiptRegistration,
    firewall: TaskReceiptRegistration,
    configureFirewall: Kvp009ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val standalonePlugin = registerKvp010ReceiptProgression(
        program,
        firewall,
        configureFirewall,
    )
    val compatibility = taskReceiptRegistration(program, TaskId("KVP-012"))
    val operationRegistryPath =
        "protocol/wire/build/generated/operation-registry/operation-registry.json"
    val operationRegistry = layout.projectDirectory.file(operationRegistryPath)
    val negativeTestPath =
        "ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/compatibility/" +
            "IdeHostCompatibilityNegativeTest.kt"
    val negativeTest = layout.projectDirectory.file(negativeTestPath)
    val declaredKastPluginVersion = providers.provider { version.toString() }

    fun Kvp012ReceiptTaskBase.configureCompatibility() {
        configureFirewall()
        standalonePluginTaskId.set(standalonePlugin.task.id.value)
        standalonePluginRedGateId.set(standalonePlugin.redGate.id)
        standalonePluginGreenGateId.set(standalonePlugin.greenGate.id)
        standalonePluginCompletionGateId.set(standalonePlugin.completionGate.id)
        standalonePluginRedReceiptId.set(standalonePlugin.redGate.outputReceiptId)
        standalonePluginGreenReceiptId.set(standalonePlugin.greenGate.outputReceiptId)
        standalonePluginCompletionReceiptId.set(standalonePlugin.completionGate.outputReceiptId)
        standalonePluginRedCommand.set(standalonePlugin.redGate.command)
        standalonePluginGreenCommand.set(standalonePlugin.greenGate.command)
        standalonePluginCompletionCommand.set(standalonePlugin.completionGate.command)
        standalonePluginTaskInputDigest.set(standalonePlugin.taskInputDigest)
        standalonePluginCompletionInputDigest.set(standalonePlugin.completionInputDigest)
        standalonePluginProofReportPath.set(standalonePlugin.task.outputs.single().path)
        directFirewallRedReceiptFile.set(firewall.redReceipt)
        directFirewallGreenReceiptFile.set(firewall.greenReceipt)
        directFirewallProofReportFile.set(firewall.proofReport)
        directFirewallCompletionReceiptFile.set(firewall.completionReceipt)

        compatibilityTaskId.set(compatibility.task.id.value)
        compatibilityRedGateId.set(compatibility.redGate.id)
        compatibilityGreenGateId.set(compatibility.greenGate.id)
        compatibilityCompletionGateId.set(compatibility.completionGate.id)
        compatibilityRedReceiptId.set(compatibility.redGate.outputReceiptId)
        compatibilityGreenReceiptId.set(compatibility.greenGate.outputReceiptId)
        compatibilityCompletionReceiptId.set(compatibility.completionGate.outputReceiptId)
        compatibilityRedCommand.set(compatibility.redGate.command)
        compatibilityGreenCommand.set(compatibility.greenGate.command)
        compatibilityCompletionCommand.set(compatibility.completionGate.command)
        compatibilityTaskInputDigest.set(compatibility.taskInputDigest)
        compatibilityCompletionInputDigest.set(compatibility.completionInputDigest)
        compatibilityProofReportPath.set(compatibility.task.outputs.single().path)
        this.operationRegistryPath.set(operationRegistryPath)
        expectedKastPluginVersion.set(declaredKastPluginVersion)
        compatibilityNegativeTestPath.set(negativeTestPath)

        directTypeModelRedReceiptFile.set(typeModel.redReceipt)
        directTypeModelGreenReceiptFile.set(typeModel.greenReceipt)
        directTypeModelProofReportFile.set(typeModel.proofReport)
        directTypeModelCompletionReceiptFile.set(typeModel.completionReceipt)
        directStandaloneRedReceiptFile.set(standalonePlugin.redReceipt)
        directStandaloneGreenReceiptFile.set(standalonePlugin.greenReceipt)
        directStandaloneProofReportFile.set(standalonePlugin.proofReport)
        directStandaloneCompletionReceiptFile.set(standalonePlugin.completionReceipt)
        compatibilityNegativeTestFile.set(negativeTest)
        operationRegistryFile.set(operationRegistry)
    }

    val recordRed = tasks.register(
        "recordKVP012RedReceipt",
        RecordKvp012RedReceiptTask::class.java,
    ) {
        configureCompatibility()
        dependsOn("verifyKVP002CompletionReceipt", "verifyKVP010CompletionReceipt")
        receiptFile.set(compatibility.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP012GreenReceipt",
        RecordKvp012GreenReceiptTask::class.java,
    ) {
        configureCompatibility()
        dependsOn(recordRed)
        redReceiptFile.set(compatibility.redReceipt)
        proofReportFile.set(compatibility.proofReport)
        receiptFile.set(compatibility.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP012Completion",
        DeriveKvp012CompletionReceiptTask::class.java,
    ) {
        configureCompatibility()
        dependsOn(recordGreen)
        mustRunAfter(":ide-plugin:generateIdeHostCompatibilityReport")
        redReceiptFile.set(compatibility.redReceipt)
        greenReceiptFile.set(compatibility.greenReceipt)
        proofReportFile.set(compatibility.proofReport)
        receiptFile.set(compatibility.completionReceipt)
    }
    tasks.register(
        "verifyKVP012CompletionReceipt",
        VerifyKvp012CompletionReceiptTask::class.java,
    ) {
        configureCompatibility()
        dependsOn(derive)
        redReceiptFile.set(compatibility.redReceipt)
        greenReceiptFile.set(compatibility.greenReceipt)
        proofReportFile.set(compatibility.proofReport)
        completionReceiptFile.set(compatibility.completionReceipt)
    }
    val endpoint = registerKvp013ReceiptProgression(program, compatibility) {
        configureCompatibility()
    }
    val projectTasks = registerKvp014ReceiptProgression(program, compatibility) {
        configureCompatibility()
    }
    return setOf(
        standalonePlugin.task.id,
        compatibility.task.id,
        endpoint,
    ) + projectTasks
}
