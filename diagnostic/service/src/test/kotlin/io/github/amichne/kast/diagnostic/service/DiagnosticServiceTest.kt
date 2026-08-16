package io.github.amichne.kast.diagnostic.service

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
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

class DiagnosticServiceTest {
    @Test
    fun `complete compiler evidence remains complete under one current generation`() {
        val workspace = published(19L)
        val request = request(workspace.readLease)
        val compiler = RecordingCompiler(DiagnosticCompilation.complete(DiagnosticBatch.empty(request.scope)))
        val service = DiagnosticService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        val complete = assertInstanceOf(
            DiagnosticCheckResult.Complete::class.java,
            runSuspend { service.check(request) },
        )

        assertEquals(request.scope.files, complete.coverage.analyzedFiles)
        assertEquals(listOf(request.scope), compiler.scopes)
    }

    @Test
    fun `stale generation rejects before compiler work`() {
        val workspace = published(19L)
        val compiler = RecordingCompiler()
        val service = DiagnosticService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            compiler,
        )

        assertEquals(
            DiagnosticCheckResult.Rejected(DiagnosticReadRejection.STALE_GENERATION),
            runSuspend { service.check(request(lease(20L))) },
        )
        assertEquals(emptyList<DiagnosticScope>(), compiler.scopes)
    }

    @Test
    fun `workspace movement after compiler work discards diagnostic evidence`() {
        val workspace = published(19L)
        val request = request(workspace.readLease)
        val states = ArrayDeque<WorkspaceRuntimeState>(
            listOf(WorkspaceRuntimeState.Ready(workspace), WorkspaceRuntimeState.Reconciling),
        )
        val service = DiagnosticService(
            WorkspaceInspectionOperations { states.removeFirst() },
            RecordingCompiler(DiagnosticCompilation.complete(DiagnosticBatch.empty(request.scope))),
        )

        assertEquals(
            DiagnosticCheckResult.Rejected(DiagnosticReadRejection.STALE_GENERATION),
            runSuspend { service.check(request) },
        )
    }

    @Test
    fun `compiler rejection remains finite public rejection data`() {
        val workspace = published(19L)
        val service = DiagnosticService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            RecordingCompiler(
                DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE),
            ),
        )

        assertEquals(
            DiagnosticCheckResult.Rejected(DiagnosticReadRejection.WORKSPACE_INDEX_UNAVAILABLE),
            runSuspend { service.check(request(workspace.readLease)) },
        )
    }

    private fun request(lease: SemanticReadLease): DiagnosticCheckRequest = DiagnosticCheckRequest(
        DiagnosticScope.fromCanonicalPaths(
            lease,
            listOf(Path.of("${lease.workspaceRoot.value}/src/Subject.kt")),
        ).refined(),
    )

    private fun published(generation: Long): PublishedWorkspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            WorkspaceCandidate(root(), WorkspaceStateIdentity("source-state")),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined(),
        EvidenceGeneration.parse(generation).refined(),
    )

    private fun lease(generation: Long): SemanticReadLease = SemanticReadLease(
        root(),
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
        private val result: DiagnosticCompilation = DiagnosticCompilation.Rejected(
            DiagnosticCompilerRejection.COMPILER_CONTRACT_VIOLATION,
        ),
    ) : DiagnosticCompilerPort {
        val scopes = mutableListOf<DiagnosticScope>()

        override suspend fun check(scope: DiagnosticScope): DiagnosticCompilation {
            scopes += scope
            return result
        }
    }
}
