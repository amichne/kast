package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project

internal fun Project.registerKvp038AtomicProof(): Set<TaskId> {
    val packet = canonicalKvp038TaskPacket()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-038.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-038-negative.json")
    val negativeEvidence = layout.buildDirectory.file(
        "reports/ide-hosted/KVP-038-negative.evidence",
    )
    val evidence = layout.buildDirectory.file("reports/ide-hosted/KVP-038-clean-checkout.evidence")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP038TaskPacket", GenerateKvp038TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-038's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    val negative = tasks.register(
        "cleanCheckoutNegativeProof", Kvp038NegativeTask::class.java,
    ) {
        group = "verification"
        description = "Rejects the graph-named dirty or reused clean-checkout misuse."
        harnessFile.set(layout.projectDirectory.file(
            "build-logic/src/main/kotlin/support/delivery/gradle/kvp038/prove-clean-checkout.sh",
        ))
        evidenceFile.set(negativeEvidence)
        reportFile.set(negativeReport)
    }
    val acceptance = tasks.register(
        "ideHostedCleanCheckoutAcceptance", Kvp038AcceptanceTask::class.java,
    ) {
        group = "verification"
        description = "Builds and executes the detached exact-head clean-checkout proof."
        mustRunAfter(negative)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        harnessFile.set(layout.projectDirectory.file(
            "build-logic/src/main/kotlin/support/delivery/gradle/kvp038/prove-clean-checkout.sh",
        ))
        evidenceFile.set(evidence)
        reportFile.set(report)
    }
    tasks.register("proveKVP038", ProveKvp038Task::class.java) {
        group = "verification"
        description = "Executes KVP-038 misuse/legal proof and emits one content receipt."
        dependsOn(generatePacket, negative, acceptance)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        configureDependencies038()
        negativeReportFile.set(negativeReport)
        cleanCheckoutReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id) + registerKvp039AtomicProof()
}

private fun ProveKvp038Task.configureDependencies038() {
    kvp008RedReceiptFile.set(receipt038("KVP-008-RED-RECEIPT"))
    kvp008GreenReceiptFile.set(receipt038("KVP-008-GREEN-RECEIPT"))
    kvp008CompletionReceiptFile.set(receipt038("KVP-008-COMPLETE"))
    kvp008ReportFile.set(project.layout.projectDirectory.file(
        "build/reports/delivery/KVP-008-derived-state.json",
    ))
    kvp036ReceiptFile.set(receipt038("KVP-036-COMPLETE"))
    kvp036ReportFile.set(report038(canonicalKvp036TaskPacket()))
    kvp037ReceiptFile.set(receipt038("KVP-037-COMPLETE"))
    kvp037ReportFile.set(report038(canonicalKvp037TaskPacket()))
}

private fun DefaultTask.receipt038(receiptId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$receiptId.receipt.json",
)

private fun DefaultTask.report038(packet: TaskPacket) = project.layout.projectDirectory.file(
    packet.task.outputs.single().path,
)
