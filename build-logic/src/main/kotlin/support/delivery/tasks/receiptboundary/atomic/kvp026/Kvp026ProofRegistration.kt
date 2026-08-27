package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp026AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp026Packet()
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-026.packet.json",
    )
    val evidence = project(":cli").layout.buildDirectory.file(
        "reports/KVP-026-test-evidence.json",
    )
    val kvp013 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-013-COMPLETE.receipt.json",
    )
    val kvp024 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-024-COMPLETE.receipt.json",
    )
    val kvp025 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-025-COMPLETE.receipt.json",
    )
    val kvp025Report = layout.projectDirectory.file(
        canonicalKvp025TaskPacket().task.outputs.single().path,
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-026.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP026TaskPacket",
        GenerateKvp026TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-026's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP026Proof", PrepareKvp026ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-026's closure and selects receipt reuse or execution."
        dependsOn("proveKVP025", generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp013ReceiptFile.set(kvp013)
        kvp024ReceiptFile.set(kvp024)
        kvp025ReceiptFile.set(kvp025)
        kvp025ReportFile.set(kvp025Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    project(":cli").tasks.withType(Kvp026AtomicProofTestTask::class.java).configureEach {
        if (name == "proveKVP026Cases") {
            dependsOn(prepare)
            onlyIf("KVP-026 content closure requires fresh execution") {
                readRequiredKvp026File(decision.get().asFile.toPath()) !=
                    "${Kvp026ProofDecision.REUSE.name}\n"
            }
        }
    }
    tasks.register("proveKVP026", ProveKvp026Task::class.java) {
        group = "verification"
        description = "Executes KVP-026 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, ":cli:proveKVP026Cases")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp013ReceiptFile.set(kvp013)
        kvp024ReceiptFile.set(kvp024)
        kvp025ReceiptFile.set(kvp025)
        kvp025ReportFile.set(kvp025Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
