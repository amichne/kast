package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp030AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp030Packet()
    val runtime = project(":runtime:ide-read")
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-030.packet.json",
    )
    val evidence = runtime.layout.buildDirectory.file(
        "reports/KVP-030-gate-evidence.json",
    )
    val kvp029 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-029-COMPLETE.receipt.json",
    )
    val kvp029Report = layout.projectDirectory.file(
        canonicalKvp029TaskPacket().task.outputs.single().path,
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-030.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP030TaskPacket",
        GenerateKvp030TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-030's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP030Proof", PrepareKvp030ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-030's closure and selects receipt reuse or execution."
        dependsOn(generatePacket, "proveKVP029")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp029ReceiptFile.set(kvp029)
        kvp029ReportFile.set(kvp029Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val namedCaseNames = setOf(
        "ideHostedSymbolResolveNegativeProof",
        "ideHostedSymbolResolveAcceptance",
    )
    runtime.tasks.configureEach {
        if (name in namedCaseNames) {
            dependsOn(prepare)
            onlyIf("KVP-030 content closure requires fresh execution") {
                readRequiredKvp030File(decision.get().asFile.toPath()) !=
                    "${Kvp030ProofDecision.REUSE.name}\n"
            }
        }
    }
    val cases = runtime.tasks.register(
        "proveKVP030Cases",
        Kvp030AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(
            prepare,
            "ideHostedSymbolResolveNegativeProof",
            "ideHostedSymbolResolveAcceptance",
        )
        configureFrom(packet)
        misuseResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolResolveNegativeProof",
        ))
        legalResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolResolveAcceptance",
        ))
        evidenceFile.set(evidence)
        onlyIf("KVP-030 content closure requires fresh execution") {
            readRequiredKvp030File(decision.get().asFile.toPath()) !=
                "${Kvp030ProofDecision.REUSE.name}\n"
        }
    }
    tasks.register("proveKVP030", ProveKvp030Task::class.java) {
        group = "verification"
        description = "Executes KVP-030 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp029ReceiptFile.set(kvp029)
        kvp029ReportFile.set(kvp029Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
