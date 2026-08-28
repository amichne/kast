package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project

internal fun Project.registerKvp034AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp034Packet()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-034.packet.json")
    val metricSpec = layout.buildDirectory.file("reports/ide-hosted/KVP-034-metrics.json")
    val negativeEvidence = layout.buildDirectory.file(
        "reports/ide-hosted/KVP-034-negative-evidence.json",
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP034TaskPacket", GenerateKvp034TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-034's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    val generateMetrics = tasks.register(
        "generateKVP034MetricSpec", GenerateKvp034MetricSpecTask::class.java,
    ) {
        group = "verification"
        description = "Projects KVP-034's installed metrics from the canonical Kotlin graph."
        specFile.set(metricSpec)
    }
    val negative = tasks.register(
        "ideHostedInstalledNegativeProof", Kvp034InstalledNegativeProofTask::class.java,
    ) {
        group = "verification"
        description = "Rejects a mutation of every graph-owned KVP-034 installed metric."
        evidenceFile.set(negativeEvidence)
    }
    val acceptance = tasks.register(
        "ideHostedInstalledExactReadAcceptance", Kvp034InstalledAcceptanceTask::class.java,
    ) {
        group = "verification"
        description = "Runs the installed exact-root four-operation journey in the live IDE."
        dependsOn(generateMetrics)
        mustRunAfter(negative)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        harnessFile.set(layout.projectDirectory.file("acceptance/ide-hosted/prove_installed.py"))
        metricSpecFile.set(metricSpec)
        staticProofFile.set(layout.projectDirectory.file(
            canonicalKvp032TaskPacket().task.outputs.single().path,
        ))
        dynamicProofFile.set(layout.projectDirectory.file(
            canonicalKvp033TaskPacket().task.outputs.single().path,
        ))
        reportFile.set(report)
    }
    tasks.register("proveKVP034", ProveKvp034Task::class.java) {
        group = "verification"
        description = "Executes KVP-034 misuse/legal proof and emits one exact-head receipt."
        dependsOn(generatePacket, negative, acceptance)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        configureDependencies()
        this.negativeEvidenceFile.set(negativeEvidence)
        installedReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun ProveKvp034Task.configureDependencies() {
    kvp027ReceiptFile.set(receipt034("KVP-027"))
    kvp027ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp027TaskPacket().task.outputs.single().path,
    ))
    kvp031ReceiptFile.set(receipt034("KVP-031"))
    kvp031ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp031TaskPacket().task.outputs.single().path,
    ))
    kvp033ReceiptFile.set(receipt034("KVP-033"))
    kvp033ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp033TaskPacket().task.outputs.single().path,
    ))
}

private fun DefaultTask.receipt034(taskId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
)
