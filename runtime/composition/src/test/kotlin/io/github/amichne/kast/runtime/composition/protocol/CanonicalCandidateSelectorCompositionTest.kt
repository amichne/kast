package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalCandidateSelectorCompositionTest {
    @Test
    fun `one opaque candidate family restores declaration file and range variants`() {
        val fixture = fixture()
        val authority = CanonicalProtocolAuthority()

        val tokens = (authority.issueCandidates(fixture.batch) as
            CandidateSelectorIssuance.Issued).selectors
        val restored = tokens.map { token ->
            assertTrue(token.value.startsWith("candidate:v2:"))
            (CanonicalProtocolAuthority().candidate(token) as CandidateSelectorLookup.Found).selector
        }

        assertInstanceOf(CandidateSelector.File::class.java, restored[0])
        assertInstanceOf(CandidateSelector.Declaration::class.java, restored[1])
        assertInstanceOf(CandidateSelector.Range::class.java, restored[2])
        assertEquals(fixture.lease, restored[0].lease)
        assertEquals(fixture.lease, restored[1].lease)
        assertEquals(fixture.lease, restored[2].lease)
    }

    @Test
    fun `every candidate variant enters source read without identity reconstruction`() {
        val fixture = fixture()
        val authority = CanonicalProtocolAuthority()
        val tokens = (authority.issueCandidates(fixture.batch) as
            CandidateSelectorIssuance.Issued).selectors
        val captured = mutableListOf<CandidateSelector>()
        val handler = CanonicalSourceReadHandler(
            SourceReadOperations { request ->
                captured += (request.anchor as SourceReadAnchor.Candidate).selector
                fixture.complete
            },
            authority,
        )

        tokens.forEach { token ->
            assertInstanceOf(
                OperationOutcome.Complete::class.java,
                runSuspend { handler.execute(sourceRequest(token)) },
            )
        }

        assertInstanceOf(CandidateSelector.File::class.java, captured[0])
        assertInstanceOf(CandidateSelector.Declaration::class.java, captured[1])
        assertInstanceOf(CandidateSelector.Range::class.java, captured[2])
    }

    @Test
    fun `symbol resolve rejects non declaration candidates before semantic operations`() {
        val fixture = fixture()
        val authority = CanonicalProtocolAuthority()
        val fileToken = (authority.issueCandidates(fixture.batch) as
            CandidateSelectorIssuance.Issued).selectors.first()
        var executed = false
        val handler = CanonicalSymbolResolveHandler(
            object : SymbolExactOperations {
                override suspend fun resolve(
                    request: io.github.amichne.kast.symbol.contract.SymbolResolutionRequest,
                ): io.github.amichne.kast.symbol.contract.SymbolResolutionResult {
                    executed = true
                    error("Non-declaration candidate reached symbol resolution")
                }

                override suspend fun describe(
                    request: io.github.amichne.kast.symbol.contract.ExactSymbolRequest,
                ): io.github.amichne.kast.symbol.contract.SymbolDescriptionResult =
                    error("Not exercised")
            },
            authority,
        )

        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.CANDIDATE_NOT_DECLARATION),
            runSuspend { handler.execute(SymbolResolveRequest(fileToken)) },
        )
        assertFalse(executed)
    }

    private fun sourceRequest(token: ProtocolText): SourceReadRequest = SourceReadRequest(
        SourceReadAnchorDocument.Candidate(token),
        SourceRegionSelectionDocument.Anchor,
        SourceEntitySelectionDocument.None,
        SourceTextRequestDocument.Complete,
        SourceEntityLimitDocument.parse(250).refined(),
        SourceTextByteLimitDocument.parse(65_536).refined(),
        SourceReadPageDocument.First,
    )

    private fun fixture(): Fixture {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease, scope),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("source").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(10).refined(),
                    WorkUnitLimit.parse(100).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(100_000).refined(),
            ),
        )
        val path = Path.of("/workspace/src/Subject.kt")
        val url = "file:///workspace/src/Subject.kt"
        val candidates = listOf(
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.FILE,
                "Subject.kt",
                lease,
                path,
                url,
                null,
            ).refined(),
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "subject",
                lease,
                path,
                url,
                4,
            ).refined(),
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.TEXT,
                "subject",
                lease,
                path,
                url,
                4,
                11,
            ).refined(),
        ).sorted()
        val batch = SymbolDiscoveryBatch.create(
            request,
            candidates,
            SymbolDiscoveryByteCount.parse(candidates.sumOf { it.projectedUtf8Size().value })
                .refined(),
            SymbolDiscoveryWorkCount.parse(3).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
            ),
        ).refined()
        val text = "fun subject() = 1\n"
        val file = candidates.first().location.file as
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace
        val snapshot = SourceSnapshot.create(
            lease,
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            file,
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
        val range = SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(0).refined(),
            Utf16CodeUnitOffset.parse(text.length).refined(),
        ).refined()
        val selector = SourceSelector.issueRoot(range, SourceRegionKind.FILE)
        val region = SourceRegion.create(SourceRegionKind.FILE, selector).refined()
        val projection = SourceTextProjection.returned(selector, text).refined()
        val complete = SourceReadResult.Complete.create(
            snapshot,
            region,
            emptyList(),
            projection,
        ).refined()
        return Fixture(lease, batch, complete)
    }

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
        val lease: SemanticReadLease,
        val batch: SymbolDiscoveryBatch,
        val complete: SourceReadResult.Complete,
    )
}
