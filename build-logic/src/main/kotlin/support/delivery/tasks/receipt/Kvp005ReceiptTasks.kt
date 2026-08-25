package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp005GateCommand { RED, GREEN }

internal data class Kvp005ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessor: AdmittedProofReceipt,
    val taskId: String,
    val redGateId: String,
    val greenGateId: String,
    val completionGateId: String,
    val redReceiptId: String,
    val greenReceiptId: String,
    val completionReceiptId: String,
    val redCommand: String,
    val greenCommand: String,
    val completionCommand: String,
    val taskInputDigest: String,
    val completionInputDigest: String,
    val proofReportPath: String,
) {
    private val predecessorDigests = mapOf(
        predecessor.receiptId.value to predecessor.digest.value,
    )

    fun negativeProof(): Kvp005ProjectionNegativeProof = when (
        val result = deriveKvp005ProjectionNegativeProof()
    ) {
        is Kvp005ProjectionNegativeProofResult.Complete -> result.proof
        is Kvp005ProjectionNegativeProofResult.Rejected -> rejectReceipt(
            "KVP-005 negative projection proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun proof(): Kvp005ProjectionProof = when (val result = deriveKvp005ProjectionProof()) {
        is Kvp005ProjectionProofResult.Complete -> result.proof
        is Kvp005ProjectionProofResult.Rejected -> rejectReceipt(
            "KVP-005 projection proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): Kvp005ProjectionProof = when (
        val result = decodeKvp005ProjectionProof(boundary.readText(proofReportPath))
    ) {
        is Kvp005ProjectionProofResult.Complete -> result.proof
        is Kvp005ProjectionProofResult.Rejected -> rejectReceipt(
            "KVP-005 projection report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp005ProjectionNegativeProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "observedFailures" to proof.failures.map { it.name }.joinToString(","),
            "rejectedCases" to proof.cases.map { it.name }.joinToString(","),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp005ProjectionProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "artifactDigestCount" to proof.projection.artifactDigests.size.toString(),
                "generationCount" to "2",
                "outcome" to "COMPLETE",
                "schemaValidArtifactCount" to ProjectionArtifactId.entries.size.toString(),
            ),
            boundary.artifactDigests(
                listOf(proofReportPath) + ProjectionArtifactId.entries.map { it.repositoryPath },
            ),
            taskId,
        )

    fun completionExpectation(red: AdmittedProofReceipt, green: AdmittedProofReceipt) =
        boundary.expectation(
            completionReceiptId,
            completionGateId,
            completionCommand,
            completionInputDigest,
            predecessorDigests + mapOf(
                red.receiptId.value to red.digest.value,
                green.receiptId.value to green.digest.value,
            ),
            mapOf("admittedGateReceiptCount" to "2", "outcome" to "COMPLETE"),
            emptyMap(),
            taskId,
        )
}

abstract class Kvp005ReceiptTaskBase : Kvp004ReceiptTaskBase() {
    @get:Input abstract val projectionTaskId: Property<String>
    @get:Input abstract val projectionRedGateId: Property<String>
    @get:Input abstract val projectionGreenGateId: Property<String>
    @get:Input abstract val projectionCompletionGateId: Property<String>
    @get:Input abstract val projectionRedReceiptId: Property<String>
    @get:Input abstract val projectionGreenReceiptId: Property<String>
    @get:Input abstract val projectionCompletionReceiptId: Property<String>
    @get:Input abstract val projectionRedCommand: Property<String>
    @get:Input abstract val projectionGreenCommand: Property<String>
    @get:Input abstract val projectionCompletionCommand: Property<String>
    @get:Input abstract val projectionTaskInputDigest: Property<String>
    @get:Input abstract val projectionCompletionInputDigest: Property<String>
    @get:Input abstract val projectionProofReportPath: Property<String>
    @get:InputFile abstract val programRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val programGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val programProofReportFile: RegularFileProperty
    @get:InputFile abstract val programCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-005 command plus closed gate identity -> successful process.
     * Establishes exact command equality and a zero exit status. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runProjectionGate(command: String, gate: Kvp005GateCommand) {
        val expected = when (gate) {
            Kvp005GateCommand.RED -> "./gradlew verifyKastVfsPassiveProjectionNegative"
            Kvp005GateCommand.GREEN ->
                "./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection"
        }
        if (command != expected) {
            rejectReceipt("KVP-005 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        val arguments = when (gate) {
            Kvp005GateCommand.RED -> listOf("verifyKastVfsPassiveProjectionNegative")
            Kvp005GateCommand.GREEN -> listOf(
                "generateKastVfsPassiveProjection",
                "verifyKastVfsPassiveProjection",
            )
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + arguments)
        }
    }

    /**
     * Proof transition: configured KVP-005 inputs plus `AuthorityGitRevision` ->
     * `Kvp005ReceiptContexts`.
     *
     * Establishes direct admission of KVP-004 completion and its transitive closure. Expected
     * receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun projectionContexts(head: AuthorityGitRevision): Kvp005ReceiptContexts {
        val program = programContexts(head)
        val proof = program.reportProof()
        val red = program.boundary.admit(
            programRedReceiptFile.get().asFile.toPath(),
            program.redExpectation(proof),
        )
        val green = program.boundary.admit(
            programGreenReceiptFile.get().asFile.toPath(),
            program.greenExpectation(red, proof),
        )
        val completion = program.boundary.admit(
            programCompletionReceiptFile.get().asFile.toPath(),
            program.completionExpectation(red, green),
        )
        return Kvp005ReceiptContexts(
            program.boundary,
            completion,
            projectionTaskId.get(),
            projectionRedGateId.get(),
            projectionGreenGateId.get(),
            projectionCompletionGateId.get(),
            projectionRedReceiptId.get(),
            projectionGreenReceiptId.get(),
            projectionCompletionReceiptId.get(),
            projectionRedCommand.get(),
            projectionGreenCommand.get(),
            projectionCompletionCommand.get(),
            projectionTaskInputDigest.get(),
            projectionCompletionInputDigest.get(),
            projectionProofReportPath.get(),
        )
    }
}
