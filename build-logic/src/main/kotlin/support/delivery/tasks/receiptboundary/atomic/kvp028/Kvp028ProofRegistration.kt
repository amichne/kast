package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp028AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp028Packet()
    val runtime = project(":runtime:ide-read")
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-028.packet.json",
    )
    val evidence = runtime.layout.buildDirectory.file(
        "reports/KVP-028-gate-evidence.json",
    )
    val kvp023 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-023-COMPLETE.receipt.json",
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
        "reports/delivery/task-packets/KVP-028.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP028TaskPacket",
        GenerateKvp028TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-028's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP028Proof", PrepareKvp028ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-028's closure and selects receipt reuse or execution."
        dependsOn(generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp023ReceiptFile.set(kvp023)
        kvp026ReceiptFile.set(kvp026)
        kvp026ReportFile.set(kvp026Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val cases = runtime.tasks.register(
        "proveKVP028Cases",
        Kvp028AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(
            prepare,
            "ideHostedWorkspaceInspectNegativeProof",
            "ideHostedWorkspaceInspectAcceptance",
        )
        configureFrom(packet)
        misuseResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedWorkspaceInspectNegativeProof",
        ))
        legalResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedWorkspaceInspectAcceptance",
        ))
        evidenceFile.set(evidence)
        onlyIf("KVP-028 content closure requires fresh execution") {
            readRequiredKvp028File(decision.get().asFile.toPath()) !=
                "${Kvp028ProofDecision.REUSE.name}\n"
        }
    }
    tasks.register("proveKVP028", ProveKvp028Task::class.java) {
        group = "verification"
        description = "Executes KVP-028 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp023ReceiptFile.set(kvp023)
        kvp026ReceiptFile.set(kvp026)
        kvp026ReportFile.set(kvp026Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
