package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project

internal fun Project.registerKvp035AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp035Packet()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-035.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-035-negative.json")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP035TaskPacket", GenerateKvp035TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-035's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    tasks.register("proveKVP035", ProveKvp035Task::class.java) {
        group = "verification"
        description = "Executes KVP-035 misuse/legal proof and emits one content receipt."
        dependsOn(generatePacket, "verifyIdeHostedReleaseNegative", "verifyIdeHostedRelease")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        configureDependencies()
        negativeReportFile.set(negativeReport)
        releaseReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun ProveKvp035Task.configureDependencies() {
    kvp011ReceiptFile.set(receipt035("KVP-011"))
    kvp011ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp011TaskPacket().task.outputs.single().path,
    ))
    kvp034ReceiptFile.set(receipt035("KVP-034"))
    kvp034ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp034TaskPacket().task.outputs.single().path,
    ))
}

private fun DefaultTask.receipt035(taskId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
)
