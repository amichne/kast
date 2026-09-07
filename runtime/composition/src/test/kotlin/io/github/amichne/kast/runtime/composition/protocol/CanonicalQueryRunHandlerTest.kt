package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.runtime.composition.InstalledSymbolProtocolFixture
import io.github.amichne.kast.source.contract.SourceReadOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CanonicalQueryRunHandlerTest {
    @Test
    fun `candidate relation is rejected by typed admission before semantic execution`(
        @TempDir root: Path,
    ) = runTest {
        var executed = false
        val handler = CanonicalQueryRunHandler(
            InstalledSymbolProtocolFixture.create(root).workspace,
            QueryOperations {
                executed = true
                QueryExecutionResult.Rejected(
                    io.github.amichne.kast.query.contract.QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
                )
            },
            CanonicalProtocolAuthority(),
        )

        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.PlanRejected(
                    offset(0),
                    QueryElementTypeDocument.EXACT_SYMBOL,
                    QueryElementTypeDocument.DECLARATION_CANDIDATE,
                    QueryAdmissionCorrectionDocument.INSERT_INSPECT,
                ),
            ),
            handler.execute(request(candidateSource(), listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS)))),
        )
        assertFalse(executed)
    }

    @Test
    fun `unsupported declaration kind is rejected before semantic execution`(
        @TempDir root: Path,
    ) = runTest {
        var executed = false
        val handler = CanonicalQueryRunHandler(
            InstalledSymbolProtocolFixture.create(root).workspace,
            QueryOperations {
                executed = true
                QueryExecutionResult.Rejected(
                    io.github.amichne.kast.query.contract.QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
                )
            },
            CanonicalProtocolAuthority(),
        )
        val constructorSource = QueryFromDocument.Symbols(
            QueryDiscoveryDocument(
                QueryMatchDocument.All,
                QueryScopeDocument(bounded(listOf(QuerySourceSetDocument.MAIN)), null, null),
                bounded(listOf(QueryDeclarationKindDocument.CONSTRUCTOR)),
            ),
        )

        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.SourceRejected(
                    QueryDeclarationKindDocument.CONSTRUCTOR,
                    QuerySourceRejectionReason.UNSUPPORTED_DECLARATION_KIND,
                ),
            ),
            handler.execute(request(constructorSource, emptyList())),
        )
        assertFalse(executed)
    }

    @Test
    fun `exact reference source distinguishes wrong family from malformed token`(
        @TempDir root: Path,
    ) = runTest {
        val handler = CanonicalQueryRunHandler(
            InstalledSymbolProtocolFixture.create(root).workspace,
            QueryOperations { error("reference admission must precede execution") },
            CanonicalProtocolAuthority(),
        )

        assertReferenceRejection(handler, "candidate:v2:wrong", QueryReferenceRejectionReason.WRONG_KIND)
        assertReferenceRejection(handler, "source-selector-v1:wrong", QueryReferenceRejectionReason.WRONG_KIND)
        assertReferenceRejection(handler, "not-an-exact-selector", QueryReferenceRejectionReason.MALFORMED)
    }

    @Test
    fun `returned candidate and exact refs are accepted verbatim by their typed stages`(
        @TempDir root: Path,
    ) = runTest {
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val authority = CanonicalProtocolAuthority()
        val handler = CanonicalQueryRunHandler(
            fixture.workspace,
            QueryService(
                fixture.discovery,
                fixture.exact,
                SourceReadOperations { error("source read was not expected") },
                fixture.relation,
            ),
            authority,
        )

        val candidateResult = handler.execute(
            request(
                candidateSymbolSource(),
                emptyList(),
                QueryOutputDocument.Candidates(
                    bounded(listOf(QueryCandidateFieldDocument.NAME)),
                ),
            ),
        ) as OperationOutcome.Complete
        val candidateRef = (candidateResult.evidence.payload.items.values.single()
            as QueryResultItemDocument.Candidate).ref
        val refinedCandidate = handler.execute(
            request(
                QueryFromDocument.References(bounded(listOf(candidateRef))),
                listOf(QueryStepDocument.Inspect),
            ),
        )

        assertTrue(refinedCandidate is OperationOutcome.Complete)
        val first = handler.execute(request(symbolSource(), emptyList()))
            as OperationOutcome.Complete
        val exactRef = (first.evidence.payload.items.values.single()
            as QueryResultItemDocument.ExactSymbol).ref
        val second = handler.execute(
            request(
                QueryFromDocument.References(bounded(listOf(exactRef))),
                emptyList(),
            ),
        )

        assertTrue(second is OperationOutcome.Complete)
        assertEquals(exactRef, ((second as OperationOutcome.Complete).evidence.payload.items.values.single()
            as QueryResultItemDocument.ExactSymbol).ref)
    }

    private suspend fun assertReferenceRejection(
        handler: CanonicalQueryRunHandler,
        token: String,
        reason: QueryReferenceRejectionReason,
    ) {
        val source = QueryFromDocument.References(
            bounded(listOf(QueryReferenceDocument.ExactSymbol(text(token)))),
        )
        assertEquals(
            OperationOutcome.Rejected(QueryRunRejection.ReferenceRejected(offset(0), reason)),
            handler.execute(request(source, emptyList())),
        )
    }

    private fun candidateSource() = QueryFromDocument.Candidates(
        QueryDiscoveryDocument(
            QueryMatchDocument.All,
            QueryScopeDocument(bounded(listOf(QuerySourceSetDocument.MAIN)), null, null),
            bounded(listOf(QueryDeclarationKindDocument.CLASS)),
        ),
    )

    private fun symbolSource() = QueryFromDocument.Symbols(
        QueryDiscoveryDocument(
            QueryMatchDocument.All,
            QueryScopeDocument(bounded(listOf(QuerySourceSetDocument.MAIN)), null, null),
            bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
        ),
    )

    private fun candidateSymbolSource() = QueryFromDocument.Candidates(
        QueryDiscoveryDocument(
            QueryMatchDocument.All,
            QueryScopeDocument(bounded(listOf(QuerySourceSetDocument.MAIN)), null, null),
            bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
        ),
    )

    private fun request(
        from: QueryFromDocument,
        steps: List<QueryStepDocument>,
        output: QueryOutputDocument = QueryOutputDocument.Symbols(
            bounded(listOf(QuerySymbolFieldDocument.NAME)),
        ),
    ) = QueryRunRequest(
        from,
        bounded(steps),
        output,
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
    )

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()
    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()
    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
