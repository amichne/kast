package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp008ReceiptProgression(
    state: TaskReceiptRegistration,
    configureState: Kvp008ReceiptTaskBase.() -> Unit,
) {
    val recordRed = tasks.register(
        "recordKVP008RedReceipt",
        RecordKvp008RedReceiptTask::class.java,
    ) {
        configureState(); dependsOn("verifyKVP007CompletionReceipt")
        receiptFile.set(state.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP008GreenReceipt",
        RecordKvp008GreenReceiptTask::class.java,
    ) {
        configureState(); dependsOn(recordRed)
        redReceiptFile.set(state.redReceipt); proofReportFile.set(state.proofReport)
        receiptFile.set(state.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP008Completion",
        DeriveKvp008CompletionReceiptTask::class.java,
    ) {
        configureState(); dependsOn(recordGreen)
        redReceiptFile.set(state.redReceipt); greenReceiptFile.set(state.greenReceipt)
        proofReportFile.set(state.proofReport); receiptFile.set(state.completionReceipt)
    }
    tasks.register("verifyKVP008CompletionReceipt", VerifyKvp008CompletionReceiptTask::class.java) {
        configureState(); dependsOn(derive)
        redReceiptFile.set(state.redReceipt); greenReceiptFile.set(state.greenReceipt)
        proofReportFile.set(state.proofReport); completionReceiptFile.set(state.completionReceipt)
    }
}
