package io.github.amichne.kast.source.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceReadServiceTest {
    @Test
    fun `current publication admits and preserves one complete provider result`() {
        val workspace = published(7, "source-state")
        val request = request(workspace, "fun subject() = 1\n")
        val expected = complete(request)
        val port = RecordingSourceReadPort { _, _ -> expected }
        val service = SourceReadService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            port,
        )

        val result = runSuspend { service.read(request) }

        assertEquals(expected, result)
        assertEquals(listOf(request), port.requests)
        assertEquals(workspace.readLease, port.contexts.single().lease)
        assertEquals(workspace.sourceState, port.contexts.single().sourceState)
    }

    @Test
    fun `stale selector and moved source state reject before provider execution`() {
        val current = published(8, "source-state")
        val stale = request(published(7, "source-state"), "fun subject() = 1\n")
        val port = RecordingSourceReadPort { _, _ -> error("must not execute") }
        val service = SourceReadService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(current) },
            port,
        )

        assertEquals(
            SourceReadResult.Rejected(SourceReadRejection.SOURCE_SELECTOR_STALE),
            runSuspend { service.read(stale) },
        )

        val wrongState = request(published(8, "old-source-state"), "fun subject() = 1\n")
        assertEquals(
            SourceReadResult.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH),
            runSuspend { service.read(wrongState) },
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun `publication movement during provider read discards otherwise complete evidence`() {
        val first = published(7, "source-state")
        val moved = published(8, "source-state")
        val request = request(first, "fun subject() = 1\n")
        var inspections = 0
        val service = SourceReadService(
            WorkspaceInspectionOperations {
                inspections += 1
                WorkspaceRuntimeState.Ready(if (inspections == 1) first else moved)
            },
            RecordingSourceReadPort { _, _ -> complete(request) },
        )

        assertEquals(
            SourceReadResult.Rejected(SourceReadRejection.STALE_GENERATION),
            runSuspend { service.read(request) },
        )
    }

    @Test
    fun `provider cannot manufacture evidence for a different snapshot`() {
        val workspace = published(7, "source-state")
        val request = request(workspace, "fun subject() = 1\n")
        val wrongRequest = request(workspace, "fun subject() = 2\n")
        val service = SourceReadService(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) },
            RecordingSourceReadPort { _, _ -> complete(wrongRequest) },
        )

        assertEquals(
            SourceReadResult.Rejected(SourceReadRejection.CONTRACT_VIOLATION),
            runSuspend { service.read(request) },
        )
    }

    private fun request(workspace: PublishedWorkspace, text: String): SourceReadRequest {
        val selector = rootSelector(workspace, text)
        return SourceReadRequest(
            SourceReadAnchor.Source(selector),
            RegionSelection.Anchor,
            EntitySelection.None,
            TextProjection.Complete,
            SourceEntityLimit.parse(250).refined(),
            SourceTextByteLimit.parse(65_536).refined(),
            SourceReadPage.First,
        )
    }

    private fun complete(request: SourceReadRequest): SourceReadResult.Complete {
        val selector = (request.anchor as SourceReadAnchor.Source).selector
        val text = "fun subject() = ${if (selector.snapshot.textIdentity ==
            SourceTextIdentity.fromNormalizedCommittedText("fun subject() = 1\n")) "1" else "2"}\n"
        return SourceReadResult.Complete.create(
            selector.snapshot,
            SourceRegion.create(SourceRegionKind.DECLARATION, selector).refined(),
            emptyList(),
            SourceTextProjection.returned(selector, text).refined(),
        ).refined()
    }

    private fun rootSelector(workspace: PublishedWorkspace, text: String): SourceSelector {
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            workspace.root,
            Path.of("${workspace.root.value}/src/Subject.kt"),
        ).refined()
        val snapshot = SourceSnapshot.create(
            workspace.readLease,
            workspace.sourceState,
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
        val range = SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(0).refined(),
            Utf16CodeUnitOffset.parse(text.length).refined(),
        ).refined()
        return SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION)
    }

    private fun published(generation: Long, sourceState: String): PublishedWorkspace =
        PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(root(), WorkspaceStateIdentity.parse(sourceState).refined()),
                WorkspaceEvidenceKind.entries.toSet(),
            ).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
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

private class RecordingSourceReadPort(
    private val result: suspend (SourceReadContext, SourceReadRequest) -> SourceReadResult,
) : SourceReadPort {
    val contexts = mutableListOf<SourceReadContext>()
    val requests = mutableListOf<SourceReadRequest>()

    override suspend fun read(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): SourceReadResult {
        contexts += context
        requests += request
        return result(context, request)
    }
}
