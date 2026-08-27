package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project

internal fun Project.registerKvp036AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp036Packet()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-036.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-036-negative.json")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP036TaskPacket", GenerateKvp036TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-036's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    tasks.register("proveKVP036", ProveKvp036Task::class.java) {
        group = "verification"
        description = "Executes KVP-036 misuse/legal proof and emits one exact-head receipt."
        dependsOn(
            generatePacket,
            "verifyNoDefaultIsolatedRuntimeNegative",
            "verifyNoDefaultIsolatedRuntime",
            "verifyIdeHostedRelease",
        )
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        configureDependencies()
        negativeReportFile.set(negativeReport)
        retirementReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun ProveKvp036Task.configureDependencies() {
    kvp027ReceiptFile.set(receipt036("KVP-027"))
    kvp027ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp027TaskPacket().task.outputs.single().path,
    ))
    kvp035ReceiptFile.set(receipt036("KVP-035"))
    kvp035ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp035TaskPacket().task.outputs.single().path,
    ))
}

private fun DefaultTask.receipt036(taskId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
)
