package support.delivery
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import support.architecture.AdmittedKvp018NoWalkReport
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ForbiddenEffect
import support.architecture.HostedReadForbiddenAuthority
import support.architecture.HostedReadPathDerivation
import support.architecture.HostedReadPathDeriver
import support.architecture.HostedReadPathReportAdmission
import support.architecture.HostedReadInventoryScope
import support.architecture.Kvp018PredecessorReceipts
import support.architecture.Kvp018RequiredForbiddenFamily
import support.architecture.ModuleId
import support.architecture.ModuleRole
import support.architecture.VfsPassiveHostedModelCapture
import support.architecture.admitHostedReadPathReport
import support.architecture.receiptIdValue
import support.architecture.gradle.HostedReadClassInputResult
import support.architecture.gradle.HostedReadExternalInputResult
import support.architecture.gradle.HostedReadProjectInputResult
import support.architecture.gradle.canonicalArchitecturePolicy
import support.architecture.gradle.loadHostedReadClassInputs
import support.architecture.gradle.loadHostedReadExternalInputs
import support.architecture.gradle.loadHostedReadProjectInputs
internal enum class Kvp018GateCommand(
    val declaredCommand: String,
    val arguments: List<String>,
    val taskPath: String,
) {
    RED(
        "./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalkNegative",
        listOf(":workspace:intellij-read:verifyNoHostedRepositoryWalkNegative"),
        ":workspace:intellij-read:verifyNoHostedRepositoryWalkNegative",
    ),
    GREEN(
        "./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalk",
        listOf(":workspace:intellij-read:verifyNoHostedRepositoryWalk"),
        ":workspace:intellij-read:verifyNoHostedRepositoryWalk",
    ),
}

internal enum class Kvp018GateOutcome { COMPLETE }

internal class Kvp018ReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    private val predecessors: Kvp018PredecessorReceipts,
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
    private val compiledClassDirectories: Set<java.io.File>,
    private val requiredClassNames: Set<String>,
    private val runtimeProjectArtifactIdentities: List<String>,
    private val runtimeProjectArtifactFiles: Set<java.io.File>,
    private val runtimeExternalArtifactIdentities: List<String>,
    private val runtimeExternalArtifactFiles: Set<java.io.File>,
) {
    private val predecessorDigests = predecessors.artifacts().associate {
        it.id.receiptIdValue to it.sha256
    }

    internal companion object {
        /** Proof transition: configured boundary values plus `Kvp018DependencyReceiptContexts ->
         * Kvp018ReceiptContexts`; preserves predecessors and snapshots Gradle collections. */
        fun capture(
            dependencies: Kvp018DependencyReceiptContexts,
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
            compiledClassDirectories: Set<java.io.File>,
            requiredClassNames: Set<String>,
            runtimeProjectArtifactIdentities: List<String>,
            runtimeProjectArtifactFiles: Set<java.io.File>,
            runtimeExternalArtifactIdentities: List<String>,
            runtimeExternalArtifactFiles: Set<java.io.File>,
        ) = Kvp018ReceiptContexts(
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
            compiledClassDirectories.toSet(),
            requiredClassNames.toSet(),
            runtimeProjectArtifactIdentities.toList(),
            runtimeProjectArtifactFiles.toSet(),
            runtimeExternalArtifactIdentities.toList(),
            runtimeExternalArtifactFiles.toSet(),
        )
    }

    /**
     * Proof transition: `Kvp018ReceiptContexts -> AdmittedKvp018NoWalkReport`.
     *
     * Establishes exact identity/scope, complete class-inventory evidence, all finite injected
     * authorities, all-zero prohibited effects, and canonical predecessor-bound bytes. Expected
     * report failures remain finite until rendered at this Gradle receipt boundary.
     */
    private fun reportProof(): AdmittedKvp018NoWalkReport = when (
        val result = admitHostedReadPathReport(
            boundary.readText(proofReportPath),
            independentlyDerivedProof(),
            predecessors,
        )
    ) {
        is HostedReadPathReportAdmission.Admitted -> result.report
        is HostedReadPathReportAdmission.Rejected -> rejectReceipt(
            "KVP-018 no-walk report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    /**
     * Proof transition: `Kvp018ReceiptContexts -> VfsPassiveHostedModelCapture`.
     *
     * Establishes exact class, project-artifact, and external-artifact input refinement followed
     * by the complete [HostedReadPathDerivation]. Their closed failures are rendered only at this
     * Gradle receipt boundary; raw configured identities and bytes enter only here.
     */
    private fun independentlyDerivedProof(): VfsPassiveHostedModelCapture = when (val loaded =
        loadHostedReadClassInputs(compiledClassDirectories)
    ) {
        is HostedReadClassInputResult.Rejected -> rejectReceipt(
            "KVP-018 hosted class inventory",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            loaded.failure.toString(),
        )
        is HostedReadClassInputResult.Loaded -> {
            val projectJars = when (val projects = loadHostedReadProjectInputs(
                runtimeProjectArtifactIdentities,
                runtimeProjectArtifactFiles,
            )) {
                is HostedReadProjectInputResult.Loaded -> projects.jars
                is HostedReadProjectInputResult.Rejected -> rejectReceipt(
                    "KVP-018 project runtime inputs",
                    ProofReceiptFailure.MALFORMED_OBSERVATION,
                    projects.failure.toString(),
                )
            }
            val externalJars = when (val external = loadHostedReadExternalInputs(
                runtimeExternalArtifactIdentities,
                runtimeExternalArtifactFiles,
            )) {
                is HostedReadExternalInputResult.Loaded -> external.jars
                is HostedReadExternalInputResult.Rejected -> rejectReceipt(
                    "KVP-018 external runtime inputs",
                    ProofReceiptFailure.MALFORMED_OBSERVATION,
                    external.failure.toString(),
                )
            }
            val architecture = when (val policy = canonicalArchitecturePolicy()) {
                is ArchitecturePolicyValidation.Valid -> policy.architecture
                is ArchitecturePolicyValidation.Invalid -> rejectReceipt(
                    "KVP-018 canonical architecture",
                    ProofReceiptFailure.MALFORMED_OBSERVATION,
                    policy.failures.toString(),
                )
            }
            when (val result = HostedReadPathDeriver.derive(
                architecture,
                loaded.classes,
                requiredClassNames,
                projectJars,
                externalJars,
            )) {
                is HostedReadPathDerivation.Derived -> result.proof
                else -> rejectReceipt(
                    "KVP-018 hosted path derivation",
                    ProofReceiptFailure.MALFORMED_OBSERVATION,
                    result.toString(),
                )
            }
        }
    }

    /**
     * Proof transition: `Kvp018ReceiptContexts -> ProofReceiptExpectation`.
     * Establishes the exact negative family ledger and predecessor closure. Malformed configured
     * values remain closed [ProofReceiptFailure] data until the receipt boundary renders them.
     */
    fun redExpectation(): ProofReceiptExpectation = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "forbiddenFamilyCount" to HostedReadForbiddenAuthority.entries
                .mapTo(linkedSetOf()) { it.requiredEffect }
                .size.toString(),
            "injectedForbiddenAuthorityCount" to
                Kvp018RequiredForbiddenFamily.entries.size.toString(),
            "outcome" to Kvp018GateOutcome.COMPLETE.name,
            "taskPath" to Kvp018GateCommand.RED.taskPath,
        ),
        boundary.artifactDigests(redArtifactPaths),
        taskId,
    )

    /**
     * Proof transition: `(Kvp018ReceiptContexts, AdmittedProofReceipt) ->
     * ProofReceiptExpectation`.
     *
     * Establishes the exact admitted report observations and hashes those same canonical report
     * bytes with the GREEN artifacts. Raw observation maps leave only through
     * [Kvp001ReceiptContext.expectation]; expected failures remain [ProofReceiptFailure].
     */
    fun greenExpectation(red: AdmittedProofReceipt): ProofReceiptExpectation {
        val report = reportProof()
        val artifacts = boundary.artifactDigests(greenArtifactPaths).toMutableMap()
        artifacts[proofReportPath] = sha256Bytes(
            report.canonicalDocumentAtReportBoundary().toByteArray(Charsets.UTF_8),
        )
        return boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "allowedEffect" to ForbiddenEffect.INTELLIJ_PLATFORM.name,
                "allowedIntellijReferenceCount" to report.allowedIntellijReferenceCount.toString(),
                "classCount" to report.classCount.toString(),
                "classInventoryDigest" to report.classInventoryDigest,
                "injectedForbiddenAuthorityCount" to
                    report.injectedForbiddenAuthorityCount.toString(),
                "inventoryScope" to HostedReadInventoryScope.COMPILED_MAIN_CLASSES.name,
                "module" to ModuleId.WORKSPACE_INTELLIJ_READ.projectPath,
                "observedForbiddenReferenceCount" to "0",
                "outcome" to Kvp018GateOutcome.COMPLETE.name,
                "prohibitedEffectCount" to report.prohibitedEffectCount.toString(),
                "role" to ModuleRole.IDE_READ_ONLY.name,
                "runtimeProjectCount" to report.runtimeProjectCount.toString(),
                "runtimeProjectArtifactSetSha256" to report.runtimeProjectArtifactSetSha256,
                "runtimeProjectClassCount" to report.runtimeProjectClassCount.toString(),
                "runtimeExternalArtifactCount" to report.runtimeExternalArtifactCount.toString(),
                "runtimeExternalArtifactSetSha256" to report.runtimeExternalArtifactSetSha256,
                "runtimeExternalClassCount" to report.runtimeExternalClassCount.toString(),
                "runtimeExternalGenericFilesystemPrimitiveReferenceCount" to
                    report.runtimeExternalGenericFilesystemPrimitiveReferenceCount.toString(),
                "taskPath" to Kvp018GateCommand.GREEN.taskPath,
            ),
            artifacts,
            taskId,
        )
    }

    /**
     * Proof transition: admitted KVP-018 RED and GREEN receipts -> `ProofReceiptExpectation`.
     * Establishes the exact two-gate closure over the two admitted predecessors; malformed raw
     * maps remain closed [ProofReceiptFailure] at [Kvp001ReceiptContext.expectation].
     */
    fun completionExpectation(
        red: AdmittedProofReceipt,
        green: AdmittedProofReceipt,
    ): ProofReceiptExpectation =
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

abstract class Kvp018ReceiptTaskBase : Kvp018DependencyReceiptTaskBase() {
    @get:Input abstract val hostedTaskId: Property<String>
    @get:Input abstract val hostedRedGateId: Property<String>
    @get:Input abstract val hostedGreenGateId: Property<String>
    @get:Input abstract val hostedCompletionGateId: Property<String>
    @get:Input abstract val hostedRedReceiptId: Property<String>
    @get:Input abstract val hostedGreenReceiptId: Property<String>
    @get:Input abstract val hostedCompletionReceiptId: Property<String>
    @get:Input abstract val hostedRedCommand: Property<String>
    @get:Input abstract val hostedGreenCommand: Property<String>
    @get:Input abstract val hostedCompletionCommand: Property<String>
    @get:Input abstract val hostedTaskInputDigest: Property<String>
    @get:Input abstract val hostedCompletionInputDigest: Property<String>
    @get:Input abstract val hostedProofReportPath: Property<String>
    @get:Input abstract val hostedRedArtifactPaths: ListProperty<String>
    @get:Input abstract val hostedGreenArtifactPaths: ListProperty<String>
    @get:Input abstract val hostedRequiredClassNames: ListProperty<String>
    @get:Input abstract val hostedRuntimeProjectArtifactIdentities: ListProperty<String>
    @get:Input abstract val hostedRuntimeExternalArtifactIdentities: ListProperty<String>

    @get:InputFiles abstract val hostedRedArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val hostedGreenArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val hostedCompiledClassDirectories: ConfigurableFileCollection
    @get:InputFiles abstract val hostedRuntimeProjectArtifactFiles: ConfigurableFileCollection
    @get:InputFiles abstract val hostedRuntimeExternalArtifactFiles: ConfigurableFileCollection

    /**
     * Proof transition: declared KVP-018 command plus continuation -> continuation result.
     * Establishes exact command equality and invokes the continuation only after zero exit.
     * Command mismatch is finite [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave
     * only at the Gradle exec boundary.
     */
    internal fun <T> afterHostedGate(
        command: String,
        gate: Kvp018GateCommand,
        onComplete: () -> T,
    ): T {
        if (command != gate.declaredCommand) rejectReceipt(
            "KVP-018 gate command",
            ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
        )
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + gate.arguments)
        }
        return onComplete()
    }

    /**
     * Proof transition: configured KVP-018 inputs plus `AuthorityGitRevision` ->
     * `Kvp018ReceiptContexts`.
     *
     * Establishes direct admission of both complete sibling predecessor closures at one exact
     * head. Expected receipt failures remain closed [ProofReceiptFailure] values until rendered at
     * this Gradle boundary; raw receipt extraction is permitted only by the dependency transition.
     */
    internal fun hostedContexts(head: AuthorityGitRevision): Kvp018ReceiptContexts {
        val dependencies = dependencyContexts(head)
        return Kvp018ReceiptContexts.capture(
            dependencies,
            hostedTaskId.get(),
            hostedRedGateId.get(),
            hostedGreenGateId.get(),
            hostedCompletionGateId.get(),
            hostedRedReceiptId.get(),
            hostedGreenReceiptId.get(),
            hostedCompletionReceiptId.get(),
            hostedRedCommand.get(),
            hostedGreenCommand.get(),
            hostedCompletionCommand.get(),
            hostedTaskInputDigest.get(),
            hostedCompletionInputDigest.get(),
            hostedProofReportPath.get(),
            hostedRedArtifactPaths.get(),
            hostedGreenArtifactPaths.get(),
            hostedCompiledClassDirectories.files,
            hostedRequiredClassNames.get().toSet(),
            hostedRuntimeProjectArtifactIdentities.get(),
            hostedRuntimeProjectArtifactFiles.files,
            hostedRuntimeExternalArtifactIdentities.get(),
            hostedRuntimeExternalArtifactFiles.files,
        )
    }
}
