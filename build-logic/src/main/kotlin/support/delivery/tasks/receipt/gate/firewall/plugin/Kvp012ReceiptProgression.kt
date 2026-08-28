package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import support.plugin.AdmittedIdeHostCompatibilityReport
import support.plugin.IdeHostReportCapability
import support.plugin.IdeHostReportCapabilitySet
import support.plugin.IdeHostCompatibilityReportAdmission

internal enum class Kvp012GateCommand(
    val declaredCommand: String,
    val testSelector: String,
) {
    RED(
        "./gradlew :ide-plugin:test --tests \"*IdeHostCompatibilityNegativeTest\"",
        "*IdeHostCompatibilityNegativeTest",
    ),
    GREEN(
        "./gradlew :ide-plugin:generateIdeHostCompatibilityReport " +
            ":ide-plugin:test --tests \"*IdeHostCompatibilityTest\"",
        "*IdeHostCompatibilityTest",
    ),
    ;

    fun arguments(): List<String> = when (this) {
        RED -> listOf(":ide-plugin:test", "--tests", testSelector)
        GREEN -> listOf(
            ":ide-plugin:generateIdeHostCompatibilityReport",
            ":ide-plugin:test",
            "--tests",
            testSelector,
        )
    }
}

internal enum class Kvp012GateOutcome { COMPLETE }

internal data class Kvp012ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val typeModelPredecessor: AdmittedProofReceipt,
    val standalonePluginPredecessor: AdmittedProofReceipt,
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
    val operationRegistryPath: String,
    val expectedKastPluginVersion: String,
    val negativeTestPath: String,
) {
    private val predecessorDigests = mapOf(
        typeModelPredecessor.receiptId.value to typeModelPredecessor.digest.value,
        standalonePluginPredecessor.receiptId.value to standalonePluginPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-012 report plus physical registry bytes ->
     * `AdmittedIdeHostCompatibilityReport`.
     *
     * Establishes the closed report schema, exact host tuple, canonical capabilities, physical
     * registry digest, and canonical wire digest. Expected report failure remains finite until
     * rendered at this outer Gradle boundary; raw JSON and bytes are extracted only here.
     */
    fun reportProof(): AdmittedIdeHostCompatibilityReport = when (
        val result = AdmittedIdeHostCompatibilityReport.admit(
            boundary.readText(proofReportPath),
            boundary.readText(operationRegistryPath).toByteArray(),
            expectedKastPluginVersion,
        )
    ) {
        is IdeHostCompatibilityReportAdmission.Complete -> result.report
        is IdeHostCompatibilityReportAdmission.Rejected -> rejectReceipt(
            "KVP-012 compatibility report",
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
            "outcome" to Kvp012GateOutcome.COMPLETE.name,
            "negativeAuthority" to negativeTestPath,
            "testSelector" to Kvp012GateCommand.RED.testSelector,
        ),
        boundary.artifactDigests(listOf(negativeTestPath)),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        proof: AdmittedIdeHostCompatibilityReport,
    ) = boundary.expectation(
        greenReceiptId,
        greenGateId,
        greenCommand,
        taskInputDigest,
        predecessorDigests + (red.receiptId.value to red.digest.value),
        mapOf(
            "capabilities" to when (proof.capabilitySet) {
                IdeHostReportCapabilitySet.CANONICAL -> IdeHostReportCapability.entries
                    .joinToString(",") { it.operationId }
            },
            "ideBuild" to proof.ideBuild.value,
            "kastPluginVersion" to proof.kastPluginVersion.value,
            "kotlinPluginBuild" to proof.kotlinPluginBuild.value,
            "operationRegistryDigest" to proof.operationRegistryDigest.value,
            "outcome" to Kvp012GateOutcome.COMPLETE.name,
            "runtimeProtocolIdentity" to proof.runtimeProtocolIdentity.value,
            "schemaVersion" to proof.schemaVersion.value.toString(),
            "taskId" to proof.taskId.value,
            "testSelector" to Kvp012GateCommand.GREEN.testSelector,
            "wireSchemaDigest" to proof.wireSchemaDigest.value,
        ),
        boundary.artifactDigests(listOf(proofReportPath)),
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

abstract class Kvp012ReceiptTaskBase : Kvp010ReceiptTaskBase() {
    @get:Input abstract val compatibilityTaskId: Property<String>
    @get:Input abstract val compatibilityRedGateId: Property<String>
    @get:Input abstract val compatibilityGreenGateId: Property<String>
    @get:Input abstract val compatibilityCompletionGateId: Property<String>
    @get:Input abstract val compatibilityRedReceiptId: Property<String>
    @get:Input abstract val compatibilityGreenReceiptId: Property<String>
    @get:Input abstract val compatibilityCompletionReceiptId: Property<String>
    @get:Input abstract val compatibilityRedCommand: Property<String>
    @get:Input abstract val compatibilityGreenCommand: Property<String>
    @get:Input abstract val compatibilityCompletionCommand: Property<String>
    @get:Input abstract val compatibilityTaskInputDigest: Property<String>
    @get:Input abstract val compatibilityCompletionInputDigest: Property<String>
    @get:Input abstract val compatibilityProofReportPath: Property<String>
    @get:Input abstract val operationRegistryPath: Property<String>
    @get:Input abstract val expectedKastPluginVersion: Property<String>
    @get:Input abstract val compatibilityNegativeTestPath: Property<String>

    @get:InputFile abstract val directTypeModelRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directTypeModelGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directTypeModelProofReportFile: RegularFileProperty
    @get:InputFile abstract val directTypeModelCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val directStandaloneRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directStandaloneGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directStandaloneProofReportFile: RegularFileProperty
    @get:InputFile abstract val directStandaloneCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val compatibilityNegativeTestFile: RegularFileProperty
    @get:Internal abstract val operationRegistryFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-012 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments
     * leave only at Gradle exec and no successful-execution token can be manufactured.
     */
    internal fun <T> afterCompatibilityGate(
        command: String,
        gate: Kvp012GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-012 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments())
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-012 inputs plus `AuthorityGitRevision` ->
     * `Kvp012ReceiptContexts`.
     *
     * Establishes independent direct admission of the complete KVP-002 and KVP-010 receipt
     * closures. Expected receipt failures remain closed until rendered at the outer Gradle
     * boundary; raw properties stay here.
     */
    internal fun compatibilityContexts(head: AuthorityGitRevision): Kvp012ReceiptContexts {
        val typeModel = contexts(head)
        val typeProof = typeModel.reportProof()
        val typeRed = typeModel.boundary.admit(
            directTypeModelRedReceiptFile.get().asFile.toPath(),
            typeModel.redExpectation(typeProof),
        )
        val typeGreen = typeModel.boundary.admit(
            directTypeModelGreenReceiptFile.get().asFile.toPath(),
            typeModel.greenExpectation(typeRed, typeProof),
        )
        val typeCompletion = typeModel.boundary.admit(
            directTypeModelCompletionReceiptFile.get().asFile.toPath(),
            typeModel.completionExpectation(typeRed, typeGreen),
        )
        val standalone = standalonePluginContexts(head)
        val standaloneProof = standalone.reportProof()
        val standaloneRed = standalone.boundary.admit(
            directStandaloneRedReceiptFile.get().asFile.toPath(),
            standalone.redExpectation(standalone.negativeProof()),
        )
        val standaloneGreen = standalone.boundary.admit(
            directStandaloneGreenReceiptFile.get().asFile.toPath(),
            standalone.greenExpectation(standaloneRed, standaloneProof),
        )
        val standaloneCompletion = standalone.boundary.admit(
            directStandaloneCompletionReceiptFile.get().asFile.toPath(),
            standalone.completionExpectation(standaloneRed, standaloneGreen),
        )
        return Kvp012ReceiptContexts(
            standalone.boundary,
            typeCompletion,
            standaloneCompletion,
            compatibilityTaskId.get(),
            compatibilityRedGateId.get(),
            compatibilityGreenGateId.get(),
            compatibilityCompletionGateId.get(),
            compatibilityRedReceiptId.get(),
            compatibilityGreenReceiptId.get(),
            compatibilityCompletionReceiptId.get(),
            compatibilityRedCommand.get(),
            compatibilityGreenCommand.get(),
            compatibilityCompletionCommand.get(),
            compatibilityTaskInputDigest.get(),
            compatibilityCompletionInputDigest.get(),
            compatibilityProofReportPath.get(),
            operationRegistryPath.get(),
            expectedKastPluginVersion.get(),
            compatibilityNegativeTestPath.get(),
        )
    }
}
