package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles

internal enum class Kvp019GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPath: String,
) {
    RED(
        "./gradlew :workspace:intellij-read:test --tests \"*VfsPassiveAdmissionNegativeTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*VfsPassiveAdmissionNegativeTest",
        ),
        ":workspace:intellij-read:test",
    ),
    GREEN(
        "./gradlew :workspace:intellij-read:test --tests \"*VfsPassiveAdmissionTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*VfsPassiveAdmissionTest",
        ),
        ":workspace:intellij-read:test",
    ),
}

internal enum class Kvp019GateOutcome { COMPLETE }

internal class Kvp019ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessors: Kvp019ReportPredecessors,
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
    private val redArtifactPaths: List<String>,
    private val greenArtifactPaths: List<String>,
) {
    private val predecessorDigests = predecessors.digestMap()

    companion object {
        /**
         * Proof transition: configured KVP-019 values plus `Kvp019DependencyContexts` ->
         * `Kvp019ReceiptContexts`.
         *
         * Preserves the exact predecessor proof and snapshots every Gradle collection before
         * receipt derivation.
         */
        fun capture(
            dependencies: Kvp019DependencyContexts,
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
            redArtifactPaths: List<String>,
            greenArtifactPaths: List<String>,
        ) = Kvp019ReceiptContexts(
            dependencies.boundary,
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
            redArtifactPaths.toList(),
            greenArtifactPaths.toList(),
        )
    }

    /**
     * Proof transition: configured report bytes -> `AdmittedKvp019VfsPassiveReport`.
     * Establishes canonical predecessor-bound VFS-passive evidence; expected malformed report
     * data remains closed until rendered at this receipt boundary.
     */
    fun reportProof(): AdmittedKvp019VfsPassiveReport = when (
        val result = AdmittedKvp019VfsPassiveReport.admit(
            boundary.readText(proofReportPath),
            predecessors,
        )
    ) {
        is Kvp019ReportAdmission.Admitted -> result.report
        is Kvp019ReportAdmission.Rejected -> rejectReceipt(
            "KVP-019 VFS-passive report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation() = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "outcome" to Kvp019GateOutcome.COMPLETE.name,
            "rejectionKindCount" to "5",
            "taskPath" to Kvp019GateCommand.RED.taskPath,
            "unavailableObservationFailureCount" to "16",
        ),
        boundary.artifactDigests(redArtifactPaths),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt): ProofReceiptExpectation {
        val report = reportProof()
        val artifacts = boundary.artifactDigests(greenArtifactPaths).toMutableMap()
        artifacts[proofReportPath] = sha256Bytes(
            report.canonicalDocument.toByteArray(Charsets.UTF_8),
        )
        return boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "admissionCaseCount" to report.admissionCaseCount.toString(),
                "admissionMode" to report.admissionMode,
                "authority" to report.authority,
                "forbiddenWorkKindCount" to report.forbiddenWorkKindCount.toString(),
                "freshnessObservationCountPerAdmission" to
                    report.freshnessObservationCountPerAdmission.toString(),
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "observationFailureStageCount" to report.observationFailureStageCount.toString(),
                "outcome" to Kvp019GateOutcome.COMPLETE.name,
                "publicInterface" to report.publicInterface,
                "retainedCapabilityEvidenceCount" to
                    report.retainedCapabilityEvidenceCount.toString(),
                "taskPath" to Kvp019GateCommand.GREEN.taskPath,
                "unavailableObservationFailureCount" to
                    report.unavailableObservationFailureCount.toString(),
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
}

abstract class Kvp019ReceiptTaskBase : Kvp019DependencyReceiptTaskBase() {
    @get:Input abstract val freshnessTaskId: Property<String>
    @get:Input abstract val freshnessRedGateId: Property<String>
    @get:Input abstract val freshnessGreenGateId: Property<String>
    @get:Input abstract val freshnessCompletionGateId: Property<String>
    @get:Input abstract val freshnessRedReceiptId: Property<String>
    @get:Input abstract val freshnessGreenReceiptId: Property<String>
    @get:Input abstract val freshnessCompletionReceiptId: Property<String>
    @get:Input abstract val freshnessRedCommand: Property<String>
    @get:Input abstract val freshnessGreenCommand: Property<String>
    @get:Input abstract val freshnessCompletionCommand: Property<String>
    @get:Input abstract val freshnessTaskInputDigest: Property<String>
    @get:Input abstract val freshnessCompletionInputDigest: Property<String>
    @get:Input abstract val freshnessProofReportPath: Property<String>
    @get:Input abstract val freshnessRedArtifactPaths: ListProperty<String>
    @get:Input abstract val freshnessGreenArtifactPaths: ListProperty<String>

    @get:InputFiles abstract val freshnessRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val freshnessGreenArtifactFiles: ConfigurableFileCollection

    internal fun <T> afterFreshnessGate(
        command: String,
        gate: Kvp019GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-019 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-019 inputs plus `AuthorityGitRevision` ->
     * `Kvp019ReceiptContexts`.
     * Establishes exact predecessor re-admission and immutable artifact ledgers at one head.
     */
    internal fun freshnessContexts(head: AuthorityGitRevision): Kvp019ReceiptContexts =
        Kvp019ReceiptContexts.capture(
            freshnessDependencyContexts(head),
            freshnessTaskId.get(),
            freshnessRedGateId.get(),
            freshnessGreenGateId.get(),
            freshnessCompletionGateId.get(),
            freshnessRedReceiptId.get(),
            freshnessGreenReceiptId.get(),
            freshnessCompletionReceiptId.get(),
            freshnessRedCommand.get(),
            freshnessGreenCommand.get(),
            freshnessCompletionCommand.get(),
            freshnessTaskInputDigest.get(),
            freshnessCompletionInputDigest.get(),
            freshnessProofReportPath.get(),
            freshnessRedArtifactPaths.get(),
            freshnessGreenArtifactPaths.get(),
        )
}
