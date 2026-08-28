package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp009ReceiptProgression(
    firewall: TaskReceiptRegistration,
    configureFirewall: Kvp009ReceiptTaskBase.() -> Unit,
) {
    val recordRed = tasks.register(
        "recordKVP009RedReceipt",
        RecordKvp009RedReceiptTask::class.java,
    ) {
        configureFirewall()
        dependsOn("verifyKVP001CompletionReceipt", "verifyKVP006CompletionReceipt")
        receiptFile.set(firewall.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP009GreenReceipt",
        RecordKvp009GreenReceiptTask::class.java,
    ) {
        configureFirewall(); dependsOn(recordRed)
        redReceiptFile.set(firewall.redReceipt); proofReportFile.set(firewall.proofReport)
        receiptFile.set(firewall.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP009Completion",
        DeriveKvp009CompletionReceiptTask::class.java,
    ) {
        configureFirewall(); dependsOn(recordGreen)
        redReceiptFile.set(firewall.redReceipt); greenReceiptFile.set(firewall.greenReceipt)
        proofReportFile.set(firewall.proofReport); receiptFile.set(firewall.completionReceipt)
    }
    tasks.register("verifyKVP009CompletionReceipt", VerifyKvp009CompletionReceiptTask::class.java) {
        configureFirewall(); dependsOn(derive)
        redReceiptFile.set(firewall.redReceipt); greenReceiptFile.set(firewall.greenReceipt)
        proofReportFile.set(firewall.proofReport)
        completionReceiptFile.set(firewall.completionReceipt)
    }
}
