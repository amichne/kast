package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles

internal enum class Kvp017GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPaths: List<String>,
) {
    RED(
        "./gradlew :workspace:contract:test --tests \"*ProjectReadEpochNegativeTest\"",
        listOf(
            ":workspace:contract:test",
            "--tests",
            "*ProjectReadEpochNegativeTest",
        ),
        listOf(":workspace:contract:test"),
    ),
    GREEN(
        "./gradlew :workspace:contract:test :workspace:intellij-read:test " +
            "--tests \"*ProjectReadEpochTest\"",
        listOf(
            ":workspace:contract:test",
            ":workspace:intellij-read:test",
            "--tests",
            "*ProjectReadEpochTest",
        ),
        listOf(":workspace:contract:test", ":workspace:intellij-read:test"),
    ),
}

internal enum class Kvp017GateOutcome { COMPLETE }

internal data class Kvp017ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val signalLedgerPredecessor: AdmittedProofReceipt,
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
    val contractEpochPath: String,
    val contractNegativeTestPath: String,
    val contractPositiveTestPath: String,
    val observationPath: String,
    val liveObservationPath: String,
    val existingProjectAdmissionPath: String,
    val adapterPositiveTestPath: String,
    val signalFixturePath: String,
    val signalApiContractPath: String,
    val signalClassContractPath: String,
    val additionalArtifactPaths: List<String>,
    val contractBuildPath: String,
    val adapterBuildPath: String,
) {
    private val predecessorDigests = mapOf(
        signalLedgerPredecessor.receiptId.value to signalLedgerPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-017 report bytes -> `AdmittedKvp017ReadEpochReport`.
     *
     * Establishes the canonical scope-bound epoch, comparison, failure, and zero-effect contract.
     * Expected report failures remain finite until rendered at this outer Gradle boundary; raw JSON
     * is extracted only here.
     */
    fun reportProof(): AdmittedKvp017ReadEpochReport = when (
        val result = AdmittedKvp017ReadEpochReport.admit(boundary.readText(proofReportPath))
    ) {
        is Kvp017ReadEpochReportAdmission.Admitted -> result.report
        is Kvp017ReadEpochReportAdmission.Rejected -> rejectReceipt(
            "KVP-017 project read epoch report",
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
            "outcome" to Kvp017GateOutcome.COMPLETE.name,
            "taskPaths" to Kvp017GateCommand.RED.taskPaths.joinToString(","),
        ),
        boundary.artifactDigests(
            listOf(
                contractEpochPath,
                contractNegativeTestPath,
                contractBuildPath,
            ),
        ),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        report: AdmittedKvp017ReadEpochReport,
    ) = boundary.expectation(
        greenReceiptId,
        greenGateId,
        greenCommand,
        taskInputDigest,
        predecessorDigests + (red.receiptId.value to red.digest.value),
        mapOf(
            "authority" to report.authority,
            "blockingWaitCount" to "0",
            "caseCount" to report.caseCount.toString(),
            "callerEpochReconstructionCount" to "0",
            "comparisonRelationCount" to report.comparisonRelationCount.toString(),
            "comparisonScope" to report.comparisonScope,
            "dumbModeEpochValueCount" to "0",
            "edtSemanticWorkCount" to "0",
            "gradleImportCount" to "0",
            "gradleRepairCount" to "0",
            "ideBuild" to report.ideBuild,
            "liveObjectEscapeCount" to "0",
            "maxCachedGradleModels" to report.maxCachedGradleModels.toString(),
            "maxVfsEventsPerBatch" to report.maxVfsEventsPerBatch.toString(),
            "maxVfsPathCharacters" to report.maxVfsPathCharacters.toString(),
            "maxVfsPathUtf8Bytes" to report.maxVfsPathUtf8Bytes.toString(),
            "observationFailureCount" to report.observationFailureCount.toString(),
            "outcome" to Kvp017GateOutcome.COMPLETE.name,
            "primitiveCounterEscapeCount" to "0",
            "repeatedValidationCount" to "0",
            "repositoryWalkCount" to "0",
            "semanticJobCount" to "0",
            "signalCount" to report.signalCount.toString(),
            "sourceHashCount" to "0",
            "taskPaths" to Kvp017GateCommand.GREEN.taskPaths.joinToString(","),
            "vfsRefreshCount" to "0",
            "vfsTraversalCount" to report.vfsTraversalCount.toString(),
        ),
        boundary.artifactDigests(
            listOf(proofReportPath, contractPositiveTestPath, adapterPositiveTestPath) +
                sharedArtifactPaths(),
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
                "admittedDependencyReceiptCount" to "1",
                "admittedGateReceiptCount" to "2",
            ),
            emptyMap(),
            taskId,
        )

    private fun sharedArtifactPaths() = listOf(
        contractEpochPath,
        observationPath,
        liveObservationPath,
        existingProjectAdmissionPath,
        signalFixturePath,
        signalApiContractPath,
        signalClassContractPath,
        contractBuildPath,
        adapterBuildPath,
    ) + additionalArtifactPaths
}

abstract class Kvp017ReceiptTaskBase : Kvp015ReceiptTaskBase() {
    @get:Input abstract val readEpochTaskId: Property<String>
    @get:Input abstract val readEpochRedGateId: Property<String>
    @get:Input abstract val readEpochGreenGateId: Property<String>
    @get:Input abstract val readEpochCompletionGateId: Property<String>
    @get:Input abstract val readEpochRedReceiptId: Property<String>
    @get:Input abstract val readEpochGreenReceiptId: Property<String>
    @get:Input abstract val readEpochCompletionReceiptId: Property<String>
    @get:Input abstract val readEpochRedCommand: Property<String>
    @get:Input abstract val readEpochGreenCommand: Property<String>
    @get:Input abstract val readEpochCompletionCommand: Property<String>
    @get:Input abstract val readEpochTaskInputDigest: Property<String>
    @get:Input abstract val readEpochCompletionInputDigest: Property<String>
    @get:Input abstract val readEpochProofReportPath: Property<String>
    @get:Input abstract val readEpochContractPath: Property<String>
    @get:Input abstract val readEpochContractNegativeTestPath: Property<String>
    @get:Input abstract val readEpochContractPositiveTestPath: Property<String>
    @get:Input abstract val readEpochObservationPath: Property<String>
    @get:Input abstract val readEpochLiveObservationPath: Property<String>
    @get:Input abstract val readEpochExistingProjectAdmissionPath: Property<String>
    @get:Input abstract val readEpochAdapterPositiveTestPath: Property<String>
    @get:Input abstract val readEpochSignalFixturePath: Property<String>
    @get:Input abstract val readEpochSignalApiContractPath: Property<String>
    @get:Input abstract val readEpochSignalClassContractPath: Property<String>
    @get:Input abstract val readEpochAdditionalArtifactPaths: ListProperty<String>
    @get:Input abstract val readEpochContractBuildPath: Property<String>
    @get:Input abstract val readEpochAdapterBuildPath: Property<String>

    @get:InputFile abstract val directSignalLedgerRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directSignalLedgerGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directSignalLedgerProofReportFile: RegularFileProperty
    @get:InputFile abstract val directSignalLedgerCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val readEpochContractFile: RegularFileProperty
    @get:InputFile abstract val readEpochContractNegativeTestFile: RegularFileProperty
    @get:InputFile abstract val readEpochContractPositiveTestFile: RegularFileProperty
    @get:InputFile abstract val readEpochObservationFile: RegularFileProperty
    @get:InputFile abstract val readEpochLiveObservationFile: RegularFileProperty
    @get:InputFile abstract val readEpochExistingProjectAdmissionFile: RegularFileProperty
    @get:InputFile abstract val readEpochAdapterPositiveTestFile: RegularFileProperty
    @get:InputFile abstract val readEpochSignalFixtureFile: RegularFileProperty
    @get:InputFile abstract val readEpochSignalApiContractFile: RegularFileProperty
    @get:InputFile abstract val readEpochSignalClassContractFile: RegularFileProperty
    @get:InputFiles abstract val readEpochAdditionalArtifactFiles: ConfigurableFileCollection
    @get:InputFile abstract val readEpochContractBuildFile: RegularFileProperty
    @get:InputFile abstract val readEpochAdapterBuildFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-017 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave
     * only at the Gradle exec boundary.
     */
    internal fun <T> afterReadEpochGate(
        command: String,
        gate: Kvp017GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-017 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-017 inputs plus `AuthorityGitRevision` ->
     * `Kvp017ReceiptContexts`.
     *
     * Establishes direct admission of the complete KVP-015 closure at the same head. Expected
     * receipt failures remain closed [ProofReceiptFailure] values until rendered at this Gradle
     * boundary; raw receipt extraction is permitted only here.
     */
    internal fun readEpochContexts(head: AuthorityGitRevision): Kvp017ReceiptContexts {
        val signalLedger = epochContexts(head)
        val signalLedgerReport = signalLedger.reportProof()
        val signalLedgerRed = signalLedger.boundary.admit(
            directSignalLedgerRedReceiptFile.get().asFile.toPath(),
            signalLedger.redExpectation(),
        )
        val signalLedgerGreen = signalLedger.boundary.admit(
            directSignalLedgerGreenReceiptFile.get().asFile.toPath(),
            signalLedger.greenExpectation(signalLedgerRed, signalLedgerReport),
        )
        val signalLedgerCompletion = signalLedger.boundary.admit(
            directSignalLedgerCompletionReceiptFile.get().asFile.toPath(),
            signalLedger.completionExpectation(signalLedgerRed, signalLedgerGreen),
        )
        return Kvp017ReceiptContexts(
            signalLedger.boundary,
            signalLedgerCompletion,
            readEpochTaskId.get(),
            readEpochRedGateId.get(),
            readEpochGreenGateId.get(),
            readEpochCompletionGateId.get(),
            readEpochRedReceiptId.get(),
            readEpochGreenReceiptId.get(),
            readEpochCompletionReceiptId.get(),
            readEpochRedCommand.get(),
            readEpochGreenCommand.get(),
            readEpochCompletionCommand.get(),
            readEpochTaskInputDigest.get(),
            readEpochCompletionInputDigest.get(),
            readEpochProofReportPath.get(),
            readEpochContractPath.get(),
            readEpochContractNegativeTestPath.get(),
            readEpochContractPositiveTestPath.get(),
            readEpochObservationPath.get(),
            readEpochLiveObservationPath.get(),
            readEpochExistingProjectAdmissionPath.get(),
            readEpochAdapterPositiveTestPath.get(),
            readEpochSignalFixturePath.get(),
            readEpochSignalApiContractPath.get(),
            readEpochSignalClassContractPath.get(),
            readEpochAdditionalArtifactPaths.get(),
            readEpochContractBuildPath.get(),
            readEpochAdapterBuildPath.get(),
        )
    }
}
