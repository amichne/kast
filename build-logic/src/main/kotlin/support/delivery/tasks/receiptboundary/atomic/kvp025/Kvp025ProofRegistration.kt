package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp025AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp025Packet()
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-025.packet.json",
    )
    val testEvidence = project(":ide-plugin").layout.buildDirectory.file(
        "reports/KVP-025-test-evidence.json",
    )
    val predecessor = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-024-COMPLETE.receipt.json",
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-025.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP025TaskPacket",
        GenerateKvp025TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-025's complete task packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP025Proof", PrepareKvp025ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-025's closure and selects receipt reuse or execution."
        dependsOn(generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        predecessorReceiptFile.set(predecessor)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    project(":ide-plugin").tasks.withType(Kvp025AtomicProofTestTask::class.java).configureEach {
        if (name == "proveKVP025Cases") {
            dependsOn(prepare)
            onlyIf("KVP-025 content closure requires fresh execution") {
                when (val admitted = admitKvp025ProofDecision(
                    readRequiredKvp025File(decision.get().asFile.toPath()),
                )) {
                    is Kvp025ProofDecisionAdmission.Complete ->
                        admitted.decision == Kvp025ProofDecision.EXECUTE
                    Kvp025ProofDecisionAdmission.Rejected -> true
                }
            }
        }
    }
    tasks.register("proveKVP025", ProveKvp025Task::class.java) {
        group = "verification"
        description = "Executes KVP-025 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, ":ide-plugin:proveKVP025Cases")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        predecessorReceiptFile.set(predecessor)
        testEvidenceFile.set(testEvidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
