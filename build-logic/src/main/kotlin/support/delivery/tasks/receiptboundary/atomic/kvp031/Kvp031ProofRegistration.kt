package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp031AtomicProof(): Set<TaskId> {
    val (packet, _) = canonicalKvp031Packet()
    val runtime = project(":runtime:ide-read")
    val packetOutput = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-031.packet.json",
    )
    val evidence = runtime.layout.buildDirectory.file(
        "reports/KVP-031-gate-evidence.json",
    )
    val kvp030 = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-030-COMPLETE.receipt.json",
    )
    val kvp030Report = layout.projectDirectory.file(
        canonicalKvp030TaskPacket().task.outputs.single().path,
    )
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val decision = layout.buildDirectory.file(
        "reports/delivery/task-packets/KVP-031.proof-decision.txt",
    )
    val generatePacket = tasks.register(
        "generateKVP031TaskPacket",
        GenerateKvp031TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-031's complete packet from the canonical Kotlin graph."
        packetFile.set(packetOutput)
    }
    val prepare = tasks.register("prepareKVP031Proof", PrepareKvp031ProofTask::class.java) {
        group = "verification"
        description = "Revalidates KVP-031's closure and selects receipt reuse or execution."
        dependsOn(generatePacket, "proveKVP030")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp030ReceiptFile.set(kvp030)
        kvp030ReportFile.set(kvp030Report)
        proofReportFile.set(report)
        receiptFile.set(receipt)
        decisionFile.set(decision)
    }
    val namedCaseNames = setOf(
        "ideHostedSymbolDescribeNegativeProof",
        "ideHostedSymbolDescribeAcceptance",
    )
    runtime.tasks.configureEach {
        if (name in namedCaseNames) {
            dependsOn(prepare)
            onlyIf("KVP-031 exact-head closure requires fresh execution") {
                readRequiredKvp031File(decision.get().asFile.toPath()) !=
                    "${Kvp031ProofDecision.REUSE.name}\n"
            }
        }
    }
    val cases = runtime.tasks.register(
        "proveKVP031Cases",
        Kvp031AtomicProofEvidenceTask::class.java,
    ) {
        dependsOn(
            prepare,
            "ideHostedSymbolDescribeNegativeProof",
            "ideHostedSymbolDescribeAcceptance",
        )
        configureFrom(packet)
        misuseResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolDescribeNegativeProof",
        ))
        legalResultsDirectory.set(runtime.layout.buildDirectory.dir(
            "test-results/ideHostedSymbolDescribeAcceptance",
        ))
        evidenceFile.set(evidence)
        onlyIf("KVP-031 exact-head closure requires fresh execution") {
            readRequiredKvp031File(decision.get().asFile.toPath()) !=
                "${Kvp031ProofDecision.REUSE.name}\n"
        }
    }
    tasks.register("proveKVP031", ProveKvp031Task::class.java) {
        group = "verification"
        description = "Executes KVP-031 misuse/legal proof and emits one exact-head receipt."
        dependsOn(prepare, cases)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        packetFile.set(packetOutput)
        kvp030ReceiptFile.set(kvp030)
        kvp030ReportFile.set(kvp030Report)
        testEvidenceFile.set(evidence)
        decisionFile.set(decision)
        proofReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
