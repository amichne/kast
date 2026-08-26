package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles

internal enum class Kvp024GateOutcome { COMPLETE }

internal class Kvp024ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessorDigests: Map<String, String>,
    private val reportPredecessors: Kvp024ReportPredecessors,
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
         * Proof transition: configured KVP-024 values plus `Kvp024DependencyContexts` ->
         * `Kvp024ReceiptContexts`.
         *
         * Preserves both direct completions and snapshots every Gradle collection before
         * receipt derivation.
         */
        fun capture(
            dependencies: Kvp024DependencyContexts,
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
        ) = Kvp024ReceiptContexts(
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

    fun reportProof(): AdmittedKvp024EndpointPublicationReport = when (
        val result = AdmittedKvp024EndpointPublicationReport.admit(
            boundary.readText(proofReportPath),
            reportPredecessors,
        )
    ) {
        is Kvp024EndpointPublicationReportAdmission.Admitted -> result.report
        is Kvp024EndpointPublicationReportAdmission.Rejected -> rejectReceipt(
            "KVP-024 endpoint-publication report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redGateProof() = gateProof(redGateEvidencePath, Kvp024GateCommand.RED)
    fun greenGateProof() = gateProof(greenGateEvidencePath, Kvp024GateCommand.GREEN)

    fun redExpectation(gate: AdmittedKvp024GateExecution): ProofReceiptExpectation {
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
                "canonicalTaskPaths" to Kvp024GateCommand.RED.canonicalTaskPaths.joinToString(","),
                "dedicatedTaskPath" to Kvp024GateCommand.RED.dedicatedTaskPath,
                "outcome" to Kvp024GateOutcome.COMPLETE.name,
                "selectorPattern" to Kvp024GateCommand.RED.selectorPattern,
            ),
            artifacts,
            taskId,
        )
    }

    fun greenExpectation(
        red: AdmittedProofReceipt,
        gate: AdmittedKvp024GateExecution,
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
                "canonicalTaskPaths" to
                    Kvp024GateCommand.GREEN.canonicalTaskPaths.joinToString(","),
                "descriptorBindings" to report.descriptorBindings,
                "descriptorPublicationLimitPerEndpoint" to
                    report.descriptorPublicationLimitPerEndpoint.toString(),
                "descriptorRules" to report.descriptorRules,
                "descriptorSchema" to report.descriptorSchema,
                "dedicatedTaskPath" to Kvp024GateCommand.GREEN.dedicatedTaskPath,
                "endpointLimitPerProject" to report.endpointLimitPerProject.toString(),
                "framing" to report.framing,
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "outcome" to Kvp024GateOutcome.COMPLETE.name,
                "predecessorCount" to report.predecessorCount.toString(),
                "preparationInputs" to report.preparationInputs,
                "publicInterface" to report.publicInterface,
                "rejectionCaseCount" to report.rejectionCaseCount.toString(),
                "rejectionCases" to report.rejectionCases,
                "rollbackArtifacts" to report.rollbackArtifacts,
                "serviceScope" to report.serviceScope,
                "serviceStates" to report.serviceStates,
                "selectorPattern" to Kvp024GateCommand.GREEN.selectorPattern,
                "socketBindLimitPerEndpoint" to report.socketBindLimitPerEndpoint.toString(),
                "transitions" to report.transitions,
                "transport" to report.transport,
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
        command: Kvp024GateCommand,
    ): AdmittedKvp024GateExecution = when (val result = AdmittedKvp024GateExecution.admit(
        boundary.readText(path),
        command,
        AuthorityGitRevision(boundary.exactHead),
        Kvp024GateExecutionPhase.COMPLETE,
    )) {
        is Kvp024GateExecutionAdmission.Admitted -> result.execution
        is Kvp024GateExecutionAdmission.Rejected -> rejectReceipt(
            "KVP-024 ${command.gateId} execution evidence",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.toString(),
        )
    }
}

abstract class Kvp024ReceiptTaskBase : Kvp024DependencyReceiptTaskBase() {
    @get:Input abstract val endpointPublicationTaskId: Property<String>
    @get:Input abstract val endpointPublicationRedGateId: Property<String>
    @get:Input abstract val endpointPublicationGreenGateId: Property<String>
    @get:Input abstract val endpointPublicationCompletionGateId: Property<String>
    @get:Input abstract val endpointPublicationRedReceiptId: Property<String>
    @get:Input abstract val endpointPublicationGreenReceiptId: Property<String>
    @get:Input abstract val endpointPublicationCompletionReceiptId: Property<String>
    @get:Input abstract val endpointPublicationRedCommand: Property<String>
    @get:Input abstract val endpointPublicationGreenCommand: Property<String>
    @get:Input abstract val endpointPublicationCompletionCommand: Property<String>
    @get:Input abstract val endpointPublicationTaskInputDigest: Property<String>
    @get:Input abstract val endpointPublicationCompletionInputDigest: Property<String>
    @get:Input abstract val endpointPublicationProofReportPath: Property<String>
    @get:Input abstract val endpointPublicationRedGateEvidencePath: Property<String>
    @get:Input abstract val endpointPublicationGreenGateEvidencePath: Property<String>
    @get:Input abstract val endpointPublicationRedArtifactPaths: ListProperty<String>
    @get:Input abstract val endpointPublicationGreenArtifactPaths: ListProperty<String>
    @get:InputFiles abstract val endpointPublicationRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val endpointPublicationGreenArtifactFiles: ConfigurableFileCollection

    internal fun endpointPublicationContexts(head: AuthorityGitRevision): Kvp024ReceiptContexts =
        Kvp024ReceiptContexts.capture(
            endpointDependencyContexts(head),
            endpointPublicationTaskId.get(),
            endpointPublicationRedGateId.get(),
            endpointPublicationGreenGateId.get(),
            endpointPublicationCompletionGateId.get(),
            endpointPublicationRedReceiptId.get(),
            endpointPublicationGreenReceiptId.get(),
            endpointPublicationCompletionReceiptId.get(),
            endpointPublicationRedCommand.get(),
            endpointPublicationGreenCommand.get(),
            endpointPublicationCompletionCommand.get(),
            endpointPublicationTaskInputDigest.get(),
            endpointPublicationCompletionInputDigest.get(),
            endpointPublicationProofReportPath.get(),
            endpointPublicationRedGateEvidencePath.get(),
            endpointPublicationGreenGateEvidencePath.get(),
            endpointPublicationRedArtifactPaths.get(),
            endpointPublicationGreenArtifactPaths.get(),
        )
}
