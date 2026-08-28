package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp008GateCommand { RED, GREEN }

internal data class Kvp008ReceiptContexts(
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
    val exactHead: AuthorityGitRevision,
) {
    private val predecessorDigests = mapOf(predecessor.receiptId.value to predecessor.digest.value)

    fun proof(): Kvp008DeliveryStateProof = when (
        val result = deriveKvp008DeliveryStateProof(
            KastVfsPassiveReusedIndexProgram.validated,
            exactHead,
        )
    ) {
        is Kvp008DeliveryStateProofResult.Complete -> result.proof
        is Kvp008DeliveryStateProofResult.Rejected -> rejectReceipt(
            "KVP-008 delivery state proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): Kvp008DeliveryStateProof = when (
        val result = decodeKvp008DeliveryStateProof(
            boundary.readText(proofReportPath),
            KastVfsPassiveReusedIndexProgram.validated,
            exactHead,
        )
    ) {
        is Kvp008DeliveryStateReportResult.Complete -> result.proof
        is Kvp008DeliveryStateReportResult.Rejected -> rejectReceipt(
            "KVP-008 delivery state report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp008DeliveryStateProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "duplicateInvalidation" to proof.duplicateInvalidation.name,
            "initialBlockedTaskCount" to proof.initialBlockedTaskCount.toString(),
            "initialTerminal" to proof.initialTerminal.name,
            "staleInvalidation" to proof.staleInvalidation.name,
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp008DeliveryStateProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "completeProvenTaskCount" to proof.completeProvenTaskCount.toString(),
                "completeTerminalTaskId" to proof.completeTerminalTaskId.value,
                "partialProvenTaskCount" to proof.partialProvenTaskCount.toString(),
                "passedRequirementCount" to proof.passedRequirementCount.toString(),
                "schemaVersion" to "1",
            ),
            boundary.artifactDigests(listOf(proofReportPath)),
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
            mapOf("admittedGateReceiptCount" to "2", "derivation" to "RECEIPT_ONLY"),
            emptyMap(),
            taskId,
        )
}

abstract class Kvp008ReceiptTaskBase : Kvp007ReceiptTaskBase() {
    @get:Input abstract val deliveryStateTaskId: Property<String>
    @get:Input abstract val deliveryStateRedGateId: Property<String>
    @get:Input abstract val deliveryStateGreenGateId: Property<String>
    @get:Input abstract val deliveryStateCompletionGateId: Property<String>
    @get:Input abstract val deliveryStateRedReceiptId: Property<String>
    @get:Input abstract val deliveryStateGreenReceiptId: Property<String>
    @get:Input abstract val deliveryStateCompletionReceiptId: Property<String>
    @get:Input abstract val deliveryStateRedCommand: Property<String>
    @get:Input abstract val deliveryStateGreenCommand: Property<String>
    @get:Input abstract val deliveryStateCompletionCommand: Property<String>
    @get:Input abstract val deliveryStateTaskInputDigest: Property<String>
    @get:Input abstract val deliveryStateCompletionInputDigest: Property<String>
    @get:Input abstract val deliveryStateProofReportPath: Property<String>
    @get:InputFile abstract val deliveryProofRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val deliveryProofGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val deliveryProofReportFile: RegularFileProperty
    @get:InputFile abstract val deliveryProofCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-008 command plus closed gate identity -> successful process.
     * Establishes exact command equality and zero exit. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runDeliveryStateGate(command: String, gate: Kvp008GateCommand) {
        val filter = when (gate) {
            Kvp008GateCommand.RED -> "*DeliveryStateNegativeTest"
            Kvp008GateCommand.GREEN -> "*DeliveryStateTest"
        }
        val expected = "./gradlew :build-logic:test --tests \"$filter\""
        if (command != expected) {
            rejectReceipt("KVP-008 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", ":build-logic:test", "--tests", filter)
        }
    }

    /**
     * Proof transition: configured KVP-008 inputs plus `AuthorityGitRevision` ->
     * `Kvp008ReceiptContexts`.
     * Establishes direct admission of KVP-007 completion and its transitive closure. Expected
     * receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun deliveryStateContexts(head: AuthorityGitRevision): Kvp008ReceiptContexts {
        val deliveryProof = deliveryProofContexts(head)
        val proof = deliveryProof.reportProof()
        val red = deliveryProof.boundary.admit(
            deliveryProofRedReceiptFile.get().asFile.toPath(),
            deliveryProof.redExpectation(proof),
        )
        val green = deliveryProof.boundary.admit(
            deliveryProofGreenReceiptFile.get().asFile.toPath(),
            deliveryProof.greenExpectation(red, proof),
        )
        val completion = deliveryProof.boundary.admit(
            deliveryProofCompletionReceiptFile.get().asFile.toPath(),
            deliveryProof.completionExpectation(red, green),
        )
        return Kvp008ReceiptContexts(
            deliveryProof.boundary,
            completion,
            deliveryStateTaskId.get(),
            deliveryStateRedGateId.get(),
            deliveryStateGreenGateId.get(),
            deliveryStateCompletionGateId.get(),
            deliveryStateRedReceiptId.get(),
            deliveryStateGreenReceiptId.get(),
            deliveryStateCompletionReceiptId.get(),
            deliveryStateRedCommand.get(),
            deliveryStateGreenCommand.get(),
            deliveryStateCompletionCommand.get(),
            deliveryStateTaskInputDigest.get(),
            deliveryStateCompletionInputDigest.get(),
            deliveryStateProofReportPath.get(),
            head,
        )
    }
}
