package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedOversizedSymbolPreviewTest {
    @Test
    fun `first retained preview issues a selector and the original row survives`() = runTest {
        val subject = "fun example() = " + "x".repeat(9400)
        val setup = HostedQueryRetainedPresentationTest()
        val owner = RelationPagingFixture.published()
        val fixture = setup.retainedFixture(owner = owner, rowCount = 1, source = source(owner, subject))
        val policy = setup.retainedPolicy(previewBytes = 4096)
        val limit = (ReturnedByteLimit.parse(4096) as Refinement.Refined).value
        val first = fixture.protocol.execute(fixture.request, fixture.owner.authority, fixture.budget, policy)
        val response =
            assertInstanceOf(
                HostedResponse.Canonical::class.java,
                encodeHostedQueryResponse(first, maximumBytes = limit),
            )
        assertTrue(response.document.toByteArray().size <= 4096)
        val result =
            assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic).evidence.payload
                as QueryRunResult
        val detail =
            assertInstanceOf(QueryResultItemDocument.DeferredDetail::class.java, result.items.values.single()).detail
        val read = QueryRunRequest.ReadResult.detail(detail.selector.result, detail.selector, fixture.output)
        val replay = fixture.protocol.execute(read, fixture.owner.authority, fixture.budget, policy)
        val fitted =
            assertInstanceOf(
                HostedResponse.Canonical::class.java,
                encodeHostedQueryResponse(replay, maximumBytes = limit),
            )
        val fragment =
            (assertInstanceOf(OperationOutcome.Complete::class.java, fitted.semantic).evidence.payload
                    as QueryRunResult)
                .detail!!
        assertEquals(detail.selector, fragment.selector)
        assertTrue(fragment.total.value >= subject.length)
        assertTrue(fragment.text.value.isNotEmpty())
        assertEquals(1, fixture.providerCalls())
    }

    private fun source(owner: RelationPagingFixture, text: String): QuerySymbolSource.Returned {
        val selected = owner.selector
        val snapshot =
            SourceSnapshot.create(
                SourceReadContext.Published(
                    selected.lease as SemanticReadLease,
                    WorkspaceStateIdentity.parse("a".repeat(64)).value(),
                ),
                selected.file as SymbolDiscoveryFileIdentity.Workspace,
                SourceTextIdentity.fromNormalizedCommittedText(text),
                Utf16CodeUnitCount.parse(text.length).value(),
                SourceReadScope.Constrained(selected.scope, selected.constraints),
            )
        val range =
            SourceRange.create(
                    snapshot,
                    Utf16CodeUnitOffset.parse(0).value(),
                    Utf16CodeUnitOffset.parse(text.length).value(),
                )
                .value()
        return QuerySymbolSource.Returned(
            SourceTextProjection.returned(SourceSelector.issueRoot(range, SourceRegionKind.FILE), text).value()
        )
    }

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
