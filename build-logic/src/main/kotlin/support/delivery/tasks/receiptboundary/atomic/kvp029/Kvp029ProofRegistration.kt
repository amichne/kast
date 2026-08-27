package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp029AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp029Packet()
    val runtime = project(":runtime:ide-read")
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-029.packet.json",
    )
    val evidence = runtime.layout.buildDirectory.file(
        "reports/KVP-029-gate-evidence.json",
    )
    val kvp021 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-021-COMPLETE.receipt.json",
    )
    val kvp023 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-023-COMPLETE.receipt.json",
    )
    val kvp028 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-028-COMPLETE.receipt.json",
    )
    val kvp028Report = layout.projectDirectory.file(
        canonicalKvp028TaskPacket().task.outputs.single().path,
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-029.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP029TaskPacket",
        GenerateKvp029TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-029's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP029Proof", PrepareKvp029ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-029's closure and selects receipt reuse or execution."
        dependsOn(generatePacket)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp021ReceiptFile.set(kvp021)
        kvp023ReceiptFile.set(kvp023)
        kvp028ReceiptFile.set(kvp028)
        kvp028ReportFile.set(kvp028Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val namedCaseNames = setOf(
        "ideHostedSymbolDiscoverNegativeProof",
        "ideHostedSymbolDiscoverAcceptance",
    )
    runtime.tasks.configureEach {
        if (name in namedCaseNames) {
            dependsOn(prepare)
            onlyIf("KVP-029 content closure requires fresh execution") {
                readRequiredKvp029File(decision.get().asFile.toPath()) !=
                    "${Kvp029ProofDecision.REUSE.name}\n"
            }
        }
    }
    val cases = runtime.tasks.register(
        "proveKVP029Cases",
        Kvp029AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(
            prepare,
            "ideHostedSymbolDiscoverNegativeProof",
            "ideHostedSymbolDiscoverAcceptance",
        )
        configureFrom(packet)
        misuseResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolDiscoverNegativeProof",
        ))
        legalResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolDiscoverAcceptance",
        ))
        evidenceFile.set(evidence)
        onlyIf("KVP-029 content closure requires fresh execution") {
            readRequiredKvp029File(decision.get().asFile.toPath()) !=
                "${Kvp029ProofDecision.REUSE.name}\n"
        }
    }
    tasks.register("proveKVP029", ProveKvp029Task::class.java) {
        group = "verification"
        description = "Executes KVP-029 misuse/legal proof and emits one content-scoped receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp021ReceiptFile.set(kvp021)
        kvp023ReceiptFile.set(kvp023)
        kvp028ReceiptFile.set(kvp028)
        kvp028ReportFile.set(kvp028Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
