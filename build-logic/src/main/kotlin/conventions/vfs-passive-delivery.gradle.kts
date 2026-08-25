package kast

import support.delivery.DeriveKvp001CompletionReceiptTask
import support.delivery.GenerateDeliveryProjectionsTask
import support.delivery.GenerateKastVfsPassiveAuthorityTask
import support.delivery.KastVfsPassiveReusedIndexProgram
import support.delivery.Kvp001ReceiptTaskBase
import support.delivery.RecordKvp001GreenReceiptTask
import support.delivery.RecordKvp001RedReceiptTask
import support.delivery.VerifyDeliveryProjectionsTask
import support.delivery.VerifyKastVfsPassiveAuthorityNegativeTask
import support.delivery.VerifyKastVfsPassiveAuthorityTask
import support.delivery.VerifyKvp001CompletionReceiptTask
import support.delivery.canonicalJson
import support.delivery.sha256

plugins { base }

val program = KastVfsPassiveReusedIndexProgram.validated
val programProjection = program.projection()
val expectedProgramFingerprint = programProjection.getValue("programFingerprint") as String
val authorityTask = program.program.tasks.single { it.id.value == "KVP-001" }
val checkedInProgramProjectionFile =
    layout.projectDirectory.file("gradle/delivery/kast-vfs-passive-reused-index-program.json")
val checkedInRequirementTraceFile =
    layout.projectDirectory.file("gradle/delivery/kast-vfs-passive-requirements.json")
val authorityLedgerFile = layout.projectDirectory.file(
    KastVfsPassiveReusedIndexProgram.authorityLedgerOutputPath.value,
)
val authorityContradictionFile = layout.projectDirectory.file(
    KastVfsPassiveReusedIndexProgram.authorityContradictionOutputPath.value,
)
val authorityNegativeReportPath = "build/reports/delivery/KVP-001-authority-negative.json"
val authorityVerificationReportPath =
    KastVfsPassiveReusedIndexProgram.authorityVerificationOutputPath.value
val expectedProgramProjectionContent = canonicalJson(programProjection) + "\n"
val expectedRequirementTraceContent = canonicalJson(program.requirementTraceProjection()) + "\n"
val receiptDirectory = layout.buildDirectory.dir("reports/delivery/receipts")

tasks.register<GenerateDeliveryProjectionsTask>("generateKastVfsPassiveProjection") {
    group = "verification"
    programProjection.set(expectedProgramProjectionContent)
    programOutputFile.set(checkedInProgramProjectionFile)
    requirementTraceProjection.set(expectedRequirementTraceContent)
    requirementTraceOutputFile.set(checkedInRequirementTraceFile)
}

tasks.register<VerifyDeliveryProjectionsTask>("verifyKastVfsPassiveProjection") {
    group = "verification"
    mustRunAfter("generateKastVfsPassiveProjection")
    expectedProgramProjection.set(expectedProgramProjectionContent)
    programProjectionFile.set(checkedInProgramProjectionFile)
    expectedRequirementTraceProjection.set(expectedRequirementTraceContent)
    requirementTraceProjectionFile.set(checkedInRequirementTraceFile)
}

tasks.register<VerifyKastVfsPassiveAuthorityNegativeTask>("verifyKastVfsPassiveAuthorityNegative") {
    group = "verification"
    dependsOn("verifyKastVfsPassiveProjection")
    baseRevision.set(program.program.targetHead)
    programFingerprint.set(expectedProgramFingerprint)
    requirementFingerprint.set(program.program.requirementFingerprint.value)
    sourceDigests.set(program.program.sourceDigests.mapValues { it.value.value })
    allowedReads.set(authorityTask.allowedReads)
    candidatePaths.set(KastVfsPassiveReusedIndexProgram.authoritySourceCandidates.map { it.value })
    reportFile.set(layout.projectDirectory.file(authorityNegativeReportPath))
}

val generateKastVfsPassiveAuthority =
    tasks.register<GenerateKastVfsPassiveAuthorityTask>("generateKastVfsPassiveAuthority") {
        group = "verification"
        dependsOn("verifyKastVfsPassiveProjection")
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        baseRevision.set(program.program.targetHead)
        programFingerprint.set(expectedProgramFingerprint)
        requirementFingerprint.set(program.program.requirementFingerprint.value)
        sourceDigests.set(program.program.sourceDigests.mapValues { it.value.value })
        allowedReads.set(authorityTask.allowedReads)
        candidatePaths.set(
            KastVfsPassiveReusedIndexProgram.authoritySourceCandidates.map { it.value },
        )
        authorityFile.set(authorityLedgerFile)
        contradictionFile.set(authorityContradictionFile)
    }

tasks.register<VerifyKastVfsPassiveAuthorityTask>("verifyKastVfsPassiveAuthority") {
    group = "verification"
    dependsOn(generateKastVfsPassiveAuthority)
    repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
    authorityFilePath.set(authorityLedgerFile.asFile.absolutePath)
    contradictionFilePath.set(authorityContradictionFile.asFile.absolutePath)
    baseRevision.set(program.program.targetHead)
    programFingerprint.set(expectedProgramFingerprint)
    requirementFingerprint.set(program.program.requirementFingerprint.value)
    sourceDigests.set(program.program.sourceDigests.mapValues { it.value.value })
    allowedReads.set(authorityTask.allowedReads)
    reportFile.set(
        layout.projectDirectory.file(
            KastVfsPassiveReusedIndexProgram.authorityVerificationOutputPath.value,
        ),
    )
}

val authorityRedGate = program.program.gates.single { it.id == authorityTask.red.gateId }
val authorityGreenGate = program.program.gates.single { it.id == authorityTask.green.gateId }
val authorityCompletionGate = program.program.gates.single {
    it.taskId == authorityTask.id && it.kind.name == "TASK_COMPLETION"
}
val authorityRedReceipt = receiptDirectory.map {
    it.file("${authorityRedGate.outputReceiptId}.receipt.json")
}
val authorityGreenReceipt = receiptDirectory.map {
    it.file("${authorityGreenGate.outputReceiptId}.receipt.json")
}
val authorityCompletionReceipt = layout.projectDirectory.file(
    authorityTask.completionReceipt.outputPath,
)
val authorityTaskInputDigest = sha256(canonicalJson(authorityTask.inputs)).value
val authorityCompletionInputDigest = sha256(
    canonicalJson(
        mapOf(
            "receiptId" to authorityTask.completionReceipt.receiptId,
            "requiredGateIds" to authorityTask.completionReceipt.requiredGateIds.sorted(),
            "requiredDependencyReceiptIds" to
                authorityTask.completionReceipt.dependencyReceiptIds.sorted(),
        ),
    ),
).value

fun Kvp001ReceiptTaskBase.configureAuthorityReceiptBoundary() {
    group = "verification"
    repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
    baseRevision.set(program.program.targetHead)
    programFingerprint.set(expectedProgramFingerprint)
    requirementFingerprint.set(program.program.requirementFingerprint.value)
    sourceDigests.set(program.program.sourceDigests.mapValues { it.value.value })
    taskId.set(authorityTask.id.value)
    redGateId.set(authorityRedGate.id)
    greenGateId.set(authorityGreenGate.id)
    completionGateId.set(authorityCompletionGate.id)
    redReceiptId.set(authorityRedGate.outputReceiptId)
    greenReceiptId.set(authorityGreenGate.outputReceiptId)
    completionReceiptId.set(authorityCompletionGate.outputReceiptId)
    redCommand.set(authorityRedGate.command)
    greenCommand.set(authorityGreenGate.command)
    completionCommand.set(authorityCompletionGate.command)
    taskInputDigest.set(authorityTaskInputDigest)
    completionInputDigest.set(authorityCompletionInputDigest)
    redProofReportPath.set(authorityNegativeReportPath)
    greenProofReportPath.set(authorityVerificationReportPath)
    redArtifactPaths.set(listOf(authorityNegativeReportPath))
    greenArtifactPaths.set(
        listOf(
            KastVfsPassiveReusedIndexProgram.authorityLedgerOutputPath.value,
            KastVfsPassiveReusedIndexProgram.authorityContradictionOutputPath.value,
            authorityVerificationReportPath,
        ),
    )
}

val recordKvp001RedReceipt = tasks.register<RecordKvp001RedReceiptTask>(
    "recordKVP001RedReceipt",
) {
    configureAuthorityReceiptBoundary()
    dependsOn("verifyKastVfsPassiveAuthorityNegative")
    receiptFile.set(authorityRedReceipt)
}

val recordKvp001GreenReceipt = tasks.register<RecordKvp001GreenReceiptTask>(
    "recordKVP001GreenReceipt",
) {
    configureAuthorityReceiptBoundary()
    dependsOn(recordKvp001RedReceipt, "verifyKastVfsPassiveAuthority")
    redReceiptFile.set(authorityRedReceipt)
    receiptFile.set(authorityGreenReceipt)
}

val deriveKvp001Completion = tasks.register<DeriveKvp001CompletionReceiptTask>(
    "deriveKVP001Completion",
) {
    configureAuthorityReceiptBoundary()
    dependsOn(recordKvp001RedReceipt, recordKvp001GreenReceipt)
    redReceiptFile.set(authorityRedReceipt)
    greenReceiptFile.set(authorityGreenReceipt)
    receiptFile.set(authorityCompletionReceipt)
}

tasks.register<VerifyKvp001CompletionReceiptTask>("verifyKVP001CompletionReceipt") {
    configureAuthorityReceiptBoundary()
    dependsOn(deriveKvp001Completion)
    redReceiptFile.set(authorityRedReceipt)
    greenReceiptFile.set(authorityGreenReceipt)
    completionReceiptFile.set(authorityCompletionReceipt)
}

program.program.tasks.sortedBy { it.id }.filterNot { it == authorityTask }.forEach { node ->
    val redReceipt = receiptDirectory.map {
        it.file("${node.red.gateId}-RECEIPT.receipt.json")
    }
    val greenReceipt = receiptDirectory.map {
        it.file("${node.green.gateId}-RECEIPT.receipt.json")
    }
    tasks.register("record${node.id.value.replace("-", "")}RedReceipt") {
        group = "verification"
        dependsOn(node.red.command.removePrefix("./gradlew ").split(' '))
        inputs.file(checkedInProgramProjectionFile)
        inputs.file(checkedInRequirementTraceFile)
        node.dependencies.taskIds.forEach { dep ->
            inputs.file(receiptDirectory.map { it.file("${dep.value}-COMPLETE.receipt.json") })
        }
        outputs.file(redReceipt)
        doLast { error("RecordGateReceiptTask must bind exact head, command, inputs, observations, artifacts, and dependency receipt digests before writing ${redReceipt.get().asFile}") }
    }
    tasks.register("record${node.id.value.replace("-", "")}GreenReceipt") {
        group = "verification"
        dependsOn(node.green.command.removePrefix("./gradlew ").split(' '))
        inputs.file(checkedInProgramProjectionFile)
        inputs.file(checkedInRequirementTraceFile)
        inputs.file(redReceipt)
        node.dependencies.taskIds.forEach { dep ->
            inputs.file(receiptDirectory.map { it.file("${dep.value}-COMPLETE.receipt.json") })
        }
        outputs.file(greenReceipt)
        doLast { error("RecordGateReceiptTask must verify all evidence before writing ${greenReceipt.get().asFile}") }
    }
}

tasks.register("deriveKastVfsPassiveState") {
    group = "verification"
    dependsOn("verifyKastVfsPassiveProjection")
    inputs.dir(receiptDirectory)
    outputs.file(layout.buildDirectory.file("reports/delivery/kast-vfs-passive-state.json"))
    doLast { error("DeriveDeliveryStateTask must admit receipts and derive state; no status field may be read") }
}

tasks.register("proveBestCaseVfsPassiveReusedIndex") {
    group = "verification"
    dependsOn("deriveKastVfsPassiveState", "ideHostedSpecificationRevalidation")
    doLast { error("Terminal task must emit BestCaseVfsPassiveReusedIndex only from the complete exact-head receipt closure") }
}
