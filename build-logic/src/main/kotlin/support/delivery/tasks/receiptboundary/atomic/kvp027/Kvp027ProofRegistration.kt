package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp027AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp027Packet()
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-027.packet.json",
    )
    val evidence = project(":cli").layout.buildDirectory.file(
        "reports/KVP-027-gate-evidence.json",
    )
    val kvp026 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-026-COMPLETE.receipt.json",
    )
    val kvp026Report = layout.projectDirectory.file(
        canonicalKvp026TaskPacket().task.outputs.single().path,
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-027.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP027TaskPacket",
        GenerateKvp027TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-027's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP027Proof", PrepareKvp027ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-027's closure and selects receipt reuse or execution."
        dependsOn("proveKVP026", generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp026ReceiptFile.set(kvp026)
        kvp026ReportFile.set(kvp026Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    project(":cli").tasks.withType(Kvp027AtomicProofEvidenceTask::class.java).configureEach {
        if (name == "proveKVP027Cases") {
            dependsOn(prepare)
            onlyIf("KVP-027 content closure requires fresh execution") {
                readRequiredKvp027File(decision.get().asFile.toPath()) !=
                    "${Kvp027ProofDecision.REUSE.name}\n"
            }
        }
    }
    tasks.register("proveKVP027", ProveKvp027Task::class.java) {
        group = "verification"
        description = "Executes KVP-027 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, ":cli:proveKVP027Cases")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp026ReceiptFile.set(kvp026)
        kvp026ReportFile.set(kvp026Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
