package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp040AtomicProof(): Set<TaskId> {
    val packet = canonicalKvp040TaskPacket()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-040.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-040-negative.json")
    val structured = layout.projectDirectory.file("build/reports/ide-hosted/final-review.json")
    val rendered = layout.projectDirectory.file("build/reports/ide-hosted/final-review.md")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val predecessorReceipt = layout.projectDirectory.file(
        canonicalKvp039TaskPacket().receipt.outputPath,
    )
    val predecessorReport = layout.projectDirectory.file(
        canonicalKvp039TaskPacket().task.outputs.single().path,
    )
    val generatePacket = tasks.register(
        "generateKVP040TaskPacket", GenerateKvp040TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-040's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    val negative = tasks.register(
        "finalReviewNegativeProof", Kvp040NegativeTask::class.java,
    ) {
        group = "verification"
        description = "Rejects the graph-named stale exact-head review misuse."
        reportFile.set(negativeReport)
    }
    val review = tasks.register(
        "ideHostedFinalDiffReview", Kvp040ReviewTask::class.java,
    ) {
        group = "verification"
        description = "Reviews the exact-head diff and all graph-required evidence authorities."
        mustRunAfter(negative)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        kvp039ReceiptFile.set(predecessorReceipt)
        kvp039ReportFile.set(predecessorReport)
        structuredReviewFile.set(structured)
        renderedReviewFile.set(rendered)
        reportFile.set(report)
    }
    tasks.register("proveKVP040", ProveKvp040Task::class.java) {
        group = "verification"
        description = "Executes KVP-040 misuse/legal proof and emits one content receipt."
        dependsOn(generatePacket, negative, review)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        kvp039ReceiptFile.set(predecessorReceipt)
        kvp039ReportFile.set(predecessorReport)
        negativeReportFile.set(negativeReport)
        structuredReviewFile.set(structured)
        renderedReviewFile.set(rendered)
        reviewReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
