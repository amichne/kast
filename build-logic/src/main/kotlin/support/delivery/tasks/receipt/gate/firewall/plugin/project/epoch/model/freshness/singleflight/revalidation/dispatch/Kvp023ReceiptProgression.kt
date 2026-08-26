package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles

internal enum class Kvp023GateOutcome { COMPLETE }

internal class Kvp023ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessorDigests: Map<String, String>,
    private val reportPredecessors: Kvp023ReportPredecessors,
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
         * Proof transition: configured KVP-023 values plus `Kvp023DependencyContexts` ->
         * `Kvp023ReceiptContexts`.
         *
         * Preserves all three direct completions and snapshots every Gradle collection before
         * receipt derivation.
         */
        fun capture(
            dependencies: Kvp023DependencyContexts,
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
        ) = Kvp023ReceiptContexts(
            dependencies.boundary,
            dependencies.digestMap(),
            dependencies.predecessors,
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

    fun reportProof(): AdmittedKvp023ReadOnlyGraphReport = when (
        val result = AdmittedKvp023ReadOnlyGraphReport.admit(
            boundary.readText(proofReportPath),
            reportPredecessors,
        )
    ) {
        is Kvp023ReadOnlyGraphReportAdmission.Admitted -> result.report
        is Kvp023ReadOnlyGraphReportAdmission.Rejected -> rejectReceipt(
            "KVP-023 read-runtime report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redGateProof() = gateProof(redGateEvidencePath, Kvp023GateCommand.RED)
    fun greenGateProof() = gateProof(greenGateEvidencePath, Kvp023GateCommand.GREEN)

    fun redExpectation(gate: AdmittedKvp023GateExecution): ProofReceiptExpectation {
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
                "canonicalTaskPaths" to Kvp023GateCommand.RED.canonicalTaskPaths.joinToString(","),
                "dedicatedTaskPath" to Kvp023GateCommand.RED.dedicatedTaskPath,
                "outcome" to Kvp023GateOutcome.COMPLETE.name,
                "selectorPattern" to Kvp023GateCommand.RED.selectorPattern,
            ),
            artifacts,
            taskId,
        )
    }

    fun greenExpectation(
        red: AdmittedProofReceipt,
        gate: AdmittedKvp023GateExecution,
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
                "authorities" to report.authorities,
                "canonicalTaskPaths" to
                    Kvp023GateCommand.GREEN.canonicalTaskPaths.joinToString(","),
                "dedicatedTaskPath" to Kvp023GateCommand.GREEN.dedicatedTaskPath,
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "operationBindings" to report.operationBindings,
                "operationCount" to report.operationCount.toString(),
                "outcome" to Kvp023GateOutcome.COMPLETE.name,
                "predecessorCount" to report.predecessorCount.toString(),
                "projectDependencies" to report.projectDependencies,
                "projectDependencyCount" to report.projectDependencyCount.toString(),
                "publicInterface" to report.publicInterface,
                "selectorPattern" to Kvp023GateCommand.GREEN.selectorPattern,
                "unsupportedOperationCount" to report.unsupportedOperationCount.toString(),
                "unsupportedOperationDecision" to report.unsupportedOperationDecision,
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
            "admittedDependencyReceiptCount" to "3",
            "admittedGateReceiptCount" to "2",
        ),
        emptyMap(),
        taskId,
    )

    private fun gateProof(
        path: String,
        command: Kvp023GateCommand,
    ): AdmittedKvp023GateExecution = when (val result = AdmittedKvp023GateExecution.admit(
        boundary.readText(path),
        command,
        AuthorityGitRevision(boundary.exactHead),
        Kvp023GateExecutionPhase.COMPLETE,
    )) {
        is Kvp023GateExecutionAdmission.Admitted -> result.execution
        is Kvp023GateExecutionAdmission.Rejected -> rejectReceipt(
            "KVP-023 ${command.gateId} execution evidence",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.toString(),
        )
    }
}

abstract class Kvp023ReceiptTaskBase : Kvp023DependencyReceiptTaskBase() {
    @get:Input abstract val dispatchTaskId: Property<String>
    @get:Input abstract val dispatchRedGateId: Property<String>
    @get:Input abstract val dispatchGreenGateId: Property<String>
    @get:Input abstract val dispatchCompletionGateId: Property<String>
    @get:Input abstract val dispatchRedReceiptId: Property<String>
    @get:Input abstract val dispatchGreenReceiptId: Property<String>
    @get:Input abstract val dispatchCompletionReceiptId: Property<String>
    @get:Input abstract val dispatchRedCommand: Property<String>
    @get:Input abstract val dispatchGreenCommand: Property<String>
    @get:Input abstract val dispatchCompletionCommand: Property<String>
    @get:Input abstract val dispatchTaskInputDigest: Property<String>
    @get:Input abstract val dispatchCompletionInputDigest: Property<String>
    @get:Input abstract val dispatchProofReportPath: Property<String>
    @get:Input abstract val dispatchRedGateEvidencePath: Property<String>
    @get:Input abstract val dispatchGreenGateEvidencePath: Property<String>
    @get:Input abstract val dispatchRedArtifactPaths: ListProperty<String>
    @get:Input abstract val dispatchGreenArtifactPaths: ListProperty<String>
    @get:InputFiles abstract val dispatchRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val dispatchGreenArtifactFiles: ConfigurableFileCollection

    internal fun dispatchContexts(head: AuthorityGitRevision): Kvp023ReceiptContexts =
        Kvp023ReceiptContexts.capture(
            dispatchDependencyContexts(head),
            dispatchTaskId.get(),
            dispatchRedGateId.get(),
            dispatchGreenGateId.get(),
            dispatchCompletionGateId.get(),
            dispatchRedReceiptId.get(),
            dispatchGreenReceiptId.get(),
            dispatchCompletionReceiptId.get(),
            dispatchRedCommand.get(),
            dispatchGreenCommand.get(),
            dispatchCompletionCommand.get(),
            dispatchTaskInputDigest.get(),
            dispatchCompletionInputDigest.get(),
            dispatchProofReportPath.get(),
            dispatchRedGateEvidencePath.get(),
            dispatchGreenGateEvidencePath.get(),
            dispatchRedArtifactPaths.get(),
            dispatchGreenArtifactPaths.get(),
        )
}
