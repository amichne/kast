package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp039AtomicProof(): Set<TaskId> {
    val packet = canonicalKvp039TaskPacket()
    val packetFile = layout.buildDirectory.file("reports/delivery/task-packets/KVP-039.packet.json")
    val negativeReport = layout.buildDirectory.file("reports/ide-hosted/KVP-039-negative.json")
    val report = layout.projectDirectory.file(packet.task.outputs.single().path)
    val receipt = layout.projectDirectory.file(packet.receipt.outputPath)
    val kvp038Receipt = layout.buildDirectory.file(
        "reports/delivery/receipts/KVP-038-COMPLETE.receipt.json",
    )
    val kvp038Report = layout.projectDirectory.file(
        canonicalKvp038TaskPacket().task.outputs.single().path,
    )
    val workflow = layout.projectDirectory.file(KVP039_WORKFLOW_PATH)
    val generatePacket = tasks.register(
        "generateKVP039TaskPacket", GenerateKvp039TaskPacketTask::class.java,
    ) {
        group = "verification"
        description = "Generates KVP-039's packet from the canonical Kotlin graph."
        this.packetFile.set(packetFile)
    }
    val negative = tasks.register(
        "exactHeadCiNegativeProof", Kvp039NegativeTask::class.java,
    ) {
        group = "verification"
        description = "Rejects the graph-named merge-head or changed-command CI misuse."
        workflowFile.set(workflow)
        reportFile.set(negativeReport)
    }
    val contract = tasks.register(
        "verifyExactHeadCiContract", Kvp039ContractTask::class.java,
    ) {
        group = "verification"
        description = "Refines the exact pull-request-head CI workflow and predecessor closure."
        mustRunAfter(negative)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        workflowFile.set(workflow)
        kvp038ReceiptFile.set(kvp038Receipt)
        kvp038ReportFile.set(kvp038Report)
        reportFile.set(report)
    }
    tasks.register("proveKVP039", ProveKvp039Task::class.java) {
        group = "verification"
        description = "Executes KVP-039 misuse/legal proof and emits one content receipt."
        dependsOn(generatePacket, negative, contract)
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        this.packetFile.set(packetFile)
        kvp038ReceiptFile.set(kvp038Receipt)
        kvp038ReportFile.set(kvp038Report)
        negativeReportFile.set(negativeReport)
        exactHeadReportFile.set(report)
        receiptFile.set(receipt)
    }
    return setOf(packet.task.id)
}
