package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp015GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPath: String,
) {
    RED(
        "./gradlew :workspace:intellij-read:characterizeEpochNegative",
        listOf(":workspace:intellij-read:characterizeEpochNegative"),
        ":workspace:intellij-read:characterizeEpochNegative",
    ),
    GREEN(
        "./gradlew :workspace:intellij-read:characterizeEpoch",
        listOf(":workspace:intellij-read:characterizeEpoch"),
        ":workspace:intellij-read:characterizeEpoch",
    ),
}

internal enum class Kvp015GateOutcome { COMPLETE }

internal data class Kvp015ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val projectAdmissionPredecessor: AdmittedProofReceipt,
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
    val negativeTestPath: String,
    val positiveTestPath: String,
    val apiContractPath: String,
    val classContractPath: String,
    val fixturePath: String,
    val moduleBuildPath: String,
    val engineeringLedgerPath: String,
) {
    private val predecessorDigests = mapOf(
        projectAdmissionPredecessor.receiptId.value to projectAdmissionPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-015 report bytes -> `AdmittedKvp015EpochLedgerReport`.
     *
     * Establishes the canonical IDEA-262 READ_EPOCH signal and case ledger. Expected report
     * failures remain finite until rendered at this outer Gradle boundary; raw JSON is extracted
     * only here.
     */
    fun reportProof(): AdmittedKvp015EpochLedgerReport = when (
        val result = AdmittedKvp015EpochLedgerReport.admit(boundary.readText(proofReportPath))
    ) {
        is Kvp015EpochLedgerReportAdmission.Admitted -> result.report
        is Kvp015EpochLedgerReportAdmission.Rejected -> rejectReceipt(
            "KVP-015 epoch signal ledger",
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
            "outcome" to Kvp015GateOutcome.COMPLETE.name,
            "taskPath" to Kvp015GateCommand.RED.taskPath,
        ),
        boundary.artifactDigests(
            listOf(
                negativeTestPath,
                apiContractPath,
                classContractPath,
                fixturePath,
                moduleBuildPath,
            ),
        ),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        report: AdmittedKvp015EpochLedgerReport,
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
            "edtWorkCount" to "0",
            "gradleImportCount" to "0",
            "ideBuild" to report.ideBuild,
            "outcome" to Kvp015GateOutcome.COMPLETE.name,
            "repositoryWalkCount" to "0",
            "sampleCount" to report.sampleCount.toString(),
            "semanticJobCount" to "0",
            "signalCount" to report.signalCount.toString(),
            "sourceHashCount" to "0",
            "stormEventCount" to report.stormEventCount.toString(),
            "taskPath" to Kvp015GateCommand.GREEN.taskPath,
            "vfsRefreshCount" to "0",
        ),
        boundary.artifactDigests(
            listOf(
                proofReportPath,
                positiveTestPath,
                apiContractPath,
                classContractPath,
                fixturePath,
                moduleBuildPath,
                engineeringLedgerPath,
            ),
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
}

abstract class Kvp015ReceiptTaskBase : Kvp014ReceiptTaskBase() {
    @get:Input abstract val epochTaskId: Property<String>
    @get:Input abstract val epochRedGateId: Property<String>
    @get:Input abstract val epochGreenGateId: Property<String>
    @get:Input abstract val epochCompletionGateId: Property<String>
    @get:Input abstract val epochRedReceiptId: Property<String>
    @get:Input abstract val epochGreenReceiptId: Property<String>
    @get:Input abstract val epochCompletionReceiptId: Property<String>
    @get:Input abstract val epochRedCommand: Property<String>
    @get:Input abstract val epochGreenCommand: Property<String>
    @get:Input abstract val epochCompletionCommand: Property<String>
    @get:Input abstract val epochTaskInputDigest: Property<String>
    @get:Input abstract val epochCompletionInputDigest: Property<String>
    @get:Input abstract val epochProofReportPath: Property<String>
    @get:Input abstract val epochNegativeTestPath: Property<String>
    @get:Input abstract val epochPositiveTestPath: Property<String>
    @get:Input abstract val epochApiContractPath: Property<String>
    @get:Input abstract val epochClassContractPath: Property<String>
    @get:Input abstract val epochFixturePath: Property<String>
    @get:Input abstract val epochModuleBuildPath: Property<String>
    @get:Input abstract val epochEngineeringLedgerPath: Property<String>

    @get:InputFile abstract val directProjectRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directProjectGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directProjectProofReportFile: RegularFileProperty
    @get:InputFile abstract val directProjectCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val epochNegativeTestFile: RegularFileProperty
    @get:InputFile abstract val epochPositiveTestFile: RegularFileProperty
    @get:InputFile abstract val epochApiContractFile: RegularFileProperty
    @get:InputFile abstract val epochClassContractFile: RegularFileProperty
    @get:InputFile abstract val epochFixtureFile: RegularFileProperty
    @get:InputFile abstract val epochModuleBuildFile: RegularFileProperty
    @get:InputFile abstract val epochEngineeringLedgerFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-015 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave
     * only at Gradle exec.
     */
    internal fun <T> afterEpochGate(
        command: String,
        gate: Kvp015GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-015 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-015 inputs plus `AuthorityGitRevision` ->
     * `Kvp015ReceiptContexts`.
     *
     * Establishes independent direct admission of the complete KVP-014 receipt closure. Expected
     * receipt failures remain closed until rendered at this Gradle boundary.
     */
    internal fun epochContexts(head: AuthorityGitRevision): Kvp015ReceiptContexts {
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
        return Kvp015ReceiptContexts(
            project.boundary,
            projectCompletion,
            epochTaskId.get(),
            epochRedGateId.get(),
            epochGreenGateId.get(),
            epochCompletionGateId.get(),
            epochRedReceiptId.get(),
            epochGreenReceiptId.get(),
            epochCompletionReceiptId.get(),
            epochRedCommand.get(),
            epochGreenCommand.get(),
            epochCompletionCommand.get(),
            epochTaskInputDigest.get(),
            epochCompletionInputDigest.get(),
            epochProofReportPath.get(),
            epochNegativeTestPath.get(),
            epochPositiveTestPath.get(),
            epochApiContractPath.get(),
            epochClassContractPath.get(),
            epochFixturePath.get(),
            epochModuleBuildPath.get(),
            epochEngineeringLedgerPath.get(),
        )
    }
}
