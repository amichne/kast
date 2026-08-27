package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import support.tasks.vfspassive.VerifyVfsPassiveDynamicNegativeTask
import support.tasks.vfspassive.VerifyVfsPassiveDynamicTask

internal fun Project.registerKvp033AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp033Packet()
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-033.packet.json",
    )
    val negativeEvidence = layout.buildDirectory.file(
        "reports/ide-hosted/KVP-033-negative-evidence.json",
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val generatePacket = tasks.register(
        "generateKVP033TaskPacket",
        GenerateKvp033TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-033's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val negative = tasks.register(
        "ideHostedVfsSafetyNegativeProof",
        VerifyVfsPassiveDynamicNegativeTask::class.java,
    ) {
        group = "verification"
        description = "Rejects every graph-named KVP-033 dynamic-safety misuse."
        evidenceFile.set(negativeEvidence)
    }
    val acceptance = tasks.register(
        "ideHostedVfsSafetyAcceptance",
        VerifyVfsPassiveDynamicTask::class.java,
    ) {
        group = "verification"
        description = "Runs the non-cacheable KVP-033 contention and movement acceptance."
        dependsOn(
            ":runtime:ide-read:kvp033RuntimeDynamicSafety",
            ":workspace:intellij-read:kvp033WorkspaceEventStorm",
        )
        mustRunAfter(negative)
        runtimeTestResults.set(project(":runtime:ide-read").layout.buildDirectory.dir(
            "test-results/kvp033RuntimeDynamicSafety",
        ))
        workspaceTestResults.set(project(":workspace:intellij-read").layout.buildDirectory.dir(
            "test-results/kvp033WorkspaceEventStorm",
        ))
        reportFile.set(report)
    }
    tasks.register("proveKVP033", ProveKvp033Task::class.java) {
        group = "verification"
        description = "Executes KVP-033 misuse/legal proof and emits one content receipt."
        dependsOn(generatePacket, negative, acceptance)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        configureDependencies()
        negativeEvidenceFile.set(negativeEvidence)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun ProveKvp033Task.configureDependencies() {
    kvp022ReceiptFile.set(receipt033("KVP-022"))
    kvp025ReceiptFile.set(receipt033("KVP-025"))
    kvp025ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp025TaskPacket().task.outputs.single().path,
    ))
    kvp031ReceiptFile.set(receipt033("KVP-031"))
    kvp031ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp031TaskPacket().task.outputs.single().path,
    ))
    kvp032ReceiptFile.set(receipt033("KVP-032"))
    kvp032ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp032TaskPacket().task.outputs.single().path,
    ))
}

private fun DefaultTask.receipt033(taskId: String) = project.layout.buildDirectory.file(
    "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
)
