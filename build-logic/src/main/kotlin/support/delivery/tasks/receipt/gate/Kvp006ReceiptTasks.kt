package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp006GateCommand { RED, GREEN }

internal data class Kvp006ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessors: List<AdmittedProofReceipt>,
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
    val negativeReportPath: String,
    val proofReportPath: String,
) {
    private val predecessorDigests = predecessors.associate {
        it.receiptId.value to it.digest.value
    }

    fun negativeProof(): Kvp006GateGraphNegativeProof = when (
        val result = decodeKvp006GateGraphNegativeProof(boundary.readText(negativeReportPath))
    ) {
        is Kvp006GateGraphNegativeProofResult.Complete -> result.proof
        is Kvp006GateGraphNegativeProofResult.Rejected -> rejectReceipt(
            "KVP-006 negative gate-graph report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): Kvp006GateGraphProof = when (
        val result = decodeKvp006GateGraphProof(boundary.readText(proofReportPath))
    ) {
        is Kvp006GateGraphProofResult.Complete -> result.proof
        is Kvp006GateGraphProofResult.Rejected -> rejectReceipt(
            "KVP-006 gate-graph report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp006GateGraphNegativeProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "observedFailures" to proof.failures.joinToString(",") { it.name },
            "outcome" to "COMPLETE",
            "rejectedCases" to proof.cases.joinToString(",") { it.name },
        ),
        boundary.artifactDigests(listOf(negativeReportPath)),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp006GateGraphProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "completionGateCount" to proof.completionGateCount.toString(),
                "gateCount" to proof.gateCount.toString(),
                "greenGateCount" to proof.greenGateCount.toString(),
                "outcome" to "COMPLETE",
                "redGateCount" to proof.redGateCount.toString(),
                "registeredTaskCount" to proof.registeredTasks.size.toString(),
                "uniqueReceiptOutputCount" to proof.uniqueReceiptOutputCount.toString(),
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
            mapOf(
                "admittedDependencyReceiptCount" to predecessors.size.toString(),
                "admittedGateReceiptCount" to "2",
                "outcome" to "COMPLETE",
            ),
            emptyMap(),
            taskId,
        )
}

abstract class Kvp006ReceiptTaskBase : Kvp005ReceiptTaskBase() {
    @get:Input abstract val gateGraphTaskId: Property<String>
    @get:Input abstract val gateGraphRedGateId: Property<String>
    @get:Input abstract val gateGraphGreenGateId: Property<String>
    @get:Input abstract val gateGraphCompletionGateId: Property<String>
    @get:Input abstract val gateGraphRedReceiptId: Property<String>
    @get:Input abstract val gateGraphGreenReceiptId: Property<String>
    @get:Input abstract val gateGraphCompletionReceiptId: Property<String>
    @get:Input abstract val gateGraphRedCommand: Property<String>
    @get:Input abstract val gateGraphGreenCommand: Property<String>
    @get:Input abstract val gateGraphCompletionCommand: Property<String>
    @get:Input abstract val gateGraphTaskInputDigest: Property<String>
    @get:Input abstract val gateGraphCompletionInputDigest: Property<String>
    @get:Input abstract val gateGraphNegativeReportPath: Property<String>
    @get:Input abstract val gateGraphProofReportPath: Property<String>
    @get:InputFile abstract val projectionRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val projectionGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val projectionProofReportFile: RegularFileProperty
    @get:InputFile abstract val projectionCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-006 command plus closed gate identity -> successful process.
     * Establishes exact command equality and a zero exit status. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runGateGraphGate(command: String, gate: Kvp006GateCommand) {
        val expected = when (gate) {
            Kvp006GateCommand.RED -> "./gradlew verifyKastVfsPassiveGateGraphNegative"
            Kvp006GateCommand.GREEN -> "./gradlew verifyKastVfsPassiveGateGraph"
        }
        if (command != expected) {
            rejectReceipt("KVP-006 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        val taskName = when (gate) {
            Kvp006GateCommand.RED -> "verifyKastVfsPassiveGateGraphNegative"
            Kvp006GateCommand.GREEN -> "verifyKastVfsPassiveGateGraph"
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", taskName)
        }
    }

    /**
     * Proof transition: configured KVP-006 inputs plus `AuthorityGitRevision` ->
     * `Kvp006ReceiptContexts`.
     *
     * Establishes direct admission of KVP-003 and KVP-005 completion and their transitive closures.
     * Expected receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun gateGraphContexts(head: AuthorityGitRevision): Kvp006ReceiptContexts {
        val graph = graphContexts(head)
        val graphProof = graph.reportProof()
        val graphRed = graph.boundary.admit(
            graphRedReceiptFile.get().asFile.toPath(),
            graph.redExpectation(graphProof),
        )
        val graphGreen = graph.boundary.admit(
            graphGreenReceiptFile.get().asFile.toPath(),
            graph.greenExpectation(graphRed, graphProof),
        )
        val graphCompletion = graph.boundary.admit(
            graphCompletionReceiptFile.get().asFile.toPath(),
            graph.completionExpectation(graphRed, graphGreen),
        )
        val projection = projectionContexts(head)
        val projectionNegative = projection.negativeProof()
        val projectionProof = projection.reportProof()
        val projectionRed = projection.boundary.admit(
            projectionRedReceiptFile.get().asFile.toPath(),
            projection.redExpectation(projectionNegative),
        )
        val projectionGreen = projection.boundary.admit(
            projectionGreenReceiptFile.get().asFile.toPath(),
            projection.greenExpectation(projectionRed, projectionProof),
        )
        val projectionCompletion = projection.boundary.admit(
            projectionCompletionReceiptFile.get().asFile.toPath(),
            projection.completionExpectation(projectionRed, projectionGreen),
        )
        return Kvp006ReceiptContexts(
            graph.boundary,
            listOf(graphCompletion, projectionCompletion),
            gateGraphTaskId.get(),
            gateGraphRedGateId.get(),
            gateGraphGreenGateId.get(),
            gateGraphCompletionGateId.get(),
            gateGraphRedReceiptId.get(),
            gateGraphGreenReceiptId.get(),
            gateGraphCompletionReceiptId.get(),
            gateGraphRedCommand.get(),
            gateGraphGreenCommand.get(),
            gateGraphCompletionCommand.get(),
            gateGraphTaskInputDigest.get(),
            gateGraphCompletionInputDigest.get(),
            gateGraphNegativeReportPath.get(),
            gateGraphProofReportPath.get(),
        )
    }
}
