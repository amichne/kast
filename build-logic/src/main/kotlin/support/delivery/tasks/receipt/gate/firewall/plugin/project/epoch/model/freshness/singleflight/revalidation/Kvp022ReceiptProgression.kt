package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles

internal enum class Kvp022GateOutcome { COMPLETE }

internal class Kvp022ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessorDigests: Map<String, String>,
    private val reportPredecessor: Kvp022ReportPredecessor,
    private val taskId: String,
    private val redGateId: String,
    private val greenGateId: String,
    private val completionGateId: String,
    private val redReceiptId: String,
    private val greenReceiptId: String,
    private val completionReceiptId: String,
    private val redCommand: String,
    private val greenCommand: String,
    private val completionCommand: String,
    private val taskInputDigest: String,
    private val completionInputDigest: String,
    private val proofReportPath: String,
    private val redGateEvidencePath: String,
    private val greenGateEvidencePath: String,
    private val redArtifactPaths: List<String>,
    private val greenArtifactPaths: List<String>,
) {
    companion object {
        /**
         * Proof transition: configured KVP-022 values plus `Kvp022DependencyContexts` ->
         * `Kvp022ReceiptContexts`.
         *
         * Preserves the exact KVP-021 proof and snapshots every Gradle collection before receipt
         * derivation.
         */
        fun capture(
            dependencies: Kvp022DependencyContexts,
            taskId: String,
            redGateId: String,
            greenGateId: String,
            completionGateId: String,
            redReceiptId: String,
            greenReceiptId: String,
            completionReceiptId: String,
            redCommand: String,
            greenCommand: String,
            completionCommand: String,
            taskInputDigest: String,
            completionInputDigest: String,
            proofReportPath: String,
            redGateEvidencePath: String,
            greenGateEvidencePath: String,
            redArtifactPaths: List<String>,
            greenArtifactPaths: List<String>,
        ) = Kvp022ReceiptContexts(
            dependencies.boundary,
            dependencies.digestMap(),
            dependencies.reportPredecessor,
            taskId,
            redGateId,
            greenGateId,
            completionGateId,
            redReceiptId,
            greenReceiptId,
            completionReceiptId,
            redCommand,
            greenCommand,
            completionCommand,
            taskInputDigest,
            completionInputDigest,
            proofReportPath,
            redGateEvidencePath,
            greenGateEvidencePath,
            redArtifactPaths.toList(),
            greenArtifactPaths.toList(),
        )
    }

    /**
     * Proof transition: configured report bytes -> `AdmittedKvp022EpochRevalidationReport`.
     * Establishes canonical KVP-022 product evidence at the exact predecessor digest.
     */
    fun reportProof(): AdmittedKvp022EpochRevalidationReport = when (
        val result = AdmittedKvp022EpochRevalidationReport.admit(
            boundary.readText(proofReportPath),
            reportPredecessor,
        )
    ) {
        is Kvp022EpochRevalidationReportAdmission.Admitted -> result.report
        is Kvp022EpochRevalidationReportAdmission.Rejected -> rejectReceipt(
            "KVP-022 epoch-revalidation report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redGateProof(): AdmittedKvp022GateExecution = gateProof(
        redGateEvidencePath,
        Kvp022GateCommand.RED,
    )

    fun greenGateProof(): AdmittedKvp022GateExecution = gateProof(
        greenGateEvidencePath,
        Kvp022GateCommand.GREEN,
    )

    fun redExpectation(gate: AdmittedKvp022GateExecution): ProofReceiptExpectation {
        val artifacts = boundary.artifactDigests(redArtifactPaths).toMutableMap()
        artifacts[redGateEvidencePath] = sha256Bytes(
            gate.canonicalDocument.toByteArray(Charsets.UTF_8),
        )
        return boundary.expectation(
            redReceiptId,
            redGateId,
            redCommand,
            taskInputDigest,
            predecessorDigests,
            mapOf(
                "canonicalTaskPath" to Kvp022GateCommand.RED.canonicalTaskPath,
                "dedicatedTaskPath" to Kvp022GateCommand.RED.dedicatedTaskPath,
                "outcome" to Kvp022GateOutcome.COMPLETE.name,
                "selectorPattern" to Kvp022GateCommand.RED.selectorPattern,
            ),
            artifacts,
            taskId,
        )
    }

    fun greenExpectation(
        red: AdmittedProofReceipt,
        gate: AdmittedKvp022GateExecution,
    ): ProofReceiptExpectation {
        val report = reportProof()
        val artifacts = boundary.artifactDigests(greenArtifactPaths).toMutableMap()
        artifacts[proofReportPath] = sha256Bytes(
            report.canonicalDocument.toByteArray(Charsets.UTF_8),
        )
        artifacts[greenGateEvidencePath] = sha256Bytes(
            gate.canonicalDocument.toByteArray(Charsets.UTF_8),
        )
        return boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "afterObservationCount" to report.afterObservationCount.toString(),
                "authorities" to report.authorities,
                "beforeObservationCount" to report.beforeObservationCount.toString(),
                "canonicalTaskPath" to Kvp022GateCommand.GREEN.canonicalTaskPath,
                "dedicatedTaskPath" to Kvp022GateCommand.GREEN.dedicatedTaskPath,
                "epochObservationCountPerCompletedRead" to
                    report.epochObservationCountPerCompletedRead.toString(),
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "outcome" to Kvp022GateOutcome.COMPLETE.name,
                "phaseFailureCount" to report.phaseFailureCount.toString(),
                "phaseFailures" to report.phaseFailures,
                "priorEpochReuseCount" to report.priorEpochReuseCount.toString(),
                "publicInterface" to report.publicInterface,
                "relationDecisionCount" to report.relationDecisionCount.toString(),
                "relationDecisions" to report.relationDecisions,
                "retryCount" to report.retryCount.toString(),
                "selectorPattern" to Kvp022GateCommand.GREEN.selectorPattern,
                "semanticExecutionLimitPerAttempt" to
                    report.semanticExecutionLimitPerAttempt.toString(),
            ),
            artifacts,
            taskId,
        )
    }

    fun completionExpectation(
        red: AdmittedProofReceipt,
        green: AdmittedProofReceipt,
    ) = boundary.expectation(
        completionReceiptId,
        completionGateId,
        completionCommand,
        completionInputDigest,
        predecessorDigests + mapOf(
            red.receiptId.value to red.digest.value,
            green.receiptId.value to green.digest.value,
        ),
        mapOf(
            "admittedDependencyReceiptCount" to "1",
            "admittedGateReceiptCount" to "2",
        ),
        emptyMap(),
        taskId,
    )

    private fun gateProof(
        path: String,
        command: Kvp022GateCommand,
    ): AdmittedKvp022GateExecution = when (val result = AdmittedKvp022GateExecution.admit(
        boundary.readText(path),
        command,
        AuthorityGitRevision(boundary.exactHead),
        Kvp022GateExecutionPhase.COMPLETE,
    )) {
        is Kvp022GateExecutionAdmission.Admitted -> result.execution
        is Kvp022GateExecutionAdmission.Rejected -> rejectReceipt(
            "KVP-022 ${command.gateId} execution evidence",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.toString(),
        )
    }
}

abstract class Kvp022ReceiptTaskBase : Kvp022DependencyReceiptTaskBase() {
    @get:Input abstract val revalidationTaskId: Property<String>
    @get:Input abstract val revalidationRedGateId: Property<String>
    @get:Input abstract val revalidationGreenGateId: Property<String>
    @get:Input abstract val revalidationCompletionGateId: Property<String>
    @get:Input abstract val revalidationRedReceiptId: Property<String>
    @get:Input abstract val revalidationGreenReceiptId: Property<String>
    @get:Input abstract val revalidationCompletionReceiptId: Property<String>
    @get:Input abstract val revalidationRedCommand: Property<String>
    @get:Input abstract val revalidationGreenCommand: Property<String>
    @get:Input abstract val revalidationCompletionCommand: Property<String>
    @get:Input abstract val revalidationTaskInputDigest: Property<String>
    @get:Input abstract val revalidationCompletionInputDigest: Property<String>
    @get:Input abstract val revalidationProofReportPath: Property<String>
    @get:Input abstract val revalidationRedGateEvidencePath: Property<String>
    @get:Input abstract val revalidationGreenGateEvidencePath: Property<String>
    @get:Input abstract val revalidationRedArtifactPaths: ListProperty<String>
    @get:Input abstract val revalidationGreenArtifactPaths: ListProperty<String>

    @get:InputFiles abstract val revalidationRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val revalidationGreenArtifactFiles: ConfigurableFileCollection

    /**
     * Proof transition: configured KVP-022 inputs plus `AuthorityGitRevision` ->
     * `Kvp022ReceiptContexts`.
     * Establishes exact predecessor re-admission and immutable source/gate ledgers at one head.
     */
    internal fun revalidationContexts(head: AuthorityGitRevision): Kvp022ReceiptContexts =
        Kvp022ReceiptContexts.capture(
            revalidationDependencyContexts(head),
            revalidationTaskId.get(),
            revalidationRedGateId.get(),
            revalidationGreenGateId.get(),
            revalidationCompletionGateId.get(),
            revalidationRedReceiptId.get(),
            revalidationGreenReceiptId.get(),
            revalidationCompletionReceiptId.get(),
            revalidationRedCommand.get(),
            revalidationGreenCommand.get(),
            revalidationCompletionCommand.get(),
            revalidationTaskInputDigest.get(),
            revalidationCompletionInputDigest.get(),
            revalidationProofReportPath.get(),
            revalidationRedGateEvidencePath.get(),
            revalidationGreenGateEvidencePath.get(),
            revalidationRedArtifactPaths.get(),
            revalidationGreenArtifactPaths.get(),
        )
}
