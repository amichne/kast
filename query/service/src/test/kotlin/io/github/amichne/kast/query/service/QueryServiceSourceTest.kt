package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryServiceSourceTest {
    private val fixture = QueryServiceTest()

    @Test
    fun `source projection reads five lines around an exact symbol in the same authority`() = runTest {
        with(fixture) {
            val selected = selector(selection())
            val text = "header\n" + "x".repeat(30) + "\ntrailer\n"
            val snapshot = snapshotFor(selected, text)
            val range =
                SourceRange.create(
                        snapshot,
                        Utf16CodeUnitOffset.parse(0).refined(),
                        Utf16CodeUnitOffset.parse(text.length).refined(),
                    )
                    .refined()
            val file = SourceSelector.issueRoot(range, SourceRegionKind.FILE)
            val returned = SourceTextProjection.returned(file, text).refined()
            var reads = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        ),
                    source =
                        SourceReadOperations { read ->
                            reads += 1
                            assertEquals(selected, (read.anchor as SourceReadAnchor.Symbol).selector)
                            assertEquals(RegionSelection.File, read.region)
                            assertEquals(EntitySelection.None, read.entities)
                            val window = assertInstanceOf(TextProjection.Window::class.java, read.text)
                            assertEquals(5, window.beforeLines.value)
                            assertEquals(5, window.afterLines.value)
                            SourceReadResult.Complete.create(
                                    snapshot,
                                    SourceRegion.create(SourceRegionKind.FILE, file).refined(),
                                    emptyList(),
                                    returned,
                                )
                                .refined()
                        },
                )
            val result = service.run(request(sourcePlan(selected), 8L))
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
            val symbol = complete.result.items.single()
            assertEquals(text, (symbol.source as QuerySymbolSource.Returned).value.text)
            assertEquals(1, reads)
        }
    }

    @Test
    fun `source rejection qualifies the symbol and retains its finite cause`() = runTest {
        with(fixture) {
            val selected = selector(selection())
            val result =
                service(
                        exact =
                            exactOperations(
                                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                                resolve = { error("No discovery expected") },
                            ),
                        source = SourceReadOperations { SourceReadResult.Rejected(SourceReadRejection.DOCUMENT_DIRTY) },
                    )
                    .run(request(sourcePlan(selected), 8L))
            val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
            assertTrue(QueryLimitation.SOURCE_INCOMPLETE in qualified.coverage.limitations)
            assertEquals(
                QuerySourceFailure.Rejected(SourceReadRejection.DOCUMENT_DIRTY),
                (qualified.result.failures.single() as QueryItemFailure.Source).reason,
            )
            assertEquals(
                SourceReadRejection.DOCUMENT_DIRTY,
                (qualified.result.items.single().source as QuerySymbolSource.Rejected).reason,
            )
        }
    }

    private fun sourcePlan(selected: SymbolSelector): AdmittedQueryPlan =
        with(fixture) {
            admittedPlan(
                source = QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                output = QueryOutputSyntax(QuerySymbolFields.from(setOf(QuerySymbolField.SOURCE)).refined()),
            )
        }

    private fun snapshotFor(selected: SymbolSelector, text: String): SourceSnapshot =
        SourceSnapshot.create(
            SourceReadContext.Published(
                selected.lease as SemanticReadLease,
                WorkspaceStateIdentity.parse("a".repeat(64)).refined(),
            ),
            selected.file as SymbolDiscoveryFileIdentity.Workspace,
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
            SourceReadScope.Constrained(selected.scope, selected.constraints),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }
}
