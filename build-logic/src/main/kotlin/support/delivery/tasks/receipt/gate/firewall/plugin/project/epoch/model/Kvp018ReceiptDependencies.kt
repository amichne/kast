package support.delivery

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles

internal enum class Kvp018DependencyMember { DETACHED_MODEL, READ_EPOCH }
internal enum class Kvp018AuthorityField {
    REPOSITORY_ROOT, BASE_REVISION, EXACT_HEAD, PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT, SOURCE_DIGESTS,
}
internal sealed interface Kvp018DependencyContextFailure {
    data class HeadMismatch(val member: Kvp018DependencyMember) :
        Kvp018DependencyContextFailure
    data class AuthoritySnapshotMismatch(val field: Kvp018AuthorityField) :
        Kvp018DependencyContextFailure
    data class PredecessorRejected(val failure: support.architecture.Kvp018PredecessorReceiptFailure) :
        Kvp018DependencyContextFailure
}
internal sealed interface Kvp018DependencyContextRefinement {
    data class Admitted(val context: Kvp018DependencyReceiptContexts) :
        Kvp018DependencyContextRefinement
    data class Rejected(val failure: Kvp018DependencyContextFailure) :
        Kvp018DependencyContextRefinement
}
private sealed interface Kvp018AuthorityComparison {
    data object Same : Kvp018AuthorityComparison
    data class Mismatch(
        val failure: Kvp018DependencyContextFailure.AuthoritySnapshotMismatch,
    ) : Kvp018AuthorityComparison
}
internal class Kvp018DependencyReceiptContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val predecessors: support.architecture.Kvp018PredecessorReceipts,
) {
    internal companion object {
        /**
         * Proof transition: `(AuthorityGitRevision, Kvp001ReceiptContext, AdmittedProofReceipt,
         * Kvp001ReceiptContext, AdmittedProofReceipt) -> Kvp018DependencyContextRefinement`.
         *
         * Establishes one immutable same-head authority snapshot and the exact admitted
         * KVP-016/KVP-017 completion pair. Head, authority, or semantic receipt mismatches remain
         * closed [Kvp018DependencyContextFailure] data. Raw boundary values are copied only here.
         */
        fun refine(
            expectedHead: AuthorityGitRevision,
            detachedBoundary: Kvp001ReceiptContext,
            detachedCompletion: AdmittedProofReceipt,
            readEpochBoundary: Kvp001ReceiptContext,
            readEpochCompletion: AdmittedProofReceipt,
        ): Kvp018DependencyContextRefinement {
            if (detachedBoundary.exactHead != expectedHead.value ||
                detachedCompletion.exactHead != expectedHead
            ) return rejected(Kvp018DependencyContextFailure.HeadMismatch(
                Kvp018DependencyMember.DETACHED_MODEL,
            ))
            if (readEpochBoundary.exactHead != expectedHead.value ||
                readEpochCompletion.exactHead != expectedHead
            ) return rejected(Kvp018DependencyContextFailure.HeadMismatch(
                Kvp018DependencyMember.READ_EPOCH,
            ))
            val detachedSnapshot = detachedBoundary.snapshot()
            val readEpochSnapshot = readEpochBoundary.snapshot()
            when (val comparison = compareAuthority(detachedSnapshot, readEpochSnapshot)) {
                Kvp018AuthorityComparison.Same -> Unit
                is Kvp018AuthorityComparison.Mismatch -> return rejected(comparison.failure)
            }
            val detachedArtifact = when (val result = admittedPredecessor(
                support.architecture.Kvp018PredecessorReceiptId.KVP_016_COMPLETE,
                detachedCompletion,
            )) {
                is support.architecture.Kvp018PredecessorArtifactRefinement.Admitted ->
                    result.artifact
                is support.architecture.Kvp018PredecessorArtifactRefinement.Rejected ->
                    return rejected(Kvp018DependencyContextFailure.PredecessorRejected(
                        result.failure,
                    ))
            }
            val readEpochArtifact = when (val result = admittedPredecessor(
                support.architecture.Kvp018PredecessorReceiptId.KVP_017_COMPLETE,
                readEpochCompletion,
            )) {
                is support.architecture.Kvp018PredecessorArtifactRefinement.Admitted ->
                    result.artifact
                is support.architecture.Kvp018PredecessorArtifactRefinement.Rejected ->
                    return rejected(Kvp018DependencyContextFailure.PredecessorRejected(
                        result.failure,
                    ))
            }
            val predecessors = when (
                val result = support.architecture.Kvp018PredecessorReceipts.refine(
                    listOf(detachedArtifact, readEpochArtifact),
                )
            ) {
                is support.architecture.Kvp018PredecessorReceiptRefinement.Admitted ->
                    result.receipts
                is support.architecture.Kvp018PredecessorReceiptRefinement.Rejected ->
                    return rejected(Kvp018DependencyContextFailure.PredecessorRejected(
                        result.failure,
                    ))
            }
            return Kvp018DependencyContextRefinement.Admitted(
                Kvp018DependencyReceiptContexts(detachedSnapshot, predecessors),
            )
        }

        private fun admittedPredecessor(
            id: support.architecture.Kvp018PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): support.architecture.Kvp018PredecessorArtifactRefinement =
            support.architecture.Kvp018PredecessorReceiptArtifact.fromAdmitted(
                id,
                receipt,
            )

        private fun compareAuthority(
            detached: Kvp001ReceiptContext,
            readEpoch: Kvp001ReceiptContext,
        ): Kvp018AuthorityComparison = when {
            detached.repositoryRoot != readEpoch.repositoryRoot -> mismatch(
                Kvp018AuthorityField.REPOSITORY_ROOT,
            )
            detached.baseRevision != readEpoch.baseRevision -> mismatch(
                Kvp018AuthorityField.BASE_REVISION,
            )
            detached.exactHead != readEpoch.exactHead -> mismatch(
                Kvp018AuthorityField.EXACT_HEAD,
            )
            detached.programFingerprint != readEpoch.programFingerprint -> mismatch(
                Kvp018AuthorityField.PROGRAM_FINGERPRINT,
            )
            detached.requirementFingerprint != readEpoch.requirementFingerprint -> mismatch(
                Kvp018AuthorityField.REQUIREMENT_FINGERPRINT,
            )
            detached.sourceDigests != readEpoch.sourceDigests -> mismatch(
                Kvp018AuthorityField.SOURCE_DIGESTS,
            )
            else -> Kvp018AuthorityComparison.Same
        }

        private fun mismatch(field: Kvp018AuthorityField) =
            Kvp018AuthorityComparison.Mismatch(
                Kvp018DependencyContextFailure.AuthoritySnapshotMismatch(field),
            )

        private fun rejected(failure: Kvp018DependencyContextFailure) =
            Kvp018DependencyContextRefinement.Rejected(failure)
    }
}

private fun Kvp001ReceiptContext.snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

abstract class Kvp018DependencyReceiptTaskBase : Kvp017ReceiptTaskBase() {
    @get:Input abstract val dependencyDetachedTaskId: Property<String>
    @get:Input abstract val dependencyDetachedRedGateId: Property<String>
    @get:Input abstract val dependencyDetachedGreenGateId: Property<String>
    @get:Input abstract val dependencyDetachedCompletionGateId: Property<String>
    @get:Input abstract val dependencyDetachedRedReceiptId: Property<String>
    @get:Input abstract val dependencyDetachedGreenReceiptId: Property<String>
    @get:Input abstract val dependencyDetachedCompletionReceiptId: Property<String>
    @get:Input abstract val dependencyDetachedRedCommand: Property<String>
    @get:Input abstract val dependencyDetachedGreenCommand: Property<String>
    @get:Input abstract val dependencyDetachedCompletionCommand: Property<String>
    @get:Input abstract val dependencyDetachedTaskInputDigest: Property<String>
    @get:Input abstract val dependencyDetachedCompletionInputDigest: Property<String>
    @get:Input abstract val dependencyDetachedProofReportPath: Property<String>
    @get:Input abstract val dependencyDetachedArtifactPaths: ListProperty<String>

    @get:InputFile abstract val directDetachedRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directDetachedGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directDetachedProofReportFile: RegularFileProperty
    @get:InputFile abstract val directDetachedCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val directReadEpochRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directReadEpochGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directReadEpochProofReportFile: RegularFileProperty
    @get:InputFile abstract val directReadEpochCompletionReceiptFile: RegularFileProperty
    @get:InputFiles abstract val dependencyDetachedArtifactFiles: ConfigurableFileCollection

    internal fun configureDetachedDependency(
        detached: TaskReceiptRegistration,
    ) {
        dependencyDetachedTaskId.set(detached.task.id.value)
        dependencyDetachedRedGateId.set(detached.redGate.id)
        dependencyDetachedGreenGateId.set(detached.greenGate.id)
        dependencyDetachedCompletionGateId.set(detached.completionGate.id)
        dependencyDetachedRedReceiptId.set(detached.redGate.outputReceiptId)
        dependencyDetachedGreenReceiptId.set(detached.greenGate.outputReceiptId)
        dependencyDetachedCompletionReceiptId.set(detached.completionGate.outputReceiptId)
        dependencyDetachedRedCommand.set(detached.redGate.command)
        dependencyDetachedGreenCommand.set(detached.greenGate.command)
        dependencyDetachedCompletionCommand.set(detached.completionGate.command)
        dependencyDetachedTaskInputDigest.set(detached.taskInputDigest)
        dependencyDetachedCompletionInputDigest.set(detached.completionInputDigest)
        dependencyDetachedProofReportPath.set(detached.task.outputs.single().path)
        dependencyDetachedArtifactPaths.set(KVP016_ARTIFACT_PATHS)
        directDetachedRedReceiptFile.set(detached.redReceipt)
        directDetachedGreenReceiptFile.set(detached.greenReceipt)
        directDetachedProofReportFile.set(detached.proofReport)
        directDetachedCompletionReceiptFile.set(detached.completionReceipt)
    }

    /**
     * Proof transition: configured KVP-016/KVP-017 inputs plus `AuthorityGitRevision` ->
     * `Kvp018DependencyReceiptContexts`.
     *
     * Establishes complete direct re-admission of both sibling predecessor closures at the same
     * head. Expected receipt/report mismatches remain closed [ProofReceiptFailure] values until
     * rendered at this Gradle boundary. Raw receipt extraction is permitted only here.
     */
    internal fun dependencyContexts(head: AuthorityGitRevision): Kvp018DependencyReceiptContexts {
        val detached = detachedDependencyContexts(head)
        val detachedReport = detached.reportProof()
        val detachedRed = detached.boundary.admit(
            directDetachedRedReceiptFile.get().asFile.toPath(),
            detached.redExpectation(),
        )
        val detachedGreen = detached.boundary.admit(
            directDetachedGreenReceiptFile.get().asFile.toPath(),
            detached.greenExpectation(detachedRed, detachedReport),
        )
        val detachedCompletion = detached.boundary.admit(
            directDetachedCompletionReceiptFile.get().asFile.toPath(),
            detached.completionExpectation(detachedRed, detachedGreen),
        )
        val readEpoch = readEpochContexts(head)
        val readEpochReport = readEpoch.reportProof()
        val readEpochRed = readEpoch.boundary.admit(
            directReadEpochRedReceiptFile.get().asFile.toPath(),
            readEpoch.redExpectation(),
        )
        val readEpochGreen = readEpoch.boundary.admit(
            directReadEpochGreenReceiptFile.get().asFile.toPath(),
            readEpoch.greenExpectation(readEpochRed, readEpochReport),
        )
        val readEpochCompletion = readEpoch.boundary.admit(
            directReadEpochCompletionReceiptFile.get().asFile.toPath(),
            readEpoch.completionExpectation(readEpochRed, readEpochGreen),
        )
        return when (val refined = Kvp018DependencyReceiptContexts.refine(
            head,
            detached.boundary,
            detachedCompletion,
            readEpoch.boundary,
            readEpochCompletion,
        )) {
            is Kvp018DependencyContextRefinement.Admitted -> refined.context
            is Kvp018DependencyContextRefinement.Rejected -> rejectReceipt(
                "KVP-018 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                refined.failure.toString(),
            )
        }
    }

    private fun detachedDependencyContexts(head: AuthorityGitRevision): Kvp016ReceiptContexts {
        val epoch = epochContexts(head)
        val epochReport = epoch.reportProof()
        val epochRed = epoch.boundary.admit(
            directSignalLedgerRedReceiptFile.get().asFile.toPath(),
            epoch.redExpectation(),
        )
        val epochGreen = epoch.boundary.admit(
            directSignalLedgerGreenReceiptFile.get().asFile.toPath(),
            epoch.greenExpectation(epochRed, epochReport),
        )
        val epochCompletion = epoch.boundary.admit(
            directSignalLedgerCompletionReceiptFile.get().asFile.toPath(),
            epoch.completionExpectation(epochRed, epochGreen),
        )
        val paths = when (val refined = Kvp016ArtifactPaths.refine(
            dependencyDetachedArtifactPaths.get(),
        )) {
            is Kvp016ArtifactPathRefinement.Refined -> refined.paths
            Kvp016ArtifactPathRefinement.Rejected -> rejectReceipt(
                "KVP-016 dependency artifact paths",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
            )
        }
        return Kvp016ReceiptContexts(
            epoch.boundary,
            epoch.projectAdmissionPredecessor,
            epochCompletion,
            dependencyDetachedTaskId.get(),
            dependencyDetachedRedGateId.get(),
            dependencyDetachedGreenGateId.get(),
            dependencyDetachedCompletionGateId.get(),
            dependencyDetachedRedReceiptId.get(),
            dependencyDetachedGreenReceiptId.get(),
            dependencyDetachedCompletionReceiptId.get(),
            dependencyDetachedRedCommand.get(),
            dependencyDetachedGreenCommand.get(),
            dependencyDetachedCompletionCommand.get(),
            dependencyDetachedTaskInputDigest.get(),
            dependencyDetachedCompletionInputDigest.get(),
            dependencyDetachedProofReportPath.get(),
            paths.detachedModel,
            paths.refinement,
            paths.valueRefinement,
            paths.capture,
            paths.existingProjectAdmission,
            paths.liveCapture,
            paths.negativeTest,
            paths.positiveTest,
            paths.fixtures,
            paths.classContract,
            paths.classpathUrlContract,
            paths.moduleBuild,
        )
    }
}

private class Kvp016ArtifactPaths private constructor(paths: List<String>) {
    val detachedModel = paths[0]
    val refinement = paths[1]
    val valueRefinement = paths[2]
    val capture = paths[3]
    val existingProjectAdmission = paths[4]
    val liveCapture = paths[5]
    val negativeTest = paths[6]
    val positiveTest = paths[7]
    val fixtures = paths[8]
    val classContract = paths[9]
    val classpathUrlContract = paths[10]
    val moduleBuild = paths[11]

    companion object {
        /**
         * Proof transition: configured `List<String> -> Kvp016ArtifactPathRefinement`.
         *
         * Establishes the exact ordered KVP-016 artifact set. Any other order or member set is
         * finite [Kvp016ArtifactPathRefinement.Rejected] data; raw Gradle list values enter only
         * here.
         */
        fun refine(paths: List<String>): Kvp016ArtifactPathRefinement =
            if (paths == KVP016_ARTIFACT_PATHS) {
                Kvp016ArtifactPathRefinement.Refined(Kvp016ArtifactPaths(paths))
            } else {
                Kvp016ArtifactPathRefinement.Rejected
            }
    }
}

private sealed interface Kvp016ArtifactPathRefinement {
    data class Refined(val paths: Kvp016ArtifactPaths) : Kvp016ArtifactPathRefinement
    data object Rejected : Kvp016ArtifactPathRefinement
}

internal val KVP016_ARTIFACT_PATHS = listOf(
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedIdeWorkspaceModel.kt",
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedIdeWorkspaceModelRefinement.kt",
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelValueRefinement.kt",
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt",
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/ExistingProjectAdmission.kt",
    "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveDetachedModelCapture.kt",
    "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/detached/DetachedModelNegativeTest.kt",
    "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/detached/DetachedModelTest.kt",
    "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/detached/DetachedModelFixtures.kt",
    "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/detached/DetachedModelClassContract.kt",
    "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/detached/DetachedClasspathUrlRefinementTest.kt",
    "workspace/intellij-read/build.gradle.kts",
)
