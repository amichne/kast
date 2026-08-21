package io.github.amichne.kast.relation.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RelationWorkOffset
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class RelationServiceTest {
    @Test
    fun `complete compiler evidence remains complete under one current lease`() {
        val workspace = published(19L)
        val request = request(workspace.readLease)
        val batch = emptyBatch(request)
        val compiler = RecordingCompiler(RelationCompilation.complete(batch))
        val service = RelationService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        val result = runSuspend { service.read(request) }

        val complete = assertInstanceOf(RelationReadResult.Complete::class.java, result)
        assertEquals(0, complete.coverage.exactCount.value)
        assertEquals(listOf(request), compiler.requests)
    }

    @Test
    fun `stale root and generation reject before compiler work`() {
        val workspace = published(19L)
        val compiler = RecordingCompiler()
        val service = RelationService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        assertEquals(
            RelationReadResult.Rejected(RelationReadRejection.STALE_GENERATION),
            runSuspend { service.read(request(lease(20L))) },
        )
        assertEquals(
            RelationReadResult.Rejected(RelationReadRejection.WORKSPACE_ROOT_MISMATCH),
            runSuspend { service.read(request(lease(19L, "/other"))) },
        )
        assertEquals(emptyList<RelationRequest>(), compiler.requests)
    }

    @Test
    fun `workspace movement after compiler work discards relation evidence`() {
        val workspace = published(19L)
        val request = request(workspace.readLease)
        val states = ArrayDeque<WorkspaceRuntimeState>(
            listOf(WorkspaceRuntimeState.Ready(workspace), WorkspaceRuntimeState.Reconciling),
        )
        val service = RelationService(
            WorkspaceInspectionOperations { states.removeFirst() },
            RecordingCompiler(RelationCompilation.complete(emptyBatch(request))),
        )

        assertEquals(
            RelationReadResult.Rejected(RelationReadRejection.STALE_GENERATION),
            runSuspend { service.read(request) },
        )
    }

    @Test
    fun `qualified empty evidence retains known minimum and continuation`() {
        val workspace = published(19L)
        val request = request(workspace.readLease)
        val qualified = RelationCompilation.qualified(
            emptyBatch(request),
            setOf(RelationLimitation.PROVIDER_INCOMPLETE),
            RelationWorkOffset.parse(0L).refined(),
        ).refined()
        val service = RelationService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            RecordingCompiler(qualified),
        )

        val result = assertInstanceOf(
            RelationReadResult.Qualified::class.java,
            runSuspend { service.read(request) },
        )

        assertEquals(0, result.coverage.knownMinimum.value)
        assertEquals(request.subject.fingerprint, result.coverage.continuation.subject)
    }

    private fun emptyBatch(request: RelationRequest): RelationBatch = RelationBatch.create(
        request,
        emptyList(),
        RelationByteCount.parse(0L).refined(),
        RelationWorkCount.parse(0L).refined(),
    ).refined()

    private fun request(lease: SemanticReadLease): RelationRequest = RelationRequest.start(
        selector(lease),
        RelationMeaning.References,
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(8).refined(),
                WorkUnitLimit.parse(32L).refined(),
                ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            RelationByteLimit.parse(100_000L).refined(),
        ),
    )

    private fun selector(lease: SemanticReadLease): SymbolSelector {
        val discovery = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("run").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(8L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "run",
            lease,
            Path.of("${lease.workspaceRoot.value}/src/Subject.kt"),
            "file://${lease.workspaceRoot.value}/src/Subject.kt",
            41,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            discovery,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + 10,
            selection.candidate.name.value,
            "sample.Subject.run",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.Subject.run|-|||-|0").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun published(generation: Long): PublishedWorkspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            WorkspaceCandidate(root(), WorkspaceStateIdentity("source-state")),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined(),
        EvidenceGeneration.parse(generation).refined(),
    )

    private fun lease(
        generation: Long,
        root: String = "/workspace",
    ): SemanticReadLease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private class RecordingCompiler(
        private val result: RelationCompilation = RelationCompilation.Rejected(
            io.github.amichne.kast.relation.contract.RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
        ),
    ) : RelationCompilerPort {
        val requests = mutableListOf<RelationRequest>()

        override suspend fun read(request: RelationRequest): RelationCompilation {
            requests += request
            return result
        }
    }
}
