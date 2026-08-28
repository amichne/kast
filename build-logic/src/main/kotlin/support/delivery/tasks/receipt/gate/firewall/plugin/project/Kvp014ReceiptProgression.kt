package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp014GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val testSelector: String,
) {
    RED(
        "./gradlew :workspace:intellij-read:test --tests \"*ExistingProjectAdmissionNegativeTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*ExistingProjectAdmissionNegativeTest",
        ),
        "*ExistingProjectAdmissionNegativeTest",
    ),
    GREEN(
        "./gradlew :workspace:intellij-read:test --tests \"*ExistingProjectAdmissionTest\"",
        listOf(
            ":workspace:intellij-read:test",
            "--tests",
            "*ExistingProjectAdmissionTest",
        ),
        "*ExistingProjectAdmissionTest",
    ),
}

internal enum class Kvp014GateOutcome { COMPLETE }

internal data class Kvp014ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val firewallPredecessor: AdmittedProofReceipt,
    val compatibilityPredecessor: AdmittedProofReceipt,
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
    val moduleBuildPath: String,
) {
    private val predecessorDigests = mapOf(
        firewallPredecessor.receiptId.value to firewallPredecessor.digest.value,
        compatibilityPredecessor.receiptId.value to compatibilityPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-014 report bytes ->
     * `AdmittedKvp014ProjectAdmissionReport`.
     *
     * Establishes canonical generated report bytes, the exact build-262 ready-Project fixture,
     * and zero forbidden stronger effects. Expected report failure remains finite until rendered
     * at this outer Gradle boundary; raw JSON is extracted only here.
     */
    fun reportProof(): AdmittedKvp014ProjectAdmissionReport = when (
        val result = AdmittedKvp014ProjectAdmissionReport.admit(boundary.readText(proofReportPath))
    ) {
        is Kvp014ProjectAdmissionReportAdmission.Admitted -> result.report
        is Kvp014ProjectAdmissionReportAdmission.Rejected -> rejectReceipt(
            "KVP-014 existing-Project admission report",
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
            "negativeAuthority" to negativeTestPath,
            "outcome" to Kvp014GateOutcome.COMPLETE.name,
            "testSelector" to Kvp014GateCommand.RED.testSelector,
        ),
        boundary.artifactDigests(listOf(negativeTestPath)),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        report: AdmittedKvp014ProjectAdmissionReport,
    ) = boundary.expectation(
        greenReceiptId,
        greenGateId,
        greenCommand,
        taskInputDigest,
        predecessorDigests + (red.receiptId.value to red.digest.value),
        mapOf(
            "authority" to "EXISTING_IDE_PROJECT",
            "canonicalRoot" to report.canonicalRoot,
            "gradleImportCount" to "0",
            "gradleLinkCount" to "0",
            "gradleModel" to "COMPLETE",
            "hostCompatibility" to "EXACT",
            "ideBuild" to report.ideBuild,
            "indexingState" to "SMART",
            "indexingWaitCount" to "0",
            "kotlinMode" to "K2",
            "kotlinPluginBuild" to report.kotlinPluginBuild,
            "outcome" to Kvp014GateOutcome.COMPLETE.name,
            "projectLifecycle" to "OPEN_INITIALIZED",
            "projectOpenCount" to "0",
            "repositoryWalkCount" to "0",
            "schemaVersion" to "1",
            "sourceHashCount" to "0",
            "testSelector" to Kvp014GateCommand.GREEN.testSelector,
            "vfsRefreshCount" to "0",
        ),
        boundary.artifactDigests(
            listOf(proofReportPath, positiveTestPath, moduleBuildPath),
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
}

abstract class Kvp014ReceiptTaskBase : Kvp012ReceiptTaskBase() {
    @get:Input abstract val projectAdmissionTaskId: Property<String>
    @get:Input abstract val projectAdmissionRedGateId: Property<String>
    @get:Input abstract val projectAdmissionGreenGateId: Property<String>
    @get:Input abstract val projectAdmissionCompletionGateId: Property<String>
    @get:Input abstract val projectAdmissionRedReceiptId: Property<String>
    @get:Input abstract val projectAdmissionGreenReceiptId: Property<String>
    @get:Input abstract val projectAdmissionCompletionReceiptId: Property<String>
    @get:Input abstract val projectAdmissionRedCommand: Property<String>
    @get:Input abstract val projectAdmissionGreenCommand: Property<String>
    @get:Input abstract val projectAdmissionCompletionCommand: Property<String>
    @get:Input abstract val projectAdmissionTaskInputDigest: Property<String>
    @get:Input abstract val projectAdmissionCompletionInputDigest: Property<String>
    @get:Input abstract val projectAdmissionProofReportPath: Property<String>
    @get:Input abstract val projectAdmissionNegativeTestPath: Property<String>
    @get:Input abstract val projectAdmissionPositiveTestPath: Property<String>
    @get:Input abstract val projectAdmissionModuleBuildPath: Property<String>

    @get:InputFile abstract val directCompatibilityRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityProofReportFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val projectAdmissionNegativeTestFile: RegularFileProperty
    @get:InputFile abstract val projectAdmissionPositiveTestFile: RegularFileProperty
    @get:InputFile abstract val projectAdmissionModuleBuildFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-014 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments
     * leave only at Gradle exec.
     */
    internal fun <T> afterProjectAdmissionGate(
        command: String,
        gate: Kvp014GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-014 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-014 inputs plus `AuthorityGitRevision` ->
     * `Kvp014ReceiptContexts`.
     *
     * Establishes independent direct admission of the complete KVP-009 and KVP-012 receipt
     * closures. Expected receipt failures remain closed until rendered at the Gradle boundary.
     */
    internal fun projectAdmissionContexts(head: AuthorityGitRevision): Kvp014ReceiptContexts {
        val firewall = firewallContexts(head)
        val firewallProof = firewall.reportProof()
        val firewallRed = firewall.boundary.admit(
            directFirewallRedReceiptFile.get().asFile.toPath(),
            firewall.redExpectation(firewallProof),
        )
        val firewallGreen = firewall.boundary.admit(
            directFirewallGreenReceiptFile.get().asFile.toPath(),
            firewall.greenExpectation(firewallRed, firewallProof),
        )
        val firewallCompletion = firewall.boundary.admit(
            directFirewallCompletionReceiptFile.get().asFile.toPath(),
            firewall.completionExpectation(firewallRed, firewallGreen),
        )
        val compatibility = compatibilityContexts(head)
        val compatibilityProof = compatibility.reportProof()
        val compatibilityRed = compatibility.boundary.admit(
            directCompatibilityRedReceiptFile.get().asFile.toPath(),
            compatibility.redExpectation(),
        )
        val compatibilityGreen = compatibility.boundary.admit(
            directCompatibilityGreenReceiptFile.get().asFile.toPath(),
            compatibility.greenExpectation(compatibilityRed, compatibilityProof),
        )
        val compatibilityCompletion = compatibility.boundary.admit(
            directCompatibilityCompletionReceiptFile.get().asFile.toPath(),
            compatibility.completionExpectation(compatibilityRed, compatibilityGreen),
        )
        return Kvp014ReceiptContexts(
            compatibility.boundary,
            firewallCompletion,
            compatibilityCompletion,
            projectAdmissionTaskId.get(),
            projectAdmissionRedGateId.get(),
            projectAdmissionGreenGateId.get(),
            projectAdmissionCompletionGateId.get(),
            projectAdmissionRedReceiptId.get(),
            projectAdmissionGreenReceiptId.get(),
            projectAdmissionCompletionReceiptId.get(),
            projectAdmissionRedCommand.get(),
            projectAdmissionGreenCommand.get(),
            projectAdmissionCompletionCommand.get(),
            projectAdmissionTaskInputDigest.get(),
            projectAdmissionCompletionInputDigest.get(),
            projectAdmissionProofReportPath.get(),
            projectAdmissionNegativeTestPath.get(),
            projectAdmissionPositiveTestPath.get(),
            projectAdmissionModuleBuildPath.get(),
        )
    }
}
