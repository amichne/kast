package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import support.plugin.StandalonePluginArchiveResult
import support.plugin.StandalonePluginNegativeProof
import support.plugin.StandalonePluginNegativeProofResult
import support.plugin.StandalonePluginReportResult
import support.plugin.VerifiedStandalonePluginReport
import support.plugin.decodeStandalonePluginReport
import support.plugin.deriveStandalonePluginNegativeProof
import support.plugin.verifyStandalonePluginArchive

internal enum class Kvp010GateCommand { RED, GREEN }

internal data class Kvp010ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessor: AdmittedProofReceipt,
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
) {
    private val predecessorDigests = mapOf(predecessor.receiptId.value to predecessor.digest.value)

    /**
     * Proof transition: fixed standalone-plugin rejection fixtures ->
     * `StandalonePluginNegativeProof`. Establishes the exact finite KVP-010 rejection set.
     * Expected proof drift remains closed until rendered at this outer Gradle boundary.
     */
    fun negativeProof(): StandalonePluginNegativeProof = when (
        val result = deriveStandalonePluginNegativeProof()
    ) {
        is StandalonePluginNegativeProofResult.Complete -> result.proof
        is StandalonePluginNegativeProofResult.Rejected -> rejectReceipt(
            "KVP-010 negative proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    /**
     * Proof transition: configured KVP-010 report and repository archive ->
     * `VerifiedStandalonePluginReport`. Establishes closed-schema report admission and exact
     * physical ZIP equality. Expected report and archive failures remain finite until rendered at
     * this outer Gradle boundary; raw JSON and paths are extracted only here.
     */
    fun reportProof(): VerifiedStandalonePluginReport {
        val decoded = when (val result = decodeStandalonePluginReport(boundary.readText(proofReportPath))) {
            is StandalonePluginReportResult.Complete -> result.report
            is StandalonePluginReportResult.Rejected -> rejectReceipt(
                "KVP-010 plugin report",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.name,
            )
        }
        return when (val result = verifyStandalonePluginArchive(boundary.repositoryRoot, decoded)) {
            is StandalonePluginArchiveResult.Complete -> result.report
            is StandalonePluginArchiveResult.Rejected -> rejectReceipt(
                "KVP-010 plugin archive",
                ProofReceiptFailure.MALFORMED_ARTIFACT_DIGESTS,
                result.failure.name,
            )
        }
    }

    fun redExpectation(proof: StandalonePluginNegativeProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "rejectedCaseCount" to proof.failures.size.toString(),
            "rejectedFailures" to proof.failures.map { it.name }.sorted().joinToString(","),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        proof: VerifiedStandalonePluginReport,
    ): ProofReceiptExpectation {
        val report = proof.report
        val artifactPath = report.artifact.path.value
        return boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "artifactSizeBytes" to report.artifact.size.value.toString(),
                "descriptorJarEntry" to report.descriptorJarEntry.value,
                "payloadJarCount" to report.payloadJars.size.toString(),
                "pluginId" to report.pluginId.value,
                "schemaVersion" to report.schemaVersion.value.toString(),
            ),
            boundary.artifactDigests(listOf(proofReportPath, artifactPath)),
            taskId,
        )
    }

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

abstract class Kvp010ReceiptTaskBase : Kvp009ReceiptTaskBase() {
    @get:Input abstract val standalonePluginTaskId: Property<String>
    @get:Input abstract val standalonePluginRedGateId: Property<String>
    @get:Input abstract val standalonePluginGreenGateId: Property<String>
    @get:Input abstract val standalonePluginCompletionGateId: Property<String>
    @get:Input abstract val standalonePluginRedReceiptId: Property<String>
    @get:Input abstract val standalonePluginGreenReceiptId: Property<String>
    @get:Input abstract val standalonePluginCompletionReceiptId: Property<String>
    @get:Input abstract val standalonePluginRedCommand: Property<String>
    @get:Input abstract val standalonePluginGreenCommand: Property<String>
    @get:Input abstract val standalonePluginCompletionCommand: Property<String>
    @get:Input abstract val standalonePluginTaskInputDigest: Property<String>
    @get:Input abstract val standalonePluginCompletionInputDigest: Property<String>
    @get:Input abstract val standalonePluginProofReportPath: Property<String>
    @get:InputFile abstract val directFirewallRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directFirewallGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directFirewallProofReportFile: RegularFileProperty
    @get:InputFile abstract val directFirewallCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-010 command plus closed gate identity -> successful process.
     * Establishes exact command equality and zero exit. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runStandalonePluginGate(command: String, gate: Kvp010GateCommand) {
        val expected = when (gate) {
            Kvp010GateCommand.RED -> "./gradlew :ide-plugin:standalonePluginNegativeProof"
            Kvp010GateCommand.GREEN -> "./gradlew :ide-plugin:buildPlugin"
        }
        if (command != expected) {
            rejectReceipt("KVP-010 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        val argument = when (gate) {
            Kvp010GateCommand.RED -> ":ide-plugin:standalonePluginNegativeProof"
            Kvp010GateCommand.GREEN -> ":ide-plugin:buildPlugin"
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", argument)
        }
    }

    /**
     * Proof transition: configured KVP-010 inputs plus `AuthorityGitRevision` ->
     * `Kvp010ReceiptContexts`. Establishes direct admission of KVP-009 completion and its complete
     * transitive closure. Expected receipt failures remain closed until rendered at the outer
     * Gradle boundary.
     */
    internal fun standalonePluginContexts(head: AuthorityGitRevision): Kvp010ReceiptContexts {
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
        return Kvp010ReceiptContexts(
            firewall.boundary,
            firewallCompletion,
            standalonePluginTaskId.get(),
            standalonePluginRedGateId.get(),
            standalonePluginGreenGateId.get(),
            standalonePluginCompletionGateId.get(),
            standalonePluginRedReceiptId.get(),
            standalonePluginGreenReceiptId.get(),
            standalonePluginCompletionReceiptId.get(),
            standalonePluginRedCommand.get(),
            standalonePluginGreenCommand.get(),
            standalonePluginCompletionCommand.get(),
            standalonePluginTaskInputDigest.get(),
            standalonePluginCompletionInputDigest.get(),
            standalonePluginProofReportPath.get(),
        )
    }
}
