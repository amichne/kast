package kast

import support.delivery.DeriveKvp001CompletionReceiptTask
import support.delivery.DeriveKvp002CompletionReceiptTask
import support.delivery.DeriveKvp003CompletionReceiptTask
import support.delivery.GenerateDeliveryProjectionsTask
import support.delivery.GenerateKastVfsPassiveAuthorityTask
import support.delivery.KastVfsPassiveReusedIndexProgram
import support.delivery.Kvp001ReceiptTaskBase
import support.delivery.RecordKvp001GreenReceiptTask
import support.delivery.RecordKvp001RedReceiptTask
import support.delivery.Kvp002ReceiptTaskBase
import support.delivery.Kvp003ReceiptTaskBase
import support.delivery.RecordKvp002GreenReceiptTask
import support.delivery.RecordKvp002RedReceiptTask
import support.delivery.RecordKvp003GreenReceiptTask
import support.delivery.RecordKvp003RedReceiptTask
import support.delivery.VerifyDeliveryProjectionsTask
import support.delivery.VerifyKastVfsPassiveAuthorityNegativeTask
import support.delivery.VerifyKastVfsPassiveAuthorityTask
import support.delivery.VerifyKvp001CompletionReceiptTask
import support.delivery.VerifyKvp002CompletionReceiptTask
import support.delivery.VerifyKvp003CompletionReceiptTask
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

val typeModelTask = program.program.tasks.single { it.id.value == "KVP-002" }
val typeModelRedGate = program.program.gates.single { it.id == typeModelTask.red.gateId }
val typeModelGreenGate = program.program.gates.single { it.id == typeModelTask.green.gateId }
val typeModelCompletionGate = program.program.gates.single {
    it.taskId == typeModelTask.id && it.kind.name == "TASK_COMPLETION"
}
val typeModelRedReceipt = receiptDirectory.map {
    it.file("${typeModelRedGate.outputReceiptId}.receipt.json")
}
val typeModelGreenReceipt = receiptDirectory.map {
    it.file("${typeModelGreenGate.outputReceiptId}.receipt.json")
}
val typeModelCompletionReceipt = layout.projectDirectory.file(
    typeModelTask.completionReceipt.outputPath,
)
val typeModelProofReport = layout.projectDirectory.file(typeModelTask.outputs.single().path)
val typeModelTaskInputDigest = sha256(canonicalJson(typeModelTask.inputs)).value
val typeModelCompletionInputDigest = sha256(
    canonicalJson(
        mapOf(
            "receiptId" to typeModelTask.completionReceipt.receiptId,
            "requiredGateIds" to typeModelTask.completionReceipt.requiredGateIds.sorted(),
            "requiredDependencyReceiptIds" to
                typeModelTask.completionReceipt.dependencyReceiptIds.sorted(),
        ),
    ),
).value

fun Kvp002ReceiptTaskBase.configureTypeModelReceiptBoundary() {
    configureAuthorityReceiptBoundary()
    candidateTaskId.set(typeModelTask.id.value)
    candidateRedGateId.set(typeModelRedGate.id)
    candidateGreenGateId.set(typeModelGreenGate.id)
    candidateCompletionGateId.set(typeModelCompletionGate.id)
    candidateRedReceiptId.set(typeModelRedGate.outputReceiptId)
    candidateGreenReceiptId.set(typeModelGreenGate.outputReceiptId)
    candidateCompletionReceiptId.set(typeModelCompletionGate.outputReceiptId)
    candidateRedCommand.set(typeModelRedGate.command)
    candidateGreenCommand.set(typeModelGreenGate.command)
    candidateCompletionCommand.set(typeModelCompletionGate.command)
    candidateTaskInputDigest.set(typeModelTaskInputDigest)
    candidateCompletionInputDigest.set(typeModelCompletionInputDigest)
    proofReportPath.set(typeModelTask.outputs.single().path)
    authorityRedReceiptFile.set(authorityRedReceipt)
    authorityGreenReceiptFile.set(authorityGreenReceipt)
    authorityCompletionReceiptFile.set(authorityCompletionReceipt)
}

val recordKvp002RedReceipt = tasks.register<RecordKvp002RedReceiptTask>(
    "recordKVP002RedReceipt",
) {
    configureTypeModelReceiptBoundary()
    dependsOn("verifyKVP001CompletionReceipt")
    receiptFile.set(typeModelRedReceipt)
}

val recordKvp002GreenReceipt = tasks.register<RecordKvp002GreenReceiptTask>(
    "recordKVP002GreenReceipt",
) {
    configureTypeModelReceiptBoundary()
    dependsOn(recordKvp002RedReceipt)
    redReceiptFile.set(typeModelRedReceipt)
    proofReportFile.set(typeModelProofReport)
    receiptFile.set(typeModelGreenReceipt)
}

val deriveKvp002Completion = tasks.register<DeriveKvp002CompletionReceiptTask>(
    "deriveKVP002Completion",
) {
    configureTypeModelReceiptBoundary()
    dependsOn(recordKvp002GreenReceipt)
    redReceiptFile.set(typeModelRedReceipt)
    greenReceiptFile.set(typeModelGreenReceipt)
    proofReportFile.set(typeModelProofReport)
    receiptFile.set(typeModelCompletionReceipt)
}

tasks.register<VerifyKvp002CompletionReceiptTask>("verifyKVP002CompletionReceipt") {
    configureTypeModelReceiptBoundary()
    dependsOn(deriveKvp002Completion)
    redReceiptFile.set(typeModelRedReceipt)
    greenReceiptFile.set(typeModelGreenReceipt)
    proofReportFile.set(typeModelProofReport)
    completionReceiptFile.set(typeModelCompletionReceipt)
}

val graphTask = program.program.tasks.single { it.id.value == "KVP-003" }
val graphRedGate = program.program.gates.single { it.id == graphTask.red.gateId }
val graphGreenGate = program.program.gates.single { it.id == graphTask.green.gateId }
val graphCompletionGate = program.program.gates.single {
    it.taskId == graphTask.id && it.kind.name == "TASK_COMPLETION"
}
val graphRedReceipt = receiptDirectory.map { it.file("${graphRedGate.outputReceiptId}.receipt.json") }
val graphGreenReceipt = receiptDirectory.map { it.file("${graphGreenGate.outputReceiptId}.receipt.json") }
val graphCompletionReceipt = layout.projectDirectory.file(graphTask.completionReceipt.outputPath)
val graphProofReport = layout.projectDirectory.file(graphTask.outputs.single().path)
val expectedGraphTaskInputDigest = sha256(canonicalJson(graphTask.inputs)).value
val expectedGraphCompletionInputDigest = sha256(canonicalJson(mapOf(
    "receiptId" to graphTask.completionReceipt.receiptId,
    "requiredGateIds" to graphTask.completionReceipt.requiredGateIds.sorted(),
    "requiredDependencyReceiptIds" to graphTask.completionReceipt.dependencyReceiptIds.sorted(),
))).value

fun Kvp003ReceiptTaskBase.configureGraphReceiptBoundary() {
    configureTypeModelReceiptBoundary()
    graphTaskId.set(graphTask.id.value)
    graphRedGateId.set(graphRedGate.id)
    graphGreenGateId.set(graphGreenGate.id)
    graphCompletionGateId.set(graphCompletionGate.id)
    graphRedReceiptId.set(graphRedGate.outputReceiptId)
    graphGreenReceiptId.set(graphGreenGate.outputReceiptId)
    graphCompletionReceiptId.set(graphCompletionGate.outputReceiptId)
    graphRedCommand.set(graphRedGate.command)
    graphGreenCommand.set(graphGreenGate.command)
    graphCompletionCommand.set(graphCompletionGate.command)
    graphTaskInputDigest.set(expectedGraphTaskInputDigest)
    graphCompletionInputDigest.set(expectedGraphCompletionInputDigest)
    graphProofReportPath.set(graphTask.outputs.single().path)
    predecessorRedReceiptFile.set(typeModelRedReceipt)
    predecessorGreenReceiptFile.set(typeModelGreenReceipt)
    predecessorProofReportFile.set(typeModelProofReport)
    predecessorCompletionReceiptFile.set(typeModelCompletionReceipt)
}

val recordKvp003RedReceipt = tasks.register<RecordKvp003RedReceiptTask>("recordKVP003RedReceipt") {
    configureGraphReceiptBoundary(); dependsOn("verifyKVP002CompletionReceipt")
    receiptFile.set(graphRedReceipt)
}
val recordKvp003GreenReceipt = tasks.register<RecordKvp003GreenReceiptTask>("recordKVP003GreenReceipt") {
    configureGraphReceiptBoundary(); dependsOn(recordKvp003RedReceipt)
    redReceiptFile.set(graphRedReceipt); proofReportFile.set(graphProofReport)
    receiptFile.set(graphGreenReceipt)
}
val deriveKvp003Completion = tasks.register<DeriveKvp003CompletionReceiptTask>("deriveKVP003Completion") {
    configureGraphReceiptBoundary(); dependsOn(recordKvp003GreenReceipt)
    redReceiptFile.set(graphRedReceipt); greenReceiptFile.set(graphGreenReceipt)
    proofReportFile.set(graphProofReport); receiptFile.set(graphCompletionReceipt)
}
tasks.register<VerifyKvp003CompletionReceiptTask>("verifyKVP003CompletionReceipt") {
    configureGraphReceiptBoundary(); dependsOn(deriveKvp003Completion)
    redReceiptFile.set(graphRedReceipt); greenReceiptFile.set(graphGreenReceipt)
    proofReportFile.set(graphProofReport); completionReceiptFile.set(graphCompletionReceipt)
}

program.program.tasks.sortedBy { it.id }.filterNot {
    it == authorityTask || it == typeModelTask || it == graphTask
}.forEach { node ->
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
        doLast { error("Typed recorder required before writing ${redReceipt.get().asFile}") }
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
        doLast { error("Typed recorder required before writing ${greenReceipt.get().asFile}") }
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
