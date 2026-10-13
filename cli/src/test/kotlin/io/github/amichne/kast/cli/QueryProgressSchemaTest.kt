package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryBlockDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryProgressDocument
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
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.AgentInterfaceDiscoveryFixture
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.evidenceBasis
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueryProgressSchemaTest {
    private val schema = LiveReadOutputSchemaTest()

    @Test
    fun `every query progress variant validates and owns its compatibility fields`() = runTest {
        with(schema) {
            val fixture = RelationPagingFixture.live()
            val result = result(fixture)
            for (progress in progressStates()) {
                val qualification =
                    QueryRunQualification.create(
                            QueryKnownMinimum.parse(1).refined(),
                            if (progress is QueryQualifiedProgressDocument.RetentionUnavailable)
                                listOf(
                                    QueryLimitationDocument.BYTE_LIMIT_REACHED,
                                    QueryLimitationDocument.RETENTION_LIMIT_REACHED,
                                )
                            else listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED),
                            progress,
                        )
                        .refined()
                val document =
                    CanonicalQueryCliDocuments.project(
                            OperationOutcome.Qualified(
                                EvidenceEnvelope(
                                    CanonicalOperation.QUERY_RUN.id,
                                    fixture.authority.evidenceBasis(),
                                    result,
                                ),
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

    @Test
    fun `actual compact and verbose discovery pages admit and required semantic evidence cannot disappear`() {
        val progresses =
            listOf(
                QueryDiscoveryProgressDocument.Exhausted,
                AgentInterfaceDiscoveryFixture.resumableProgress(),
                QueryDiscoveryProgressDocument.Blocked(QueryDiscoveryBlockDocument.PARTITION_CAPACITY),
            )
        for (progress in progresses) {
            assertDiscoveryOutcome(AgentInterfaceDiscoveryFixture.complete(progress = progress))
            assertDiscoveryOutcome(AgentInterfaceDiscoveryFixture.qualified(progress = progress))
            assertDiscoveryOutcome(AgentInterfaceDiscoveryFixture.complete(progress = progress, evidenceOnly = true))
        }
        with(schema) {
            for (detail in ToolOutputDetail.entries) {
                assertAdmits(CanonicalOperation.QUERY_RUN, AgentInterfaceDiscoveryFixture.rejected().document(detail))
            }
        }
    }

    private fun assertDiscoveryOutcome(outcome: ProjectedOperationOutcome) =
        with(schema) {
            for (detail in ToolOutputDetail.entries) assertDiscoveryDocument(outcome.document(detail))
        }

    private fun assertDiscoveryDocument(document: JsonObject) =
        with(schema) {
            assertAdmits(CanonicalOperation.QUERY_RUN, document)
            for (field in
                listOf(
                    "items",
                    "walk_observations",
                    "reference_observations",
                    "discovery_observations",
                    "relation_observations",
                )) {
                assertRejects(CanonicalOperation.QUERY_RUN, document.withoutRequired(field))
            }
            val observation = document.getValue("discovery_observations").jsonArray.single().jsonObject
            for (field in listOf("universe", "ordering", "progress", "observed_stops")) {
                assertInvalidObservation(document, observation.withoutRequired(field))
            }
            assertInvalidObservation(
                document,
                observation.with(
                    "progress",
                    observation.getValue("progress").jsonObject.with("kind", JsonPrimitive("invented")),
                ),
            )
            assertInvalidObservation(document, observation.with("ordering", JsonPrimitive("invented")))
            assertInvalidObservation(document, observation.with("examined_work_units", JsonPrimitive(-1)))
        }

    // These mutations deliberately remove required fields or supply incompatible values.
    // Their source is a real serialized document, not a second fixed contract fixture.
    private fun JsonObject.withoutRequired(field: String): JsonObject =
        Json.encodeToJsonElement(filterKeys { it != field }).jsonObject

    private fun assertInvalidObservation(document: JsonObject, invalid: JsonObject) =
        with(schema) {
            assertRejects(
                CanonicalOperation.QUERY_RUN,
                document.with("discovery_observations", Json.encodeToJsonElement(listOf(invalid))),
            )
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
                fixtureQueryQuestion(),
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
                } +
                coverage.map(QueryQualifiedProgressDocument::RetentionUnavailable)
        }

    private fun assertCompatibility(progress: QueryQualifiedProgressDocument, document: JsonObject) {
        when (progress) {
            is QueryQualifiedProgressDocument.Resumable -> {
                assertEquals(JsonPrimitive(progress.checkpoint.token.value), document["continuation"])
                assertNull(document["terminal_reason"])
            }
            is QueryQualifiedProgressDocument.RetentionUnavailable -> {
                assertNull(document["continuation"])
                assertEquals(JsonPrimitive("checkpoint-capacity-exceeded"), document["terminal_reason"])
            }
            is QueryQualifiedProgressDocument.TerminalIncomplete -> {
                assertNull(document["continuation"])
                assertEquals(
                    JsonPrimitive(progress.reason.name.lowercase().replace('_', '-')),
                    document["terminal_reason"],
                )
            }
        }
    }
}

private fun fixtureQueryQuestion(): io.github.amichne.kast.protocol.contract.QueryQuestionDocument {
    fun <Value, Failure> fixtureValue(value: io.github.amichne.kast.kernel.Refinement<Value, Failure>): Value =
        when (value) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Invalid question fixture: ${value.failure}")
        }
    return io.github.amichne.kast.protocol.contract.QueryQuestionDocument(
        io.github.amichne.kast.protocol.contract.QueryFromDocument.Location(
            fixtureValue(io.github.amichne.kast.protocol.contract.ProtocolText.parse("Fixture.kt")),
            fixtureValue(io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(0)),
        ),
        fixtureValue(io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(emptyList())),
        io.github.amichne.kast.protocol.contract.QueryOutputDocument.Symbols(
            fixtureValue(
                io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                    listOf(io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument.NAME)
                )
            )
        ),
    )
}
