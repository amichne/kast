package kast

import support.delivery.GenerateDeliveryProjectionsTask
import support.delivery.GenerateKastVfsPassiveAuthorityTask
import support.delivery.KastVfsPassiveReusedIndexProgram
import support.delivery.DeterministicProgramProjection
import support.delivery.ProjectionArtifactId
import support.delivery.VerifyDeliveryProjectionsTask
import support.delivery.VerifyDeliveryProjectionsNegativeTask
import support.delivery.VerifyDeliveryGateGraphNegativeTask
import support.delivery.VerifyDeliveryGateGraphTask
import support.delivery.VerifyKastVfsPassiveAuthorityNegativeTask
import support.delivery.VerifyKastVfsPassiveAuthorityTask
import support.delivery.registerDeliveryReceiptProgression
import support.delivery.registerKvp011AtomicProof
import support.delivery.registerKvp025AtomicProof
import support.delivery.registerKvp026AtomicProof
import support.delivery.registerKvp027AtomicProof
import support.delivery.registerKvp028AtomicProof
import support.delivery.registerKvp029AtomicProof
import support.delivery.registerKvp030AtomicProof
import support.delivery.registerKvp031AtomicProof
import support.delivery.registerKvp032AtomicProof
import support.delivery.GradleGateTaskNameRefinement
import support.delivery.TaskProofProtocol
import support.delivery.refineGradleGateTaskName

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
val receiptDirectory = layout.buildDirectory.dir("reports/delivery/receipts")
private val firstProjectionGeneration = DeterministicProgramProjection.generate(program)
private val secondProjectionGeneration = DeterministicProgramProjection.generate(program)
private val projectionArtifactFiles = ProjectionArtifactId.entries.map {
    layout.projectDirectory.file(it.repositoryPath)
}

tasks.register<GenerateDeliveryProjectionsTask>("generateKastVfsPassiveProjection") {
    group = "verification"
    artifactContents.set(firstProjectionGeneration.contentsByPath())
    artifactFiles.from(projectionArtifactFiles)
    repositoryRoot.set(layout.projectDirectory)
}

tasks.register<VerifyDeliveryProjectionsTask>("verifyKastVfsPassiveProjection") {
    group = "verification"
    mustRunAfter("generateKastVfsPassiveProjection")
    firstGenerationContents.set(firstProjectionGeneration.contentsByPath())
    secondGenerationContents.set(secondProjectionGeneration.contentsByPath())
    artifactFiles.from(projectionArtifactFiles)
    reportFile.set(layout.buildDirectory.file("reports/delivery/KVP-005-projection.json"))
    repositoryRoot.set(layout.projectDirectory)
}

tasks.register<VerifyDeliveryProjectionsNegativeTask>(
    "verifyKastVfsPassiveProjectionNegative",
) {
    group = "verification"
    canonicalGenerationContents.set(firstProjectionGeneration.contentsByPath())
    reportFile.set(layout.buildDirectory.file("reports/delivery/KVP-005-projection-negative.json"))
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

val typedReceiptTaskIds = registerDeliveryReceiptProgression() +
    registerKvp011AtomicProof() + registerKvp025AtomicProof() + registerKvp026AtomicProof() +
    registerKvp027AtomicProof() +
    registerKvp028AtomicProof() + registerKvp029AtomicProof() + registerKvp030AtomicProof() +
        registerKvp031AtomicProof() + registerKvp032AtomicProof()
program.program.tasks.sortedBy { it.id }.filterNot {
    it.id in typedReceiptTaskIds
}.forEach { node ->
    when (node.proof) {
        is TaskProofProtocol.Legacy -> {
            val redReceipt = receiptDirectory.map {
                it.file("${node.red.gateId}-RECEIPT.receipt.json")
            }
            val greenReceipt = receiptDirectory.map {
                it.file("${node.green.gateId}-RECEIPT.receipt.json")
            }
            val completionReceipt = receiptDirectory.map {
                it.file("${node.id.value}-COMPLETE.receipt.json")
            }
            tasks.register("record${node.id.value.replace("-", "")}RedReceipt") {
                group = "verification"
                dependsOn(node.red.command.removePrefix("./gradlew ").split(' '))
                outputs.file(redReceipt)
                doLast { error("Typed recorder required before writing ${redReceipt.get().asFile}") }
            }
            tasks.register("record${node.id.value.replace("-", "")}GreenReceipt") {
                group = "verification"
                dependsOn(node.green.command.removePrefix("./gradlew ").split(' '))
                inputs.file(redReceipt)
                outputs.file(greenReceipt)
                doLast { error("Typed recorder required before writing ${greenReceipt.get().asFile}") }
            }
            tasks.register("derive${node.id.value.replace("-", "")}Completion") {
                group = "verification"
                inputs.file(redReceipt)
                inputs.file(greenReceipt)
                outputs.file(completionReceipt)
                doLast {
                    error("Typed completion deriver required before writing ${completionReceipt.get().asFile}")
                }
            }
        }
        is TaskProofProtocol.Atomic -> tasks.register(
            "prove${node.id.value.replace("-", "")}",
        ) {
            group = "verification"
            doLast { error("Typed atomic prover required for ${node.id.value}") }
        }
    }
}

private val registeredGateTaskNames = program.program.gates.map { gate ->
    when (val refined = refineGradleGateTaskName(gate)) {
        is GradleGateTaskNameRefinement.Refined -> refined.name.value
        is GradleGateTaskNameRefinement.Rejected -> error(
            "unsupported gate registration: ${refined.failure}",
        )
    }
}.sorted()
registeredGateTaskNames.forEach { tasks.named(it) }

tasks.register<VerifyDeliveryGateGraphNegativeTask>("verifyKastVfsPassiveGateGraphNegative") {
    group = "verification"
    registeredTaskNames.set(registeredGateTaskNames)
    reportFile.set(layout.buildDirectory.file("reports/delivery/KVP-006-gradle-gates-negative.json"))
}

tasks.register<VerifyDeliveryGateGraphTask>("verifyKastVfsPassiveGateGraph") {
    group = "verification"
    registeredTaskNames.set(registeredGateTaskNames)
    reportFile.set(layout.buildDirectory.file("reports/delivery/KVP-006-gradle-gates.json"))
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
