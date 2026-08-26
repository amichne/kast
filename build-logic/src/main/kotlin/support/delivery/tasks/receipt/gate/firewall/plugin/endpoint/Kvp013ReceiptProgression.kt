package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import support.plugin.IdeHostReportCapability

internal enum class Kvp013GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
) {
    RED(
        "./gradlew :protocol:wire:test --tests \"*IdeEndpointDescriptorNegativeTest\"",
        listOf(":protocol:wire:test", "--tests", "*IdeEndpointDescriptorNegativeTest"),
    ),
    GREEN(
        "./gradlew :protocol:wire:generateIdeEndpointDescriptorReport " +
            ":protocol:wire:test --tests \"*IdeEndpointDescriptorTest\"",
        listOf(
            ":protocol:wire:generateIdeEndpointDescriptorReport",
            ":protocol:wire:test",
            "--tests",
            "*IdeEndpointDescriptorTest",
        ),
    ),
}

internal enum class Kvp013GateOutcome { COMPLETE }

internal data class Kvp013ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val projectionPredecessor: AdmittedProofReceipt,
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
    val endpointSchemaPath: String,
    val negativeTestPath: String,
    val positiveTestPath: String,
) {
    private val predecessorDigests = mapOf(
        projectionPredecessor.receiptId.value to projectionPredecessor.digest.value,
        compatibilityPredecessor.receiptId.value to compatibilityPredecessor.digest.value,
    )

    /**
     * Proof transition: configured KVP-013 report bytes ->
     * `AdmittedKvp013EndpointDescriptor`.
     * Establishes the canonical closed descriptor, compatibility syntax, and capability identity.
     * Expected report failure remains finite until rendered at this outer Gradle boundary; raw
     * JSON is extracted only here.
     */
    fun reportProof(): AdmittedKvp013EndpointDescriptor = when (
        val result = AdmittedKvp013EndpointDescriptor.admit(boundary.readText(proofReportPath))
    ) {
        is Kvp013EndpointDescriptorAdmission.Complete -> result.descriptor
        is Kvp013EndpointDescriptorAdmission.Rejected -> rejectReceipt(
            "KVP-013 endpoint descriptor report",
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
            "outcome" to Kvp013GateOutcome.COMPLETE.name,
            "testSelector" to "*IdeEndpointDescriptorNegativeTest",
        ),
        boundary.artifactDigests(listOf(negativeTestPath)),
        taskId,
    )

    fun greenExpectation(
        red: AdmittedProofReceipt,
        descriptor: AdmittedKvp013EndpointDescriptor,
    ) = boundary.expectation(
        greenReceiptId,
        greenGateId,
        greenCommand,
        taskInputDigest,
        predecessorDigests + (red.receiptId.value to red.digest.value),
        mapOf(
            "canonicalRoot" to descriptor.canonicalRoot.value,
            "capabilities" to IdeHostReportCapability.entries.joinToString(",") {
                it.operationId
            },
            "framing" to Kvp013EndpointFraming.LENGTH_PREFIXED_JSON_V1.value,
            "hostKind" to Kvp013EndpointHostKind.IDE_PROJECT.value,
            "ideBuild" to descriptor.compatibility.ideBuild,
            "kastPluginVersion" to descriptor.compatibility.kastPluginVersion,
            "kotlinPluginBuild" to descriptor.compatibility.kotlinPluginBuild,
            "operationRegistryDigest" to descriptor.compatibility.operationRegistryDigest,
            "outcome" to Kvp013GateOutcome.COMPLETE.name,
            "processId" to descriptor.processId.value.toString(),
            "runtimeEpoch" to descriptor.runtimeEpoch.value.toString(),
            "runtimeProtocolIdentity" to descriptor.compatibility.runtimeProtocolIdentity,
            "schema" to Kvp013EndpointSchema.V2.value,
            "socketPath" to descriptor.socketPath.value,
            "testSelector" to "*IdeEndpointDescriptorTest",
            "wireSchemaDigest" to descriptor.compatibility.wireSchemaDigest,
        ),
        boundary.artifactDigests(
            listOf(proofReportPath, endpointSchemaPath, positiveTestPath),
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

abstract class Kvp013ReceiptTaskBase : Kvp012ReceiptTaskBase() {
    @get:Input abstract val endpointTaskId: Property<String>
    @get:Input abstract val endpointRedGateId: Property<String>
    @get:Input abstract val endpointGreenGateId: Property<String>
    @get:Input abstract val endpointCompletionGateId: Property<String>
    @get:Input abstract val endpointRedReceiptId: Property<String>
    @get:Input abstract val endpointGreenReceiptId: Property<String>
    @get:Input abstract val endpointCompletionReceiptId: Property<String>
    @get:Input abstract val endpointRedCommand: Property<String>
    @get:Input abstract val endpointGreenCommand: Property<String>
    @get:Input abstract val endpointCompletionCommand: Property<String>
    @get:Input abstract val endpointTaskInputDigest: Property<String>
    @get:Input abstract val endpointCompletionInputDigest: Property<String>
    @get:Input abstract val endpointProofReportPath: Property<String>
    @get:Input abstract val endpointSchemaPath: Property<String>
    @get:Input abstract val endpointNegativeTestPath: Property<String>
    @get:Input abstract val endpointPositiveTestPath: Property<String>

    @get:InputFile abstract val directProjectionRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directProjectionGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directProjectionProofReportFile: RegularFileProperty
    @get:InputFile abstract val directProjectionCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityProofReportFile: RegularFileProperty
    @get:InputFile abstract val directCompatibilityCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val endpointSchemaFile: RegularFileProperty
    @get:InputFile abstract val endpointNegativeTestFile: RegularFileProperty
    @get:InputFile abstract val endpointPositiveTestFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-013 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments
     * leave only at Gradle exec.
     */
    internal fun <T> afterEndpointGate(
        command: String,
        gate: Kvp013GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-013 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-013 inputs plus `AuthorityGitRevision` ->
     * `Kvp013ReceiptContexts`.
     * Establishes independent direct admission of the complete KVP-005 and KVP-012 receipt
     * closures. Expected receipt failures remain closed until rendered at the Gradle boundary.
     */
    internal fun endpointContexts(head: AuthorityGitRevision): Kvp013ReceiptContexts {
        val projection = projectionContexts(head)
        val projectionProof = projection.reportProof()
        val projectionRed = projection.boundary.admit(
            directProjectionRedReceiptFile.get().asFile.toPath(),
            projection.redExpectation(projection.negativeProof()),
        )
        val projectionGreen = projection.boundary.admit(
            directProjectionGreenReceiptFile.get().asFile.toPath(),
            projection.greenExpectation(projectionRed, projectionProof),
        )
        val projectionCompletion = projection.boundary.admit(
            directProjectionCompletionReceiptFile.get().asFile.toPath(),
            projection.completionExpectation(projectionRed, projectionGreen),
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
        return Kvp013ReceiptContexts(
            compatibility.boundary,
            projectionCompletion,
            compatibilityCompletion,
            endpointTaskId.get(),
            endpointRedGateId.get(),
            endpointGreenGateId.get(),
            endpointCompletionGateId.get(),
            endpointRedReceiptId.get(),
            endpointGreenReceiptId.get(),
            endpointCompletionReceiptId.get(),
            endpointRedCommand.get(),
            endpointGreenCommand.get(),
            endpointCompletionCommand.get(),
            endpointTaskInputDigest.get(),
            endpointCompletionInputDigest.get(),
            endpointProofReportPath.get(),
            endpointSchemaPath.get(),
            endpointNegativeTestPath.get(),
            endpointPositiveTestPath.get(),
        )
    }
}
