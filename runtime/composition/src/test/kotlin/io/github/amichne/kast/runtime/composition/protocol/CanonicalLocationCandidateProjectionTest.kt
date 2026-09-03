package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.runtime.composition.InstalledSymbolProtocolFixture
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalRelationReadHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTraversalRunHandler
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadRejection as DomainSourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult
import io.github.amichne.kast.symbol.contract.CandidateSelector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CanonicalLocationCandidateProjectionTest {
    @Test
    fun `relation traversal and diagnostics retain exact reusable range candidates`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val authority = CanonicalProtocolAuthority()
        val discover = CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
        val resolve = CanonicalSymbolResolveHandler(fixture.exact, authority)
        val relation = CanonicalRelationReadHandler(fixture.relation, authority)
        val traversal = CanonicalTraversalRunHandler(fixture.traversal, authority)
        val diagnostic = CanonicalDiagnosticCheckHandler(fixture.workspace, fixture.diagnostic, authority)

        val candidate = (
            runSuspend { discover.execute(discoverRequest()) } as OperationOutcome.Complete
            ).evidence.payload.items.values.single() as SymbolDiscoveryDocument.Declaration
        val exact = (
            runSuspend { resolve.execute(SymbolResolveRequest(candidate.candidateSelector)) } as
                OperationOutcome.Complete
            ).evidence.payload.exactSelector
        val related = runSuspend {
            relation.execute(RelationReadRequest(exact, RelationKindDocument.REFERENCES, count(4)))
        } as OperationOutcome.Complete
        val traversed = runSuspend {
            traversal.execute(
                TraversalRunRequest(exact, RelationKindDocument.REFERENCES, count(1), count(4)),
            )
        } as OperationOutcome.Qualified
        val diagnosed = runSuspend {
            diagnostic.execute(
                DiagnosticCheckRequest(
                    text("src/main/kotlin/Sample.kt"),
                    count(4),
                ),
            )
        } as OperationOutcome.Complete

        val relationOccurrence = related.evidence.payload.relations.values.single().occurrence
        val traversalOccurrence = traversed.evidence.payload.records.values.single().relation.occurrence
        val diagnosticLocation = diagnosed.evidence.payload.diagnostics.values.single().location
        assertEquals(relationOccurrence.candidateSelector, traversalOccurrence.candidateSelector)
        val restored = listOf(
            relationOccurrence.candidateSelector,
            diagnosticLocation.candidateSelector,
        ).map { token ->
            (authority.candidate(token) as CandidateSelectorLookup.Found).selector
                as CandidateSelector.Range
        }
        assertEquals(relationOccurrence.range.startInclusive.value, restored[0].startInclusive.value)
        assertEquals(relationOccurrence.range.endExclusive.value, restored[0].endExclusive.value)
        assertEquals(diagnosticLocation.range.startInclusive.value, restored[1].startInclusive.value)
        assertEquals(diagnosticLocation.range.endExclusive.value, restored[1].endExclusive.value)

        val admitted = mutableListOf<CandidateSelector.Range>()
        val source = CanonicalSourceReadHandler(
            SourceReadOperations { request ->
                admitted += ((request.anchor as SourceReadAnchor.Candidate).selector as
                    CandidateSelector.Range)
                DomainSourceReadResult.Rejected(DomainSourceReadRejection.REGION_NOT_APPLICABLE)
            },
            authority,
        )
        listOf(relationOccurrence.candidateSelector, diagnosticLocation.candidateSelector)
            .forEach { token ->
                assertInstanceOf(
                    OperationOutcome.Rejected::class.java,
                    runSuspend { source.execute(sourceRequest(token)) },
                )
            }
        assertEquals(restored, admitted)
    }

    @Test
    fun `zero width compiler diagnostic remains an exact source readable candidate`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val authority = CanonicalProtocolAuthority()
        val zeroWidth = DiagnosticService(
            fixture.workspace,
            DiagnosticCompilerPort { scope ->
                val fact = DiagnosticFact.fromBoundary(
                    scope,
                    scope.files.single(),
                    7,
                    7,
                    DiagnosticSeverity.WARNING,
                    "ZERO_WIDTH",
                    "zero-width fixture diagnostic",
                ).refined()
                DiagnosticCompilation.complete(
                    DiagnosticBatch.create(scope, listOf(fact)).refined(),
                )
            },
        )
        val handler = CanonicalDiagnosticCheckHandler(fixture.workspace, zeroWidth, authority)

        val outcome = runSuspend {
            handler.execute(
                DiagnosticCheckRequest(text("src/main/kotlin/Sample.kt"), count(4)),
            )
        } as OperationOutcome.Complete
        val location = outcome.evidence.payload.diagnostics.values.single().location
        val selector = (authority.candidate(location.candidateSelector) as
            CandidateSelectorLookup.Found).selector as CandidateSelector.Range

        assertEquals(7, selector.startInclusive.value)
        assertEquals(7, selector.endExclusive.value)
    }

    private fun discoverRequest(): SymbolDiscoverRequest = SymbolDiscoverRequest(
        SymbolDiscoverTargetDocument.Name(
            text("sample"),
            SymbolNameKindDocument.SYMBOL,
            SymbolDiscoveryMatchDocument.EXACT_NAME,
        ),
        count(4),
    )

    private fun sourceRequest(token: ProtocolText): SourceReadRequest = SourceReadRequest(
        SourceReadAnchorDocument.Candidate(token),
        SourceRegionSelectionDocument.Anchor,
        SourceEntitySelectionDocument.None,
        SourceTextRequestDocument.Complete,
        SourceEntityLimitDocument.parse(250).refined(),
        SourceTextByteLimitDocument.parse(65_536).refined(),
        SourceReadPageDocument.First,
    )

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refined()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected fixture rejection: $failure")
}

private fun <Value> runSuspend(block: suspend () -> Value): Value {
    var result: Result<Value>? = null
    block.startCoroutine(
        object : Continuation<Value> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(resumeResult: Result<Value>) {
                result = resumeResult
            }
        },
    )
    return checkNotNull(result).getOrThrow()
}
