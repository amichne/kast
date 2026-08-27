package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project

internal fun Project.registerKvp037AtomicProof(): Set<TaskId> {
    val packet = canonicalKvp037TaskPacket()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-037.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-037-negative.json")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP037TaskPacket",
        GenerateKvp037TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-037's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    val negative = tasks.register(
        "ideHostedFailureMatrixNegative",
        Kvp037NegativeTask::class.java,
    ) {
        group = "verification"
        description = "Rejects every graph-owned KVP-037 forbidden-work mutation."
        reportFile.set(negativeReport)
    }
    val acceptance = tasks.register(
        "ideHostedFailureMatrixAcceptance",
        Kvp037AcceptanceTask::class.java,
    ) {
        group = "verification"
        description = "Executes the installed closed failure and unsupported-operation matrix."
        dependsOn(":cli:test", ":protocol:wire:test", ":runtime:ide-read:test", ":ide-plugin:test")
        mustRunAfter(negative)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        harnessFile.set(layout.projectDirectory.file("acceptance/ide-hosted/prove_failures.py"))
        reportFile.set(report)
    }
    tasks.register("proveKVP037", ProveKvp037Task::class.java) {
        group = "verification"
        description = "Executes KVP-037 misuse/legal proof and emits one content receipt."
        dependsOn(
            generatePacket,
            negative,
            acceptance,
            "proveKVP025",
            "proveKVP026",
            "proveKVP027",
            "proveKVP031",
            "proveKVP036",
        )
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        configureDependencies037()
        negativeReportFile.set(negativeReport)
        failureMatrixFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun ProveKvp037Task.configureDependencies037() {
    kvp025ReceiptFile.set(receipt037("KVP-025"))
    kvp025ReportFile.set(report037(canonicalKvp025TaskPacket()))
    kvp026ReceiptFile.set(receipt037("KVP-026"))
    kvp026ReportFile.set(report037(canonicalKvp026TaskPacket()))
    kvp027ReceiptFile.set(receipt037("KVP-027"))
    kvp027ReportFile.set(report037(canonicalKvp027TaskPacket()))
    kvp031ReceiptFile.set(receipt037("KVP-031"))
    kvp031ReportFile.set(report037(canonicalKvp031TaskPacket()))
    kvp036ReceiptFile.set(receipt037("KVP-036"))
    kvp036ReportFile.set(report037(canonicalKvp036TaskPacket()))
}

private fun DefaultTask.receipt037(taskId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
)

private fun DefaultTask.report037(packet: TaskPacket) = project.layout.projectDirectory.file(
    packet.task.outputs.single().path,
)
