package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp032AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp032Packet()
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-032.packet.json",
    )
    val evidence = layout.buildDirectory.file(
        "reports/ide-hosted/KVP-032-gate-evidence.json",
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-032.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP032TaskPacket",
        GenerateKvp032TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-032's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP032Proof", PrepareKvp032ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-032's closure and selects receipt reuse or execution."
        dependsOn(generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        configureDependencies()
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val cases = tasks.register(
        "proveKVP032Cases",
        Kvp032AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(prepare, "verifyVfsPassiveReadNegative", "verifyVfsPassiveRead")
        configureFrom(packet)
        proofReportFile.set(report)
        evidenceFile.set(evidence)
        onlyIf("KVP-032 content closure requires fresh execution") {
            when (val read = readKvp032TextFile(decision.get().asFile.toPath())) {
                is Kvp032TextFileRead.Complete ->
                    read.text != "${Kvp032ProofDecision.REUSE.name}\n"
                is Kvp032TextFileRead.Rejected -> true
            }
        }
    }
    tasks.register("proveKVP032", ProveKvp032Task::class.java) {
        group = "verification"
        description = "Executes KVP-032 misuse/legal proof and emits one content receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        configureDependencies()
        evidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}

private fun PrepareKvp032ProofTask.configureDependencies() {
    kvp010WitnessReceiptFile.set(receipt("KVP-010"))
    kvp011ReceiptFile.set(receipt("KVP-011"))
    kvp011ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp011TaskPacket().task.outputs.single().path,
    ))
    kvp023ReceiptFile.set(receipt("KVP-023"))
    kvp027ReceiptFile.set(receipt("KVP-027"))
    kvp027ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp027TaskPacket().task.outputs.single().path,
    ))
    kvp031ReceiptFile.set(receipt("KVP-031"))
    kvp031ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp031TaskPacket().task.outputs.single().path,
    ))
}

private fun ProveKvp032Task.configureDependencies() {
    kvp010WitnessReceiptFile.set(receipt("KVP-010"))
    kvp011ReceiptFile.set(receipt("KVP-011"))
    kvp011ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp011TaskPacket().task.outputs.single().path,
    ))
    kvp023ReceiptFile.set(receipt("KVP-023"))
    kvp027ReceiptFile.set(receipt("KVP-027"))
    kvp027ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp027TaskPacket().task.outputs.single().path,
    ))
    kvp031ReceiptFile.set(receipt("KVP-031"))
    kvp031ReportFile.set(project.layout.projectDirectory.file(
        canonicalKvp031TaskPacket().task.outputs.single().path,
    ))
}

private fun org.gradle.api.DefaultTask.receipt(taskId: String) =
    project.layout.buildDirectory.file(
        "reports/delivery/receipts/$taskId-COMPLETE.receipt.json",
    )
