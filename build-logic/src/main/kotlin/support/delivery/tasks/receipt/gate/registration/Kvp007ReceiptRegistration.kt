package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp007ReceiptProgression(
    proof: TaskReceiptRegistration,
    configureProof: Kvp007ReceiptTaskBase.() -> Unit,
) {
    val recordRed = tasks.register(
        "recordKVP007RedReceipt",
        RecordKvp007RedReceiptTask::class.java,
    ) {
        configureProof(); dependsOn("verifyKVP006CompletionReceipt")
        receiptFile.set(proof.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP007GreenReceipt",
        RecordKvp007GreenReceiptTask::class.java,
    ) {
        configureProof(); dependsOn(recordRed)
        redReceiptFile.set(proof.redReceipt); proofReportFile.set(proof.proofReport)
        receiptFile.set(proof.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP007Completion",
        DeriveKvp007CompletionReceiptTask::class.java,
    ) {
        configureProof(); dependsOn(recordGreen)
        redReceiptFile.set(proof.redReceipt); greenReceiptFile.set(proof.greenReceipt)
        proofReportFile.set(proof.proofReport); receiptFile.set(proof.completionReceipt)
    }
    tasks.register("verifyKVP007CompletionReceipt", VerifyKvp007CompletionReceiptTask::class.java) {
        configureProof(); dependsOn(derive)
        redReceiptFile.set(proof.redReceipt); greenReceiptFile.set(proof.greenReceipt)
        proofReportFile.set(proof.proofReport); completionReceiptFile.set(proof.completionReceipt)
    }
}
