package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp016GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPath: String,
) {
    RED(
        "./gradlew :workspace:intellij-read:test --tests \"*DetachedModelNegativeTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*DetachedModelNegativeTest",
        ),
        ":workspace:intellij-read:test",
    ),
    GREEN(
        "./gradlew :workspace:intellij-read:test --tests \"*DetachedModelTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*DetachedModelTest",
        ),
        ":workspace:intellij-read:test",
    ),
}

internal enum class Kvp016GateOutcome { COMPLETE }

internal data class Kvp016ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val projectAdmissionPredecessor: AdmittedProofReceipt,
    val epochPredecessor: AdmittedProofReceipt,
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
    val detachedModelPath: String,
    val refinementPath: String,
    val valueRefinementPath: String,
    val capturePath: String,
    val existingProjectAdmissionPath: String,
    val liveCapturePath: String,
    val negativeTestPath: String,
    val positiveTestPath: String,
    val fixturesPath: String,
    val classContractPath: String,
    val classpathUrlContractPath: String,
    val moduleBuildPath: String,
) {
    private val predecessorDigests = mapOf(
        projectAdmissionPredecessor.receiptId.value to projectAdmissionPredecessor.digest.value,
        epochPredecessor.receiptId.value to epochPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-016 report bytes ->
     * `AdmittedKvp016DetachedModelReport`.
     *
     * Establishes the canonical bounded detached-model contract. Expected report failures remain
     * finite until rendered at this outer Gradle boundary; raw JSON is extracted only here.
     */
    fun reportProof(): AdmittedKvp016DetachedModelReport = when (
        val result = AdmittedKvp016DetachedModelReport.admit(boundary.readText(proofReportPath))
    ) {
        is Kvp016DetachedModelReportAdmission.Admitted -> result.report
        is Kvp016DetachedModelReportAdmission.Rejected -> rejectReceipt(
            "KVP-016 detached model report",
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
            "outcome" to Kvp016GateOutcome.COMPLETE.name,
            "taskPath" to Kvp016GateCommand.RED.taskPath,
        ),
        boundary.artifactDigests(
            sharedArtifactPaths() + negativeTestPath,
        ),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        report: AdmittedKvp016DetachedModelReport,
    ) = boundary.expectation(
        greenReceiptId,
        greenGateId,
        greenCommand,
        taskInputDigest,
        predecessorDigests + (red.receiptId.value to red.digest.value),
        mapOf(
            "authority" to report.authority,
            "canonicalRoot" to report.canonicalRoot,
            "maxClasspathEntryCountPerModule" to
                report.maxClasspathEntryCountPerModule.toString(),
            "maxModuleCount" to report.maxModuleCount.toString(),
            "maxSourceRootCountPerModule" to report.maxSourceRootCountPerModule.toString(),
            "outcome" to Kvp016GateOutcome.COMPLETE.name,
            "rejectedLiveCapabilityCount" to report.rejectedLiveCapabilityCount.toString(),
            "retainedFacetCount" to report.retainedFacetCount.toString(),
            "taskPath" to Kvp016GateCommand.GREEN.taskPath,
        ),
        boundary.artifactDigests(
            listOf(proofReportPath, positiveTestPath) + sharedArtifactPaths(),
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
            mapOf(
                "admittedDependencyReceiptCount" to "2",
                "admittedGateReceiptCount" to "2",
            ),
            emptyMap(),
            taskId,
        )

    private fun sharedArtifactPaths() = listOf(
        detachedModelPath,
        refinementPath,
        valueRefinementPath,
        capturePath,
        existingProjectAdmissionPath,
        liveCapturePath,
        fixturesPath,
        classContractPath,
        classpathUrlContractPath,
        moduleBuildPath,
    )
}

abstract class Kvp016ReceiptTaskBase : Kvp015ReceiptTaskBase() {
    @get:Input abstract val detachedTaskId: Property<String>
    @get:Input abstract val detachedRedGateId: Property<String>
    @get:Input abstract val detachedGreenGateId: Property<String>
    @get:Input abstract val detachedCompletionGateId: Property<String>
    @get:Input abstract val detachedRedReceiptId: Property<String>
    @get:Input abstract val detachedGreenReceiptId: Property<String>
    @get:Input abstract val detachedCompletionReceiptId: Property<String>
    @get:Input abstract val detachedRedCommand: Property<String>
    @get:Input abstract val detachedGreenCommand: Property<String>
    @get:Input abstract val detachedCompletionCommand: Property<String>
    @get:Input abstract val detachedTaskInputDigest: Property<String>
    @get:Input abstract val detachedCompletionInputDigest: Property<String>
    @get:Input abstract val detachedProofReportPath: Property<String>
    @get:Input abstract val detachedModelPath: Property<String>
    @get:Input abstract val detachedRefinementPath: Property<String>
    @get:Input abstract val detachedValueRefinementPath: Property<String>
    @get:Input abstract val detachedCapturePath: Property<String>
    @get:Input abstract val detachedExistingProjectAdmissionPath: Property<String>
    @get:Input abstract val detachedLiveCapturePath: Property<String>
    @get:Input abstract val detachedNegativeTestPath: Property<String>
    @get:Input abstract val detachedPositiveTestPath: Property<String>
    @get:Input abstract val detachedFixturesPath: Property<String>
    @get:Input abstract val detachedClassContractPath: Property<String>
    @get:Input abstract val detachedClasspathUrlContractPath: Property<String>
    @get:Input abstract val detachedModuleBuildPath: Property<String>

    @get:InputFile abstract val directEpochRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directEpochGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directEpochProofReportFile: RegularFileProperty
    @get:InputFile abstract val directEpochCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val detachedModelFile: RegularFileProperty
    @get:InputFile abstract val detachedRefinementFile: RegularFileProperty
    @get:InputFile abstract val detachedValueRefinementFile: RegularFileProperty
    @get:InputFile abstract val detachedCaptureFile: RegularFileProperty
    @get:InputFile abstract val detachedExistingProjectAdmissionFile: RegularFileProperty
    @get:InputFile abstract val detachedLiveCaptureFile: RegularFileProperty
    @get:InputFile abstract val detachedNegativeTestFile: RegularFileProperty
    @get:InputFile abstract val detachedPositiveTestFile: RegularFileProperty
    @get:InputFile abstract val detachedFixturesFile: RegularFileProperty
    @get:InputFile abstract val detachedClassContractFile: RegularFileProperty
    @get:InputFile abstract val detachedClasspathUrlContractFile: RegularFileProperty
    @get:InputFile abstract val detachedModuleBuildFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-016 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave
     * only at the Gradle exec boundary.
     */
    internal fun <T> afterDetachedGate(
        command: String,
        gate: Kvp016GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-016 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-016 inputs plus `AuthorityGitRevision` ->
     * `Kvp016ReceiptContexts`.
     *
     * Establishes direct admission of KVP-014 and the complete KVP-015 closure at the same head.
     * Expected receipt failures remain closed [ProofReceiptFailure] values until rendered at this
     * Gradle boundary; raw receipt extraction is permitted only here.
     */
    internal fun detachedContexts(head: AuthorityGitRevision): Kvp016ReceiptContexts {
        val epoch = epochContexts(head)
        val epochReport = epoch.reportProof()
        val epochRed = epoch.boundary.admit(
            directEpochRedReceiptFile.get().asFile.toPath(),
            epoch.redExpectation(),
        )
        val epochGreen = epoch.boundary.admit(
            directEpochGreenReceiptFile.get().asFile.toPath(),
            epoch.greenExpectation(epochRed, epochReport),
        )
        val epochCompletion = epoch.boundary.admit(
            directEpochCompletionReceiptFile.get().asFile.toPath(),
            epoch.completionExpectation(epochRed, epochGreen),
        )
        return Kvp016ReceiptContexts(
            epoch.boundary,
            epoch.projectAdmissionPredecessor,
            epochCompletion,
            detachedTaskId.get(),
            detachedRedGateId.get(),
            detachedGreenGateId.get(),
            detachedCompletionGateId.get(),
            detachedRedReceiptId.get(),
            detachedGreenReceiptId.get(),
            detachedCompletionReceiptId.get(),
            detachedRedCommand.get(),
            detachedGreenCommand.get(),
            detachedCompletionCommand.get(),
            detachedTaskInputDigest.get(),
            detachedCompletionInputDigest.get(),
            detachedProofReportPath.get(),
            detachedModelPath.get(),
            detachedRefinementPath.get(),
            detachedValueRefinementPath.get(),
            detachedCapturePath.get(),
            detachedExistingProjectAdmissionPath.get(),
            detachedLiveCapturePath.get(),
            detachedNegativeTestPath.get(),
            detachedPositiveTestPath.get(),
            detachedFixturesPath.get(),
            detachedClassContractPath.get(),
            detachedClasspathUrlContractPath.get(),
            detachedModuleBuildPath.get(),
        )
    }
}
