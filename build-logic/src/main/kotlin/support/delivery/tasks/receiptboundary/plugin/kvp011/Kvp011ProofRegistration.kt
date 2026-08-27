package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp011AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp011Packet()
    val idePlugin = project(":ide-plugin")
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-011.packet.json",
    )
    val evidence = idePlugin.layout.buildDirectory.file(
        "reports/KVP-011-gate-evidence.json",
    )
    val kvp010 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-010-COMPLETE.receipt.json",
    )
    val kvp025 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-025-COMPLETE.receipt.json",
    )
    val kvp025Report = layout.projectDirectory.file(
        canonicalKvp025TaskPacket().task.outputs.single().path,
    )
    val kvp031 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-031-COMPLETE.receipt.json",
    )
    val kvp031Report = layout.projectDirectory.file(
        canonicalKvp031TaskPacket().task.outputs.single().path,
    )
    val layoutReport = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-011.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP011TaskPacket",
        GenerateKvp011TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-011's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP011Proof", PrepareKvp011ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-011's closure and selects receipt reuse or execution."
        dependsOn(generatePacket, "proveKVP031")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp010ReceiptFile.set(kvp010)
        kvp025ReceiptFile.set(kvp025)
        kvp025ReportFile.set(kvp025Report)
        kvp031ReceiptFile.set(kvp031)
        kvp031ReportFile.set(kvp031Report)
        this.layoutReportFile.set(layoutReport)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val namedCases = setOf("verifyPluginLayoutNegative", "verifyPluginLayout")
    idePlugin.tasks.configureEach {
        if (name in namedCases) {
            dependsOn(prepare)
            onlyIf("KVP-011 content closure requires fresh execution") {
                when (val read = readKvp011TextFile(decision.get().asFile.toPath())) {
                    is Kvp011TextFileRead.Complete ->
                        read.text != "${Kvp011ProofDecision.REUSE.name}\n"
                    is Kvp011TextFileRead.Rejected -> true
                }
            }
        }
    }
    val cases = idePlugin.tasks.register(
        "proveKVP011Cases",
        Kvp011AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(prepare, "verifyPluginLayoutNegative", "verifyPluginLayout")
        configureFrom(packet)
        this.layoutReportFile.set(layoutReport)
        evidenceFile.set(evidence)
        onlyIf("KVP-011 content closure requires fresh execution") {
            when (val read = readKvp011TextFile(decision.get().asFile.toPath())) {
                is Kvp011TextFileRead.Complete ->
                    read.text != "${Kvp011ProofDecision.REUSE.name}\n"
                is Kvp011TextFileRead.Rejected -> true
            }
        }
    }
    tasks.register("proveKVP011", ProveKvp011Task::class.java) {
        group = "verification"
        description = "Executes KVP-011 misuse/legal proof and emits one content receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp010ReceiptFile.set(kvp010)
        kvp025ReceiptFile.set(kvp025)
        kvp025ReportFile.set(kvp025Report)
        kvp031ReceiptFile.set(kvp031)
        kvp031ReportFile.set(kvp031Report)
        evidenceFile.set(evidence)
        decisionFile.set(decision)
        this.layoutReportFile.set(layoutReport)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
