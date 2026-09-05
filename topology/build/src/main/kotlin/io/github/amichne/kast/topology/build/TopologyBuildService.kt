package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.KastTopologyBindingFailure
import io.github.amichne.kast.topology.contract.TopologyBindingFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanEvent
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTopologyCacheDisposition
import io.github.amichne.kast.kernel.KastTopologyIdentityStage
import io.github.amichne.kast.kernel.KastTopologySourceRange
import io.github.amichne.kast.kernel.KastTraceSpan
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCacheDisposition
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyIdentityStage
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.topology.contract.toTopologyExtractionFailure
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Private capability whose construction and use exist only inside `:topology:build`. */
private class TopologyBuildAuthority {
    suspend fun execute(operation: suspend () -> TopologyBuildResult): TopologyBuildResult =
        operation()
}

private class ExactDurableTopologySnapshot private constructor(
    val snapshot: PublishedTopologySnapshot,
) {
    companion object {
        /**
         * Proof transition: `(TopologyWorkspaceIdentity, PublishedTopologySnapshot) ->
         * Refinement<ExactDurableTopologySnapshot,
         * TopologyBuildFailure.SnapshotContractViolation>`.
         *
         * Establishes that the durable eligibility adapter returned the exact requested workspace
         * identity. The closed expected failure is
         * [TopologyBuildFailure.SnapshotContractViolation]. Raw adapter output is consumed only at
         * this durable reuse boundary.
         */
        fun validate(
            identity: TopologyWorkspaceIdentity,
            snapshot: PublishedTopologySnapshot,
        ): Refinement<
            ExactDurableTopologySnapshot,
            TopologyBuildFailure.SnapshotContractViolation,
        > = if (snapshot.identity == identity) {
            Refinement.Refined(ExactDurableTopologySnapshot(snapshot))
        } else {
            Refinement.Rejected(TopologyBuildFailure.SnapshotContractViolation)
        }
    }
}

private sealed interface TopologyGenerationRevalidationFailure {
    data class SourceEvidenceMoved(
        val path: WorkspaceSourcePath,
    ) : TopologyGenerationRevalidationFailure
    data object WorkspaceMismatch : TopologyGenerationRevalidationFailure
    data object GenerationMismatch : TopologyGenerationRevalidationFailure
}

private class RevalidatedTopologyGeneration private constructor(
    val generation: CompleteTopologyGeneration,
) {
    companion object {
        /**
         * Proof transition: `(TopologyCandidateSet, TopologyCandidateSet,
         * CompleteTopologyGeneration) -> Refinement<RevalidatedTopologyGeneration,
         * TopologyGenerationRevalidationFailure>`.
         *
         * Establishes that the exact candidate paths, ownership, and content hashes did not move
         * during extraction and that the generation covers that stable evidence. The closed
         * expected failure is [TopologyGenerationRevalidationFailure]. Fresh physical evidence
         * may enter only through the injected candidate enumerator.
         */
        fun validate(
            original: TopologyCandidateSet,
            observed: TopologyCandidateSet,
            generation: CompleteTopologyGeneration,
        ): Refinement<
            RevalidatedTopologyGeneration,
            TopologyGenerationRevalidationFailure,
        > {
            if (original.workspace != observed.workspace) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.WorkspaceMismatch,
                )
            }
            val originalFiles = original.files.associateBy { it.path }
            val observedFiles = observed.files.associateBy { it.path }
            val movedPath = (originalFiles.keys + observedFiles.keys)
                .sortedBy(WorkspaceSourcePath::value)
                .firstOrNull { originalFiles[it] != observedFiles[it] }
            if (movedPath != null) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.SourceEvidenceMoved(movedPath),
                )
            }
            if (
                generation.identity != original.workspace ||
                generation.files.map(CompleteTopologyFile::file) != original.files
            ) {
                return Refinement.Rejected(
                    TopologyGenerationRevalidationFailure.GenerationMismatch,
                )
            }
            return Refinement.Refined(RevalidatedTopologyGeneration(generation))
        }
    }
}

/** Explicit coordinator that alone can turn terminal K2 coverage into publication. */
class TopologyBuildService private constructor(
    private val workspaces: WorkspaceInspectionOperations,
    private val leaseGuard: SemanticReadLeaseGuard,
    private val candidates: TopologyCandidateEnumerator,
    private val extractor: TopologyFileExtractor,
    private val snapshots: TopologySnapshotStore,
    private val observability: KastObservability,
    private val authority: TopologyBuildAuthority,
    private val buildGate: Mutex,
) : TopologyBuildOperations {
    override suspend fun build(): TopologyBuildResult = buildGate.withLock {
        observability.inSpan(KastSpanName.TOPOLOGY_BUILD) { span ->
            authority.execute { buildAuthorized(span) }.also { result ->
                span.observe(result.traceObservation())
            }
        }
    }

    private suspend fun buildAuthorized(trace: KastTraceSpan): TopologyBuildResult {
        val workspace = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return rejected(TopologyBuildFailure.WorkspaceNotReady)
        }
        val identity = TopologyWorkspaceIdentity.from(workspace)
        val eligibilityUse = trace.child(KastSpanName.TOPOLOGY_SNAPSHOT_ELIGIBILITY) { span ->
            leaseGuard.whileCurrent(workspace.readLease) {
                snapshots.eligible(identity)
            }.also { guarded ->
                span.observe(
                    when (guarded) {
                        SemanticReadLeaseUse.Moved -> rejectedObservation(
                            KastSpanFailure.TOPOLOGY_WORKSPACE_MOVED,
                        )
                        is SemanticReadLeaseUse.Completed -> when (guarded.value) {
                            is TopologySnapshotEligibility.Rejected -> rejectedObservation(
                                KastSpanFailure.TOPOLOGY_SNAPSHOT,
                            )
                            is TopologySnapshotEligibility.Eligible,
                            is TopologySnapshotEligibility.Stale,
                            TopologySnapshotEligibility.Unavailable,
                                -> completeObservation()
                        }
                    },
                )
            }
        }
        val eligibility = when (val guarded = eligibilityUse) {
            SemanticReadLeaseUse.Moved -> return TopologyBuildResult.WorkspaceMoved
            is SemanticReadLeaseUse.Completed -> guarded.value
        }
        val prior = when (val existing = eligibility) {
            is TopologySnapshotEligibility.Eligible -> {
                val durable = when (val validation = ExactDurableTopologySnapshot.validate(
                    identity,
                    existing.snapshot,
                )) {
                    is Refinement.Refined -> validation.value
                    is Refinement.Rejected -> return rejected(validation.failure)
                }
                return TopologyBuildResult.Reused(durable.snapshot)
            }
            is TopologySnapshotEligibility.Rejected ->
                return rejected(TopologyBuildFailure.SnapshotRead(existing.failure))
            is TopologySnapshotEligibility.Stale ->
                PriorTopologySnapshot.Stale(existing.latest)
            TopologySnapshotEligibility.Unavailable -> PriorTopologySnapshot.Unavailable
        }
        val candidateSet = when (val enumeration = enumerate(workspace, trace)) {
            is TopologyCandidateEnumeration.Complete -> enumeration.candidates
            is TopologyCandidateEnumeration.Rejected ->
                return rejected(TopologyBuildFailure.Enumeration(enumeration.failure))
        }
        if (candidateSet.workspace != identity) {
            return rejected(TopologyBuildFailure.ExtractionContractViolation)
        }
        when (prior) {
            is PriorTopologySnapshot.Stale -> {
                val content = when (val read = trace.child(
                    KastSpanName.TOPOLOGY_SNAPSHOT_READ,
                ) { span ->
                    snapshots.read(prior.snapshot).also { result ->
                        span.observe(
                            when (result) {
                                is TopologySnapshotContentRead.Loaded -> completeObservation()
                                is TopologySnapshotContentRead.Rejected -> rejectedObservation(
                                    KastSpanFailure.TOPOLOGY_SNAPSHOT,
                                )
                            },
                        )
                    }
                }) {
                    is TopologySnapshotContentRead.Loaded -> read.content
                    is TopologySnapshotContentRead.Rejected ->
                        return rejected(TopologyBuildFailure.SnapshotRead(read.failure))
                }
                when (
                    val reuse = rebindUnchangedTopologyGeneration(workspace, candidateSet, content)
                ) {
                    is TopologyGenerationReuse.Rebound -> return publishCompleteGeneration(
                        workspace,
                        candidateSet,
                        reuse.generation,
                        TopologyPublicationMode.REBOUND,
                        trace,
                    )
                    TopologyGenerationReuse.Rejected ->
                        return rejected(TopologyBuildFailure.SnapshotContractViolation)
                    TopologyGenerationReuse.SourceChanged -> Unit
                }
            }
            PriorTopologySnapshot.Unavailable -> Unit
        }
        val completed = mutableListOf<CompleteTopologyFile>()
        val extractionFailure = trace.child(KastSpanName.TOPOLOGY_EXTRACTION) { span ->
            for (file in candidateSet.files) {
                val request = when (val admitted = candidateSet.extractionRequest(file)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> {
                        span.observe(rejectedObservation(KastSpanFailure.TOPOLOGY_EXTRACTION))
                        return@child rejected(TopologyBuildFailure.ExtractionContractViolation)
                    }
                }
                when (val extraction = extractor.extract(request)) {
                    is TopologyFileExtraction.Complete -> completed += extraction.file
                    is TopologyFileExtraction.Failed -> {
                        span.observe(rejectedObservation(KastSpanFailure.TOPOLOGY_EXTRACTION))
                        if (extraction.file !in candidateSet.files) {
                            return@child rejected(
                                TopologyBuildFailure.ExtractionContractViolation,
                            )
                        }
                        return@child rejected(
                            TopologyBuildFailure.Extraction(
                                extraction.file.path,
                                extraction.failure.toTopologyExtractionFailure(),
                            ),
                        )
                    }
                    is TopologyFileExtraction.IdentityMismatch -> {
                        if (extraction.file != request.file) {
                            span.observe(rejectedObservation(KastSpanFailure.TOPOLOGY_EXTRACTION))
                            return@child rejected(
                                TopologyBuildFailure.ExtractionContractViolation,
                            )
                        }
                        span.observe(
                            rejectedObservation(
                                KastSpanFailure.TOPOLOGY_EXTRACTION,
                                setOf(extraction.traceEvent()),
                            ),
                        )
                        return@child rejected(
                            TopologyBuildFailure.Extraction(
                                extraction.file.path,
                                TopologyExtractionFailure.COMPILER_IDENTITY_MISMATCH,
                            ),
                        )
                    }
                }
            }
            span.observe(
                completeObservation(
                    KastSpanMeasurement.FileCount(exactCount(completed.size)),
                ),
            )
            null
        }
        if (extractionFailure != null) return extractionFailure
        val generation = when (
            val admitted = CompleteTopologyGeneration.admit(
                workspace,
                candidateSet.files,
                completed,
            )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                TopologyBuildFailure.Coverage(admitted.failure),
            )
        }
        return publishCompleteGeneration(
            workspace,
            candidateSet,
            generation,
            TopologyPublicationMode.EXTRACTED,
            trace,
        )
    }

    private suspend fun publishCompleteGeneration(
        workspace: PublishedWorkspace,
        originalCandidates: TopologyCandidateSet,
        generation: CompleteTopologyGeneration,
        mode: TopologyPublicationMode,
        trace: KastTraceSpan,
    ): TopologyBuildResult {
        val observedCandidates = when (val enumeration = enumerate(workspace, trace)) {
            is TopologyCandidateEnumeration.Complete -> enumeration.candidates
            is TopologyCandidateEnumeration.Rejected ->
                return rejected(TopologyBuildFailure.Enumeration(enumeration.failure))
        }
        val validation = trace.child(KastSpanName.TOPOLOGY_REVALIDATION) { span ->
            RevalidatedTopologyGeneration.validate(
                originalCandidates,
                observedCandidates,
                generation,
            ).also { result ->
                span.observe(
                    when (result) {
                        is Refinement.Refined -> completeObservation(
                            KastSpanMeasurement.FileCount(
                                exactCount(result.value.generation.files.size),
                            ),
                        )
                        is Refinement.Rejected -> rejectedObservation(
                            KastSpanFailure.TOPOLOGY_EXTRACTION,
                        )
                    },
                )
            }
        }
        val stableGeneration = when (validation) {
            is Refinement.Refined -> validation.value
            is Refinement.Rejected -> return when (val failure = validation.failure) {
                is TopologyGenerationRevalidationFailure.SourceEvidenceMoved -> rejected(
                    TopologyBuildFailure.Extraction(
                        failure.path,
                        TopologyExtractionFailure.SOURCE_CONTENT_CHANGED_DURING_BUILD,
                    ),
                )
                TopologyGenerationRevalidationFailure.WorkspaceMismatch,
                TopologyGenerationRevalidationFailure.GenerationMismatch ->
                    rejected(TopologyBuildFailure.ExtractionContractViolation)
            }
        }
        val publicationUse = trace.child(KastSpanName.TOPOLOGY_PUBLICATION) { span ->
            leaseGuard.whileCurrent(workspace.readLease) {
                snapshots.publish(stableGeneration.generation)
            }.also { guarded ->
                span.observe(
                    when (guarded) {
                        SemanticReadLeaseUse.Moved -> rejectedObservation(
                            KastSpanFailure.TOPOLOGY_WORKSPACE_MOVED,
                        )
                        is SemanticReadLeaseUse.Completed -> when (guarded.value) {
                            is TopologyPublicationResult.Published,
                            is TopologyPublicationResult.Unchanged,
                                -> completeObservation()
                            is TopologyPublicationResult.Rejected -> rejectedObservation(
                                KastSpanFailure.TOPOLOGY_PUBLICATION,
                            )
                        }
                    },
                )
            }
        }
        return when (val guarded = publicationUse) {
            SemanticReadLeaseUse.Moved -> TopologyBuildResult.WorkspaceMoved
            is SemanticReadLeaseUse.Completed -> when (val publication = guarded.value) {
                is TopologyPublicationResult.Published -> {
                    when (mode) {
                        TopologyPublicationMode.EXTRACTED ->
                            TopologyBuildResult.Published(publication.snapshot)
                        TopologyPublicationMode.REBOUND ->
                            TopologyBuildResult.Reused(publication.snapshot)
                    }
                }
                is TopologyPublicationResult.Unchanged ->
                    TopologyBuildResult.Reused(publication.snapshot)
                is TopologyPublicationResult.Rejected -> rejected(
                    TopologyBuildFailure.Publication(publication.failure),
                )
            }
        }
    }

    companion object {
        /**
         * Proof transition: `(workspace observation, current-lease guard, admitted-root
         * enumerator, K2 extractor, snapshot reader and publisher) -> TopologyBuildOperations`.
         *
         * Establishes the sole explicit build service carrying private [TopologyBuildAuthority].
         * Platform and persistence effects remain behind their injected ports. Raw construction is
         * permitted only in runtime composition while binding `topology.build`.
         */
        fun create(
            workspaces: WorkspaceInspectionOperations,
            leaseGuard: SemanticReadLeaseGuard,
            candidates: TopologyCandidateEnumerator,
            extractor: TopologyFileExtractor,
            snapshots: TopologySnapshotStore,
            observability: KastObservability = KastObservability.Disabled,
        ): TopologyBuildOperations = TopologyBuildService(
            workspaces,
            leaseGuard,
            candidates,
            extractor,
            snapshots,
            observability,
            TopologyBuildAuthority(),
            Mutex(),
        )
    }

    private suspend fun enumerate(
        workspace: PublishedWorkspace,
        trace: KastTraceSpan,
    ): TopologyCandidateEnumeration = trace.child(
        KastSpanName.TOPOLOGY_CANDIDATE_ENUMERATION,
    ) { span ->
        candidates.enumerate(workspace).also { result ->
            span.observe(
                when (result) {
                    is TopologyCandidateEnumeration.Complete -> completeObservation(
                        KastSpanMeasurement.FileCount(
                            exactCount(result.candidates.files.size),
                        ),
                    )
                    is TopologyCandidateEnumeration.Rejected -> rejectedObservation(
                        KastSpanFailure.TOPOLOGY_ENUMERATION,
                    )
                },
            )
        }
    }
}

private sealed interface PriorTopologySnapshot {
    data class Stale(val snapshot: PublishedTopologySnapshot) : PriorTopologySnapshot
    data object Unavailable : PriorTopologySnapshot
}

private enum class TopologyPublicationMode { EXTRACTED, REBOUND }

private fun rejected(failure: TopologyBuildFailure): TopologyBuildResult.Rejected =
    TopologyBuildResult.Rejected(failure)

private fun TopologyBuildResult.traceObservation(): KastSpanObservation = when (this) {
    is TopologyBuildResult.Published -> completeObservation(
        KastSpanMeasurement.FileCount(exactCount(snapshot.manifest.cardinalities.files)),
    )
    is TopologyBuildResult.Reused -> completeObservation(
        KastSpanMeasurement.FileCount(exactCount(snapshot.manifest.cardinalities.files)),
    )
    TopologyBuildResult.WorkspaceMoved -> rejectedObservation(
        KastSpanFailure.TOPOLOGY_WORKSPACE_MOVED,
    )
    is TopologyBuildResult.Rejected -> rejectedObservation(
        when (failure) {
            TopologyBuildFailure.WorkspaceNotReady ->
                KastSpanFailure.TOPOLOGY_WORKSPACE_NOT_READY
            TopologyBuildFailure.SnapshotContractViolation,
            is TopologyBuildFailure.SnapshotRead,
                -> KastSpanFailure.TOPOLOGY_SNAPSHOT
            is TopologyBuildFailure.Enumeration -> KastSpanFailure.TOPOLOGY_ENUMERATION
            is TopologyBuildFailure.Extraction,
            TopologyBuildFailure.ExtractionContractViolation,
                -> KastSpanFailure.TOPOLOGY_EXTRACTION
            is TopologyBuildFailure.Coverage -> KastSpanFailure.TOPOLOGY_COVERAGE
            is TopologyBuildFailure.Publication -> KastSpanFailure.TOPOLOGY_PUBLICATION
        },
    )
}

private fun completeObservation(
    vararg measurements: KastSpanMeasurement,
): KastSpanObservation = KastSpanObservation(
    KastSpanCompletion.Complete,
    measurements.toSet(),
)

private fun rejectedObservation(
    failure: KastSpanFailure,
    events: Set<KastSpanEvent> = emptySet(),
): KastSpanObservation = KastSpanObservation(
    completion = KastSpanCompletion.Rejected(failure),
    events = events,
)

private fun TopologyFileExtraction.IdentityMismatch.traceEvent(): KastSpanEvent =
    KastSpanEvent.TopologyIdentityMismatch(
        stage = evidence.stage.traceStage(),
        cacheDisposition = cacheDisposition.traceDisposition(),
        sourceFile = evidence.sourceFile.path.value,
        sourceOccurrence = evidence.sourceOccurrence.traceRange(),
        targetFile = evidence.targetFile.path.value,
        targetDeclaration = evidence.targetDeclarationRange.traceRange(),
        reason = evidence.reason.traceReason(),
    )

private fun TopologyIdentityStage.traceStage(): KastTopologyIdentityStage = when (this) {
    TopologyIdentityStage.REFERENCE_TARGET -> KastTopologyIdentityStage.REFERENCE_TARGET
    TopologyIdentityStage.DIRECT_OVERRIDE -> KastTopologyIdentityStage.DIRECT_OVERRIDE
}

private fun TopologyCacheDisposition.traceDisposition(): KastTopologyCacheDisposition =
    when (this) {
        TopologyCacheDisposition.COMPUTED -> KastTopologyCacheDisposition.COMPUTED
        TopologyCacheDisposition.REUSED -> KastTopologyCacheDisposition.REUSED
    }

private fun TopologyBindingFailure.traceReason(): KastTopologyBindingFailure = when (this) {
    TopologyBindingFailure.EPOCH_CHANGED -> KastTopologyBindingFailure.EPOCH_CHANGED
    TopologyBindingFailure.DECLARATION_UNAVAILABLE -> KastTopologyBindingFailure.DECLARATION_UNAVAILABLE
    TopologyBindingFailure.ORIGIN_NOT_ADMITTED -> KastTopologyBindingFailure.ORIGIN_NOT_ADMITTED
    TopologyBindingFailure.ROLE_MISMATCH -> KastTopologyBindingFailure.ROLE_MISMATCH
    TopologyBindingFailure.MODULE_MISMATCH -> KastTopologyBindingFailure.MODULE_MISMATCH
    TopologyBindingFailure.DECLARATION_MISMATCH -> KastTopologyBindingFailure.DECLARATION_MISMATCH
}

private fun ExactDeclarationTextRange.traceRange(): KastTopologySourceRange =
    KastTopologySourceRange(startInclusive, endExclusive)

private fun exactCount(value: Int): KastSpanCount = when (val count = KastSpanCount.parse(
    value.toLong(),
)) {
    is Refinement.Refined -> count.value
    is Refinement.Rejected -> error("Collection size cannot be negative")
}
