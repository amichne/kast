package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryProgressSchemaTest {
    private val schema = LiveReadOutputSchemaTest()

    @Test
    fun `every query progress variant validates and owns its compatibility fields`() = runTest {
        with(schema) {
            val fixture = RelationPagingFixture.live()
            val relation = fixture.page() as OperationOutcome.Qualified
            val result = result(fixture)
            for (progress in progressStates()) {
                val qualification =
                    QueryRunQualification.create(
                            QueryKnownMinimum.parse(1).refined(),
                            listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED),
                            progress,
                        )
                        .refined()
                val document =
                    CanonicalQueryCliDocuments.project(
                            OperationOutcome.Qualified(
                                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, relation.evidence.basis, result),
                                qualification,
                            )
                        )
                        .document()
                assertAdmits(CanonicalOperation.QUERY_RUN, document)
                assertCompatibility(progress, document)
                val qualified = document.getValue("qualification").jsonObject
                assertRejects(
                    CanonicalOperation.QUERY_RUN,
                    document.with(
                        "qualification",
                        qualified.with(
                            "progress",
                            qualified.getValue("progress").jsonObject.with("type", JsonPrimitive("invented")),
                        ),
                    ),
                )
            }
        }
    }

    private fun result(fixture: RelationPagingFixture): QueryRunResult =
        with(schema) {
            val item: QueryResultItemDocument =
                QueryResultItemDocument.ExactSymbol(
                    QueryReferenceDocument.ExactSymbol(fixture.exact),
                    SymbolKindDocument.FUNCTION,
                    null,
                    null,
                    null,
                    BoundedProtocolList.create(emptyList<RelationFactDocument>()).refined(),
                    SymbolIdDocument.parse(CanonicalSymbolId.from(fixture.selector).value).refined(),
                )
            QueryRunResult(
                BoundedProtocolList.create(listOf(item)).refined(),
                BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).refined(),
                executionBudget = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument())),
            )
        }

    private fun progressStates(): List<QueryQualifiedProgressDocument> =
        with(schema) {
            val id = "00000000-0000-0000-0000-000000000001"
            val upstream =
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.Upstream(
                        QueryExecutionContinuation.Pipeline.parse("query:v1:$id").refined()
                    ),
                    ReadResumeActionDocument.RESUME,
                )
            val terminals = QueryTerminalReasonDocument.entries.map(QueryQualifiedProgressDocument::TerminalIncomplete)
            val coverage =
                listOf(QueryPreparedCoverageDocument.Complete, QueryPreparedCoverageDocument.Resumable) +
                    QueryTerminalReasonDocument.entries.map(QueryPreparedCoverageDocument::TerminalIncomplete)
            listOf(upstream) +
                terminals +
                coverage.map { original ->
                    QueryQualifiedProgressDocument.Resumable(
                        QueryCheckpointDocument.RetainedOutput(
                            QueryExecutionContinuation.Output.parse("query-output:v1:$id").refined(),
                            original,
                        ),
                        ReadResumeActionDocument.RESUME,
                    )
                }
        }

    private fun assertCompatibility(progress: QueryQualifiedProgressDocument, document: JsonObject) {
        when (progress) {
            is QueryQualifiedProgressDocument.Resumable -> {
                assertEquals(JsonPrimitive(progress.checkpoint.token.value), document["continuation"])
                assertEquals(JsonNull, document["terminal_reason"])
            }
            is QueryQualifiedProgressDocument.TerminalIncomplete -> {
                assertEquals(JsonNull, document["continuation"])
                assertEquals(
                    JsonPrimitive(progress.reason.name.lowercase().replace('_', '-')),
                    document["terminal_reason"],
                )
            }
        }
    }
}
