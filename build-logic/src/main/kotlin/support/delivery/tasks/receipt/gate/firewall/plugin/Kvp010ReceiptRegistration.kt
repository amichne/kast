package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp010ReceiptProgression(
    program: DeliveryProgram,
    firewall: TaskReceiptRegistration,
    configureFirewall: Kvp009ReceiptTaskBase.() -> Unit,
): TaskReceiptRegistration {
    val standalonePlugin = taskReceiptRegistration(program, TaskId("KVP-010"))

    fun Kvp010ReceiptTaskBase.configureStandalonePlugin() {
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
    }

    val recordRed = tasks.register(
        "recordKVP010RedReceipt",
        RecordKvp010RedReceiptTask::class.java,
    ) {
        configureStandalonePlugin()
        dependsOn("verifyKVP009CompletionReceipt")
        receiptFile.set(standalonePlugin.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP010GreenReceipt",
        RecordKvp010GreenReceiptTask::class.java,
    ) {
        configureStandalonePlugin(); dependsOn(recordRed)
        redReceiptFile.set(standalonePlugin.redReceipt)
        proofReportFile.set(standalonePlugin.proofReport)
        receiptFile.set(standalonePlugin.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP010Completion",
        DeriveKvp010CompletionReceiptTask::class.java,
    ) {
        configureStandalonePlugin(); dependsOn(recordGreen)
        mustRunAfter(":ide-plugin:buildPlugin")
        redReceiptFile.set(standalonePlugin.redReceipt)
        greenReceiptFile.set(standalonePlugin.greenReceipt)
        proofReportFile.set(standalonePlugin.proofReport)
        receiptFile.set(standalonePlugin.completionReceipt)
    }
    tasks.register("verifyKVP010CompletionReceipt", VerifyKvp010CompletionReceiptTask::class.java) {
        configureStandalonePlugin(); dependsOn(derive)
        redReceiptFile.set(standalonePlugin.redReceipt)
        greenReceiptFile.set(standalonePlugin.greenReceipt)
        proofReportFile.set(standalonePlugin.proofReport)
        completionReceiptFile.set(standalonePlugin.completionReceipt)
    }
    return standalonePlugin
}
