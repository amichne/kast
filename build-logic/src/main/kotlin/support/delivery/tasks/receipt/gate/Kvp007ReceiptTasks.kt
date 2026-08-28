package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp007GateCommand { RED, GREEN }

internal data class Kvp007ReceiptContexts(
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

    fun proof(): DeliveryProof = when (val result = deriveDeliveryProof()) {
        is DeliveryProofResult.Complete -> result.proof
        is DeliveryProofResult.Rejected -> rejectReceipt(
            "KVP-007 delivery proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): DeliveryProof = when (
        val result = decodeKvp007DeliveryProof(boundary.readText(proofReportPath))
    ) {
        is Kvp007DeliveryProofReportResult.Complete -> result.proof
        is Kvp007DeliveryProofReportResult.Rejected -> rejectReceipt(
            "KVP-007 delivery proof report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: DeliveryProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "invalidationCount" to proof.invalidations.size.toString(),
            "invalidations" to proof.invalidations.entries.joinToString(",") {
                "${it.key.name}=${it.value.name}"
            },
            "outcome" to "COMPLETE",
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: DeliveryProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "invalidationCount" to proof.invalidations.size.toString(),
                "outcome" to "COMPLETE",
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
            mapOf("admittedGateReceiptCount" to "2", "outcome" to "COMPLETE"),
            emptyMap(),
            taskId,
        )
}

abstract class Kvp007ReceiptTaskBase : Kvp006ReceiptTaskBase() {
    @get:Input abstract val deliveryProofTaskId: Property<String>
    @get:Input abstract val deliveryProofRedGateId: Property<String>
    @get:Input abstract val deliveryProofGreenGateId: Property<String>
    @get:Input abstract val deliveryProofCompletionGateId: Property<String>
    @get:Input abstract val deliveryProofRedReceiptId: Property<String>
    @get:Input abstract val deliveryProofGreenReceiptId: Property<String>
    @get:Input abstract val deliveryProofCompletionReceiptId: Property<String>
    @get:Input abstract val deliveryProofRedCommand: Property<String>
    @get:Input abstract val deliveryProofGreenCommand: Property<String>
    @get:Input abstract val deliveryProofCompletionCommand: Property<String>
    @get:Input abstract val deliveryProofTaskInputDigest: Property<String>
    @get:Input abstract val deliveryProofCompletionInputDigest: Property<String>
    @get:Input abstract val deliveryProofReportPath: Property<String>
    @get:InputFile abstract val gateGraphRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val gateGraphGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val gateGraphProofReportFile: RegularFileProperty
    @get:InputFile abstract val gateGraphCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-007 command plus closed gate identity -> successful process.
     * Establishes exact command equality and zero exit. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runDeliveryProofGate(command: String, gate: Kvp007GateCommand) {
        val filter = when (gate) {
            Kvp007GateCommand.RED -> "*DeliveryProofNegativeTest"
            Kvp007GateCommand.GREEN -> "*DeliveryProofTest"
        }
        val expected = "./gradlew :build-logic:test --tests \"$filter\""
        if (command != expected) {
            rejectReceipt("KVP-007 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", ":build-logic:test", "--tests", filter)
        }
    }

    /**
     * Proof transition: configured KVP-007 inputs plus `AuthorityGitRevision` ->
     * `Kvp007ReceiptContexts`.
     * Establishes direct admission of KVP-006 completion and its transitive closure. Expected
     * receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun deliveryProofContexts(head: AuthorityGitRevision): Kvp007ReceiptContexts {
        val gateGraph = gateGraphContexts(head)
        val negativeProof = gateGraph.negativeProof()
        val proof = gateGraph.reportProof()
        val red = gateGraph.boundary.admit(
            gateGraphRedReceiptFile.get().asFile.toPath(),
            gateGraph.redExpectation(negativeProof),
        )
        val green = gateGraph.boundary.admit(
            gateGraphGreenReceiptFile.get().asFile.toPath(),
            gateGraph.greenExpectation(red, proof),
        )
        val completion = gateGraph.boundary.admit(
            gateGraphCompletionReceiptFile.get().asFile.toPath(),
            gateGraph.completionExpectation(red, green),
        )
        return Kvp007ReceiptContexts(
            gateGraph.boundary,
            completion,
            deliveryProofTaskId.get(),
            deliveryProofRedGateId.get(),
            deliveryProofGreenGateId.get(),
            deliveryProofCompletionGateId.get(),
            deliveryProofRedReceiptId.get(),
            deliveryProofGreenReceiptId.get(),
            deliveryProofCompletionReceiptId.get(),
            deliveryProofRedCommand.get(),
            deliveryProofGreenCommand.get(),
            deliveryProofCompletionCommand.get(),
            deliveryProofTaskInputDigest.get(),
            deliveryProofCompletionInputDigest.get(),
            deliveryProofReportPath.get(),
        )
    }
}
