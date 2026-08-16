package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
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

class SymbolDiscoveryServiceTest {
    @Test
    fun `only the published generation reaches the compiler`() {
        val workspace = published(generation = 7L)
        val request = request(workspace.readLease)
        val compiler = RecordingCompiler(compilation(request))
        val service = SymbolDiscoveryService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        val result = runSuspend { service.discover(request) }

        assertInstanceOf(SymbolDiscoveryResult.Discovered::class.java, result)
        assertEquals(listOf(request), compiler.requests)
    }

    @Test
    fun `stale and unavailable workspaces reject before compiler work`() {
        val workspace = published(generation = 7L)
        val staleRequest = request(lease(generation = 6L))
        val compiler = RecordingCompiler(compilation(staleRequest))

        val stale = runSuspend {
            SymbolDiscoveryService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
                compiler,
            ).discover(staleRequest)
        }
        val unavailable = runSuspend {
            SymbolDiscoveryService(
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Reconciling },
                compiler,
            ).discover(request(workspace.readLease))
        }

        assertEquals(
            SymbolDiscoveryResult.Rejected(SymbolDiscoveryRejection.STALE_GENERATION),
            stale,
        )
        assertEquals(
            SymbolDiscoveryResult.Rejected(SymbolDiscoveryRejection.WORKSPACE_NOT_READY),
            unavailable,
        )
        assertEquals(emptyList<SymbolDiscoveryRequest>(), compiler.requests)
    }

    @Test
    fun `compiler cannot return another generation as successful discovery`() {
        val workspace = published(generation = 7L)
        val request = request(workspace.readLease)
        val otherRequest = request(lease(generation = 8L))
        val service = SymbolDiscoveryService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            RecordingCompiler(compilation(otherRequest)),
        )

        assertEquals(
            SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.COMPILER_CONTRACT_VIOLATION,
            ),
            runSuspend { service.discover(request) },
        )
    }

    @Test
    fun `workspace invalidation during compilation rejects the detached result`() {
        val workspace = published(generation = 7L)
        val request = request(workspace.readLease)
        val states = ArrayDeque<WorkspaceRuntimeState>(
            listOf(
                WorkspaceRuntimeState.Ready(workspace),
                WorkspaceRuntimeState.Reconciling,
            ),
        )
        val service = SymbolDiscoveryService(
            WorkspaceInspectionOperations { states.removeFirst() },
            RecordingCompiler(compilation(request)),
        )

        assertEquals(
            SymbolDiscoveryResult.Rejected(SymbolDiscoveryRejection.STALE_GENERATION),
            runSuspend { service.discover(request) },
        )
    }

    private fun compilation(request: SymbolDiscoveryRequest): SymbolCompilation =
        SymbolCompilation.Compiled(
            SymbolDiscoveryOutcome.Complete(
                SymbolDiscoveryBatch.create(
                    request = request,
                    candidates = emptyList(),
                    encodedBytes = SymbolDiscoveryByteCount.parse(0L).refined(),
                    examinedWorkUnits = SymbolDiscoveryWorkCount.parse(0L).refined(),
                    timings = SymbolDiscoveryTimings(
                        nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                        projection = SymbolDiscoveryElapsedNanoseconds.parse(0L).refined(),
                    ),
                ).refined(),
            ),
        )

    private fun request(lease: SemanticReadLease): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
        scope = SymbolSearchScopeRequest(
            lease = lease,
            scope = SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.EXCLUDE,
            ),
        ),
        kind = SymbolDiscoveryKind.SYMBOL,
        pattern = SymbolDiscoveryPattern.parse("Service").refined(),
        budget = SymbolDiscoveryBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(10).refined(),
                workUnitLimit = WorkUnitLimit.parse(100L).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
            ),
            returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
        ),
    )

    private fun published(generation: Long): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            root = root(),
            sourceState = WorkspaceStateIdentity("source-state"),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined()
        return PublishedWorkspace.publish(reconciled, evidenceGeneration(generation))
    }

    private fun lease(generation: Long): SemanticReadLease =
        SemanticReadLease(root(), evidenceGeneration(generation))

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun evidenceGeneration(value: Long): EvidenceGeneration =
        EvidenceGeneration.parse(value).refined()

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

private class RecordingCompiler(
    private val result: SymbolCompilation,
) : SymbolCompilerPort {
    val requests = mutableListOf<SymbolDiscoveryRequest>()

    override suspend fun compile(request: SymbolDiscoveryRequest): SymbolCompilation {
        requests += request
        return result
    }
}
