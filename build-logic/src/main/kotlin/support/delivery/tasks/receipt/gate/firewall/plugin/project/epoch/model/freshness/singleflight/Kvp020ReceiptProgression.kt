package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles

internal enum class Kvp020GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPath: String,
) {
    RED(
        "./gradlew :runtime:ide-read:test --tests \"*SingleFlightNegativeTest\"",
        listOf(
            ":runtime:ide-read:test",
            "--tests",
            "*SingleFlightNegativeTest",
        ),
        ":runtime:ide-read:test",
    ),
    GREEN(
        "./gradlew :runtime:ide-read:test --tests \"*SingleFlightTest\"",
        listOf(
            ":runtime:ide-read:test",
            "--tests",
            "*SingleFlightTest",
        ),
        ":runtime:ide-read:test",
    ),
}

internal enum class Kvp020GateOutcome { COMPLETE }

internal class Kvp020ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessors: Kvp020ReportPredecessors,
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
         * Proof transition: configured KVP-020 values plus `Kvp020DependencyContexts` ->
         * `Kvp020ReceiptContexts`.
         *
         * Preserves the exact predecessor proof and snapshots every Gradle collection before
         * receipt derivation.
         */
        fun capture(
            dependencies: Kvp020DependencyContexts,
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
        ) = Kvp020ReceiptContexts(
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
     * Proof transition: configured report bytes -> `AdmittedKvp020SingleFlightReport`.
     * Establishes canonical predecessor-bound single-flight evidence; expected malformed report
     * data remains closed until rendered at this receipt boundary.
     */
    fun reportProof(): AdmittedKvp020SingleFlightReport = when (
        val result = AdmittedKvp020SingleFlightReport.admit(
            boundary.readText(proofReportPath),
            predecessors,
        )
    ) {
        is Kvp020SingleFlightReportAdmission.Admitted -> result.report
        is Kvp020SingleFlightReportAdmission.Rejected -> rejectReceipt(
            "KVP-020 single-flight report",
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
            "activePermitLimit" to "1",
            "outcome" to Kvp020GateOutcome.COMPLETE.name,
            "queuedRequestLimit" to "1",
            "taskPath" to Kvp020GateCommand.RED.taskPath,
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
                "activePermitLimit" to report.activePermitLimit.toString(),
                "authority" to report.authority,
                "effect" to report.effect,
                "freshnessObservationCount" to report.freshnessObservationCount.toString(),
                "observedForbiddenRetentionCount" to
                    report.observedForbiddenRetentionCount.toString(),
                "observedForbiddenWorkCount" to report.observedForbiddenWorkCount.toString(),
                "outcome" to Kvp020GateOutcome.COMPLETE.name,
                "promotionLimitPerActiveTerminalization" to
                    report.promotionLimitPerActiveTerminalization.toString(),
                "publicInterface" to report.publicInterface,
                "queuedRequestLimit" to report.queuedRequestLimit.toString(),
                "semanticExecutionCount" to report.semanticExecutionCount.toString(),
                "stateCount" to report.stateCount.toString(),
                "taskPath" to Kvp020GateCommand.GREEN.taskPath,
                "terminalizationLimitPerAuthority" to
                    report.terminalizationLimitPerAuthority.toString(),
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

abstract class Kvp020DependencyReceiptTaskBase : Kvp019ReceiptTaskBase() {
    @get:InputFile abstract val directFreshnessRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directFreshnessGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directFreshnessProofReportFile: RegularFileProperty
    @get:InputFile abstract val directFreshnessCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: configured KVP-014/KVP-019 inputs plus `AuthorityGitRevision` ->
     * `Kvp020DependencyContexts`.
     *
     * Re-admits both complete predecessor closures independently at one exact head. Raw receipt
     * extraction is permitted only here; every expected mismatch remains closed typed data until
     * this Gradle boundary renders it.
     */
    internal fun singleFlightDependencyContexts(
        head: AuthorityGitRevision,
    ): Kvp020DependencyContexts {
        val project = projectAdmissionContexts(head)
        val projectReport = project.reportProof()
        val projectRed = project.boundary.admit(
            directProjectRedReceiptFile.get().asFile.toPath(),
            project.redExpectation(),
        )
        val projectGreen = project.boundary.admit(
            directProjectGreenReceiptFile.get().asFile.toPath(),
            project.greenExpectation(projectRed, projectReport),
        )
        val projectCompletion = project.boundary.admit(
            directProjectCompletionReceiptFile.get().asFile.toPath(),
            project.completionExpectation(projectRed, projectGreen),
        )
        val freshness = freshnessContexts(head)
        val freshnessRed = freshness.boundary.admit(
            directFreshnessRedReceiptFile.get().asFile.toPath(),
            freshness.redExpectation(),
        )
        val freshnessGreen = freshness.boundary.admit(
            directFreshnessGreenReceiptFile.get().asFile.toPath(),
            freshness.greenExpectation(freshnessRed),
        )
        val freshnessCompletion = freshness.boundary.admit(
            directFreshnessCompletionReceiptFile.get().asFile.toPath(),
            freshness.completionExpectation(freshnessRed, freshnessGreen),
        )
        return when (val result = Kvp020DependencyContexts.refine(
            head,
            project.boundary,
            projectCompletion,
            freshness.boundary,
            freshnessCompletion,
        )) {
            is Kvp020DependencyRefinement.Admitted -> result.context
            is Kvp020DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-020 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.toString(),
            )
        }
    }
}

abstract class Kvp020ReceiptTaskBase : Kvp020DependencyReceiptTaskBase() {
    @get:Input abstract val singleFlightTaskId: Property<String>
    @get:Input abstract val singleFlightRedGateId: Property<String>
    @get:Input abstract val singleFlightGreenGateId: Property<String>
    @get:Input abstract val singleFlightCompletionGateId: Property<String>
    @get:Input abstract val singleFlightRedReceiptId: Property<String>
    @get:Input abstract val singleFlightGreenReceiptId: Property<String>
    @get:Input abstract val singleFlightCompletionReceiptId: Property<String>
    @get:Input abstract val singleFlightRedCommand: Property<String>
    @get:Input abstract val singleFlightGreenCommand: Property<String>
    @get:Input abstract val singleFlightCompletionCommand: Property<String>
    @get:Input abstract val singleFlightTaskInputDigest: Property<String>
    @get:Input abstract val singleFlightCompletionInputDigest: Property<String>
    @get:Input abstract val singleFlightProofReportPath: Property<String>
    @get:Input abstract val singleFlightRedArtifactPaths: ListProperty<String>
    @get:Input abstract val singleFlightGreenArtifactPaths: ListProperty<String>

    @get:InputFiles abstract val singleFlightRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val singleFlightGreenArtifactFiles: ConfigurableFileCollection

    /**
     * Proof transition: `(String, Kvp020GateCommand, () -> T) -> T`.
     *
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments
     * leave only at the outer Gradle execution boundary.
     */
    internal fun <T> afterSingleFlightGate(
        command: String,
        gate: Kvp020GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-020 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-020 inputs plus `AuthorityGitRevision` ->
     * `Kvp020ReceiptContexts`.
     * Establishes exact predecessor re-admission and immutable artifact ledgers at one head.
     */
    internal fun singleFlightContexts(head: AuthorityGitRevision): Kvp020ReceiptContexts =
        Kvp020ReceiptContexts.capture(
            singleFlightDependencyContexts(head),
            singleFlightTaskId.get(),
            singleFlightRedGateId.get(),
            singleFlightGreenGateId.get(),
            singleFlightCompletionGateId.get(),
            singleFlightRedReceiptId.get(),
            singleFlightGreenReceiptId.get(),
            singleFlightCompletionReceiptId.get(),
            singleFlightRedCommand.get(),
            singleFlightGreenCommand.get(),
            singleFlightCompletionCommand.get(),
            singleFlightTaskInputDigest.get(),
            singleFlightCompletionInputDigest.get(),
            singleFlightProofReportPath.get(),
            singleFlightRedArtifactPaths.get(),
            singleFlightGreenArtifactPaths.get(),
        )
}
