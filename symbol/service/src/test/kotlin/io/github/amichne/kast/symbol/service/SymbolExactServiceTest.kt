package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
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

class SymbolExactServiceTest {
    @Test
    fun `resolve admits only the current published selection`() {
        val workspace = published(7L)
        val request = SymbolResolutionRequest(selection(workspace.readLease))
        val compiler = RecordingExactCompiler(
            resolveResult = SymbolResolutionCompilation.Resolved(selector(request.selection)),
        )
        val service = SymbolExactService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        val result = runSuspend { service.resolve(request) }

        assertInstanceOf(SymbolResolutionResult.Resolved::class.java, result)
        assertEquals(listOf(request), compiler.resolutions)
    }

    @Test
    fun `root and generation drift reject before compiler work`() {
        val workspace = published(7L)
        val compiler = RecordingExactCompiler()
        val service = SymbolExactService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )
        val moved = SymbolResolutionRequest(selection(lease(8L)))
        val otherRoot = SymbolResolutionRequest(
            selection(
                SemanticReadLease(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other")).refined(),
                    workspace.generation,
                ),
            ),
        )

        assertEquals(
            SymbolResolutionResult.Rejected(SymbolExactRejection.STALE_GENERATION),
            runSuspend { service.resolve(moved) },
        )
        assertEquals(
            SymbolResolutionResult.Rejected(SymbolExactRejection.WORKSPACE_ROOT_MISMATCH),
            runSuspend { service.resolve(otherRoot) },
        )
        assertEquals(emptyList<SymbolResolutionRequest>(), compiler.resolutions)
    }

    @Test
    fun `workspace movement during resolution discards exact authority`() {
        val workspace = published(7L)
        val request = SymbolResolutionRequest(selection(workspace.readLease))
        val states = ArrayDeque<WorkspaceRuntimeState>(
            listOf(WorkspaceRuntimeState.Ready(workspace), WorkspaceRuntimeState.Reconciling),
        )
        val service = SymbolExactService(
            WorkspaceInspectionOperations { states.removeFirst() },
            RecordingExactCompiler(
                resolveResult = SymbolResolutionCompilation.Resolved(selector(request.selection)),
            ),
        )

        assertEquals(
            SymbolResolutionResult.Rejected(SymbolExactRejection.STALE_GENERATION),
            runSuspend { service.resolve(request) },
        )
    }

    @Test
    fun `describe revalidates only the exact selector and retains its authority`() {
        val workspace = published(7L)
        val selector = selector(selection(workspace.readLease))
        val request = ExactSymbolRequest(selector)
        val description = SymbolDescription.from(selector)
        val compiler = RecordingExactCompiler(
            descriptionResult = SymbolDescriptionCompilation.Described(description),
        )
        val service = SymbolExactService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        assertEquals(
            SymbolDescriptionResult.Described(description),
            runSuspend { service.describe(request) },
        )
        assertEquals(listOf(request), compiler.descriptions)
    }

    private fun selector(selection: SymbolDiscoverySelection): SymbolSelector {
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file = location.file,
            rawStartInclusive = location.offset.value,
            rawEndExclusive = location.offset.value + 10,
            rawName = selection.candidate.name.value,
            rawQualifiedIdentity = "sample.Service.call",
            kind = CompilerSymbolKind.FUNCTION,
            compilerIdentity = CompilerSymbolIdentity.parse("sample.Service.call(kotlin.Int)").refined(),
        ).refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }

    private fun selection(lease: SemanticReadLease): SymbolDiscoverySelection {
        val request = discoveryRequest(lease)
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            kind = SymbolDiscoveryKind.SYMBOL,
            rawName = "call",
            lease = lease,
            nativePath = Path.of("${lease.workspaceRoot.value}/src/Service.kt"),
            virtualFileUrl = "file://${lease.workspaceRoot.value}/src/Service.kt",
            rawOffset = 7,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request = request,
            candidates = listOf(candidate),
            encodedBytes = candidate.projectedUtf8Size(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(1L).refined(),
            timings = SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        return SymbolDiscoverySelection.select(batch, 0).refined()
    }

    private fun discoveryRequest(lease: SemanticReadLease): SymbolDiscoveryRequest =
        SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            kind = SymbolNameDiscoveryKind.SYMBOL,
            pattern = SymbolDiscoveryPattern.parse("call").refined(),
            budget = SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )

    private fun published(generation: Long): PublishedWorkspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            WorkspaceCandidate(root(), WorkspaceStateIdentity("source-state")),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined(),
        EvidenceGeneration.parse(generation).refined(),
    )

    private fun lease(generation: Long): SemanticReadLease =
        SemanticReadLease(root(), EvidenceGeneration.parse(generation).refined())

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            },
        )
        return checkNotNull(completion).getOrThrow()
    }
}

private class RecordingExactCompiler(
    private val resolveResult: SymbolResolutionCompilation =
        SymbolResolutionCompilation.Rejected(
            io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection.INTERNAL_INVARIANT,
        ),
    private val descriptionResult: SymbolDescriptionCompilation =
        SymbolDescriptionCompilation.Rejected(
            io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection.INTERNAL_INVARIANT,
        ),
) : SymbolExactCompilerPort {
    val resolutions = mutableListOf<SymbolResolutionRequest>()
    val descriptions = mutableListOf<ExactSymbolRequest>()

    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionCompilation {
        resolutions += request
        return resolveResult
    }

    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionCompilation {
        descriptions += request
        return descriptionResult
    }
}
