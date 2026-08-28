package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles

internal enum class Kvp021GateOutcome { COMPLETE }

internal class Kvp021ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessorDigests: Map<String, String>,
    private val reportPredecessors: Kvp021ReportPredecessors,
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
         * Proof transition: configured KVP-021 values plus `Kvp021DependencyContexts` ->
         * `Kvp021ReceiptContexts`.
         *
         * Preserves the exact predecessor proof and snapshots every Gradle collection before
         * receipt derivation.
         */
        fun capture(
            dependencies: Kvp021DependencyContexts,
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
        ) = Kvp021ReceiptContexts(
            dependencies.boundary,
            dependencies.digestMap(),
            dependencies.reportPredecessors,
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
     * Proof transition: configured report bytes -> `AdmittedKvp021CancellableReadReport`.
     * Establishes canonical KVP-021 product evidence; malformed report data remains closed until
     * rendered at this receipt boundary.
     */
    fun reportProof(): AdmittedKvp021CancellableReadReport = when (
        val result = AdmittedKvp021CancellableReadReport.admit(
            boundary.readText(proofReportPath),
            reportPredecessors,
        )
    ) {
        is Kvp021CancellableReadReportAdmission.Admitted -> result.report
        is Kvp021CancellableReadReportAdmission.Rejected -> rejectReceipt(
            "KVP-021 cancellable-read report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redGateProof(): AdmittedKvp021GateExecution = gateProof(
        redGateEvidencePath,
        Kvp021GateCommand.RED,
    )

    fun greenGateProof(): AdmittedKvp021GateExecution = gateProof(
        greenGateEvidencePath,
        Kvp021GateCommand.GREEN,
    )

    fun redExpectation(gate: AdmittedKvp021GateExecution): ProofReceiptExpectation {
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
                "canonicalTaskPath" to Kvp021GateCommand.RED.canonicalTaskPath,
                "dedicatedTaskPath" to Kvp021GateCommand.RED.dedicatedTaskPath,
                "outcome" to Kvp021GateOutcome.COMPLETE.name,
                "selectorPattern" to Kvp021GateCommand.RED.selectorPattern,
            ),
            artifacts,
            taskId,
        )
    }

    fun greenExpectation(
        red: AdmittedProofReceipt,
        gate: AdmittedKvp021GateExecution,
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
                "authority" to report.authority,
                "canonicalTaskPath" to Kvp021GateCommand.GREEN.canonicalTaskPath,
                "dedicatedTaskPath" to Kvp021GateCommand.GREEN.dedicatedTaskPath,
                "effectCount" to report.effectCount.toString(),
                "executionCaseCount" to report.executionCaseCount.toString(),
                "failureCount" to report.failureCount.toString(),
                "lifecycleStateCount" to report.lifecycleStateCount.toString(),
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "outcome" to Kvp021GateOutcome.COMPLETE.name,
                "permitTerminalizationCountPerExecution" to
                    report.permitTerminalizationCountPerExecution.toString(),
                "platformPrimitive" to report.platformPrimitive,
                "publicInterface" to report.publicInterface,
                "selectorPattern" to Kvp021GateCommand.GREEN.selectorPattern,
                "semanticExecutionLimitPerPermit" to
                    report.semanticExecutionLimitPerPermit.toString(),
                "terminalOutcomeCount" to report.terminalOutcomeCount.toString(),
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
            "admittedDependencyReceiptCount" to "2",
            "admittedGateReceiptCount" to "2",
        ),
        emptyMap(),
        taskId,
    )

    private fun gateProof(
        path: String,
        command: Kvp021GateCommand,
    ): AdmittedKvp021GateExecution = when (val result = AdmittedKvp021GateExecution.admit(
        boundary.readText(path),
        command,
        AuthorityGitRevision(boundary.exactHead),
        Kvp021GateExecutionPhase.COMPLETE,
    )) {
        is Kvp021GateExecutionAdmission.Admitted -> result.execution
        is Kvp021GateExecutionAdmission.Rejected -> rejectReceipt(
            "KVP-021 ${command.gateId} execution evidence",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.toString(),
        )
    }
}

abstract class Kvp021ReceiptTaskBase : Kvp021DependencyReceiptTaskBase() {
    @get:Input abstract val cancellableTaskId: Property<String>
    @get:Input abstract val cancellableRedGateId: Property<String>
    @get:Input abstract val cancellableGreenGateId: Property<String>
    @get:Input abstract val cancellableCompletionGateId: Property<String>
    @get:Input abstract val cancellableRedReceiptId: Property<String>
    @get:Input abstract val cancellableGreenReceiptId: Property<String>
    @get:Input abstract val cancellableCompletionReceiptId: Property<String>
    @get:Input abstract val cancellableRedCommand: Property<String>
    @get:Input abstract val cancellableGreenCommand: Property<String>
    @get:Input abstract val cancellableCompletionCommand: Property<String>
    @get:Input abstract val cancellableTaskInputDigest: Property<String>
    @get:Input abstract val cancellableCompletionInputDigest: Property<String>
    @get:Input abstract val cancellableProofReportPath: Property<String>
    @get:Input abstract val cancellableRedGateEvidencePath: Property<String>
    @get:Input abstract val cancellableGreenGateEvidencePath: Property<String>
    @get:Input abstract val cancellableRedArtifactPaths: ListProperty<String>
    @get:Input abstract val cancellableGreenArtifactPaths: ListProperty<String>

    @get:InputFiles abstract val cancellableRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val cancellableGreenArtifactFiles: ConfigurableFileCollection

    /**
     * Proof transition: configured KVP-021 inputs plus `AuthorityGitRevision` ->
     * `Kvp021ReceiptContexts`.
     * Establishes exact predecessor re-admission and immutable source/gate ledgers at one head.
     */
    internal fun cancellableContexts(head: AuthorityGitRevision): Kvp021ReceiptContexts =
        Kvp021ReceiptContexts.capture(
            cancellableDependencyContexts(head),
            cancellableTaskId.get(),
            cancellableRedGateId.get(),
            cancellableGreenGateId.get(),
            cancellableCompletionGateId.get(),
            cancellableRedReceiptId.get(),
            cancellableGreenReceiptId.get(),
            cancellableCompletionReceiptId.get(),
            cancellableRedCommand.get(),
            cancellableGreenCommand.get(),
            cancellableCompletionCommand.get(),
            cancellableTaskInputDigest.get(),
            cancellableCompletionInputDigest.get(),
            cancellableProofReportPath.get(),
            cancellableRedGateEvidencePath.get(),
            cancellableGreenGateEvidencePath.get(),
            cancellableRedArtifactPaths.get(),
            cancellableGreenArtifactPaths.get(),
        )
}
