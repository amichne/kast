package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import io.github.amichne.kast.protocol.contract.SourceReadResult as ProtocolSourceReadResult
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult

class CanonicalSourceReadHandlerTest {
    @Test
    fun `source selector enters domain service and returns reusable protocol authority`() {
        val fixture = fixture()
        var captured: io.github.amichne.kast.source.contract.SourceReadRequest? = null
        val handler = CanonicalSourceReadHandler(
            SourceReadOperations { request ->
                captured = request
                fixture.complete
            },
            CanonicalProtocolAuthority(),
        )

        val outcome = runSuspend { handler.execute(protocolRequest(fixture.token.value)) }
        val complete = outcome as OperationOutcome.Complete
        val result = complete.evidence.payload as ProtocolSourceReadResult
        val returned = result.text as SourceTextProjectionDocument.Returned

        assertTrue(captured?.anchor is SourceReadAnchor.Source)
        assertEquals(7, complete.evidence.generation.value)
        assertEquals(fixture.text, returned.text.value)
        assertEquals(1L, returned.lines.startInclusive.value)
        assertEquals(1L, returned.lines.endInclusive.value)
        assertTrue(
            SourceSelectorTokenCodec.decode(
                SourceSelectorToken.parse(returned.selection.selector.value).refined(),
            ) is Refinement.Refined,
        )
    }

    @Test
    fun `tampered source selector rejects before source operations execute`() {
        val fixture = fixture()
        var executed = false
        val handler = CanonicalSourceReadHandler(
            SourceReadOperations {
                executed = true
                fixture.complete
            },
            CanonicalProtocolAuthority(),
        )
        val tampered = fixture.token.value.dropLast(1) +
            if (fixture.token.value.last() == '0') '1' else '0'

        assertEquals(
            OperationOutcome.Rejected(SourceReadRejection.SOURCE_SELECTOR_STALE),
            runSuspend { handler.execute(protocolRequest(tampered)) },
        )
        assertFalse(executed)
    }

    private fun protocolRequest(selector: String): SourceReadRequest = SourceReadRequest(
        SourceReadAnchorDocument.Source(protocolText(selector)),
        SourceRegionSelectionDocument.Anchor,
        SourceEntitySelectionDocument.None,
        SourceTextRequestDocument.Complete,
        SourceEntityLimitDocument.parse(250).refined(),
        SourceTextByteLimitDocument.parse(65_536).refined(),
        SourceReadPageDocument.First,
    )

    private fun fixture(): Fixture {
        val text = "fun subject() = 1\n"
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/src/Subject.kt"),
        ).refined()
        val snapshot = SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(7).refined()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
        val range = SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(0).refined(),
            Utf16CodeUnitOffset.parse(text.length).refined(),
        ).refined()
        val selector = SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION)
        val region = SourceRegion.create(SourceRegionKind.DECLARATION, selector).refined()
        val projection = SourceTextProjection.returned(selector, text).refined()
        val complete = DomainSourceReadResult.Complete.create(
            snapshot,
            region,
            emptyList(),
            projection,
        ).refined()
        return Fixture(text, SourceSelectorTokenCodec.encode(selector), complete)
    }

    private fun protocolText(raw: String) =
        io.github.amichne.kast.protocol.contract.ProtocolText.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refinement, got $failure")
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

    private data class Fixture(
        val text: String,
        val token: SourceSelectorToken,
        val complete: DomainSourceReadResult.Complete,
    )
}
