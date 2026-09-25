package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryWireBindingTest {
    @Test
    fun `continuation and every terminal reason have independent encoded qualification shapes`() {
        val json = Json { encodeDefaults = true }
        val token = text("query:v1:00000000-0000-0000-0000-000000000001")
        val resumable =
            qualification(
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.Upstream(token),
                    ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
                )
            )
        assertEquals(
            WireValueEncoding.Encoded(
                json.encodeToJsonElement(
                    ExpectedQueryQualification.serializer(),
                    ExpectedQueryQualification(
                        progress =
                            ExpectedQueryProgress.Resumable(ExpectedUpstream(token.value), "increase_execution_budget")
                    ),
                )
            ),
            CanonicalQuerySerializers.qualification.encode(resumable, WireValueRole.QUALIFICATION),
        )
        val names =
            mapOf(
                QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE to "upstream-incomplete",
                QueryTerminalReasonDocument.OUTPUT_ITEM_TOO_LARGE to "output-item-too-large",
                QueryTerminalReasonDocument.CHECKPOINT_CAPACITY_EXCEEDED to "checkpoint-capacity-exceeded",
                QueryTerminalReasonDocument.NO_PROGRESS to "no-progress",
            )
        for ((reason, encoded) in names) {
            assertEquals(
                WireValueEncoding.Encoded(
                    json.encodeToJsonElement(
                        ExpectedQueryQualification.serializer(),
                        ExpectedQueryQualification(progress = ExpectedQueryProgress.Terminal(encoded)),
                    )
                ),
                CanonicalQuerySerializers.qualification.encode(
                    qualification(QueryQualifiedProgressDocument.TerminalIncomplete(reason)),
                    WireValueRole.QUALIFICATION,
                ),
            )
        }
    }

    @Test
    fun `unknown terminal reason cannot become qualified query evidence`() {
        val json = Json { encodeDefaults = true }
        assertTrue(
            CanonicalQuerySerializers.qualification.decode(
                json.encodeToJsonElement(
                    ExpectedQueryQualification.serializer(),
                    ExpectedQueryQualification(progress = ExpectedQueryProgress.Terminal("invented")),
                ),
                WireValueRole.QUALIFICATION,
            ) is WireDecoding.Rejected
        )
    }

    @Test
    fun `query request round trip admits ref only symbol output`() {
        val request =
            QueryRunRequest(
                from =
                    QueryFromDocument.References(
                        bounded(listOf(QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque"))))
                    ),
                steps = bounded(emptyList()),
                output = QueryOutputDocument.Symbols(bounded(emptyList())),
                execution =
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
            )

        val encoded = CanonicalOperationWireBindings.queryRun.encodeRequest(request)
        assertTrue(encoded is WireEncoding.Encoded)
        val decoded =
            CanonicalOperationWireBindings.queryRun.decodeRequest(
                WireRequestEnvelope.admit((encoded as WireEncoding.Encoded).document).admittedRequest()
            )
        assertEquals(request, (decoded as WireDecoding.Decoded).value)
    }

    @Test
    fun `query request round trip retains explicit enumeration and typed stages`() {
        val request =
            QueryRunRequest(
                from =
                    QueryFromDocument.Symbols(
                        QueryDiscoveryDocument(
                            QueryMatchDocument.All,
                            QueryScopeDocument(
                                bounded(listOf(text("main"))),
                                QueryDirectoryScopeDocument(
                                    text("services/payments"),
                                    QueryContainmentDocument.DESCENDANTS,
                                ),
                                QueryPackageScopeDocument(
                                    text("com.acme.payments"),
                                    QueryContainmentDocument.DESCENDANTS,
                                ),
                            ),
                            bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                        )
                    ),
                steps =
                    bounded(
                        listOf(
                            QueryStepDocument.Where(
                                QueryPredicateDocument.Visibility(bounded(listOf(QueryVisibilityDocument.PUBLIC)))
                            ),
                            QueryStepDocument.Related(RelationKindDocument.INHERITORS),
                            QueryStepDocument.Distinct,
                        )
                    ),
                output =
                    QueryOutputDocument.Symbols(
                        bounded(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION))
                    ),
                execution =
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
            )

        val encoded = CanonicalOperationWireBindings.queryRun.encodeRequest(request)
        assertTrue(encoded is WireEncoding.Encoded)
        val decoded =
            CanonicalOperationWireBindings.queryRun.decodeRequest(
                WireRequestEnvelope.admit((encoded as WireEncoding.Encoded).document).admittedRequest()
            )
        assertEquals(request, (decoded as WireDecoding.Decoded).value)
    }

    @Test
    fun `live complete evidence round trips without a published generation`() {
        val live =
            LiveReadEvidence.create(
                    "/workspace",
                    UUID.fromString("b41c43b0-1f11-4ca9-9ec0-b6fc88cd31c4"),
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .refinedValue()
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Live(live),
                    QueryRunResult(bounded(emptyList()), bounded(emptyList())),
                )
            )
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(outcome) as WireEncoding.Encoded
        assertTrue(!encoded.document.contains("generation"))
        assertEquals(
            outcome,
            (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document) as WireDecoding.Decoded).value,
        )
        val ambiguous = encoded.document.replace("\"type\":\"complete\"", "\"type\":\"complete\",\"generation\":1")
        assertTrue(CanonicalOperationWireBindings.queryRun.decodeOutcome(ambiguous) is WireDecoding.Rejected)
    }

    @Test
    fun `exact query source window retains bounded text and rejects malformed wire evidence`() {
        val live =
            LiveReadEvidence.create(
                    "/workspace",
                    UUID.fromString("b41c43b0-1f11-4ca9-9ec0-b6fc88cd31c4"),
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .refinedValue()
        val source =
            QuerySourceWindowDocument(
                ProtocolSourceText.parse("fun payment() = 1\n").refinedValue(),
                SourceLineRangeDocument.parse(1, 1).refinedValue(),
            )
        val item =
            QueryResultItemDocument.ExactSymbol(
                QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque")),
                SymbolKindDocument.FUNCTION,
                null,
                null,
                null,
                bounded(emptyList()),
                SymbolIdDocument.parse("sym:" + "A".repeat(43)).refinedValue(),
                source,
            )
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Live(live),
                    QueryRunResult(bounded(listOf(item)), bounded(emptyList())),
                )
            )
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(outcome) as WireEncoding.Encoded
        assertTrue(encoded.document.contains("\"source\":{\"text\":\"fun payment() = 1\\n\""))
        assertEquals(
            outcome,
            (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document) as WireDecoding.Decoded).value,
        )
        val malformed = encoded.document.replace("\"startInclusive\":1", "\"startInclusive\":0")
        assertTrue(CanonicalOperationWireBindings.queryRun.decodeOutcome(malformed) is WireDecoding.Rejected)
    }

    private fun qualification(progress: QueryQualifiedProgressDocument) =
        QueryRunQualification.create(
                QueryKnownMinimum.parse(0).refinedValue(),
                listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                progress,
            )
            .refinedValue()

    private fun <Value, Failure> Refinement<Value, Failure>.refinedValue(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun text(raw: String): ProtocolText =
        when (val value = ProtocolText.parse(raw)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error(value.failure)
        }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        when (val value = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error(value.failure)
        }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest =
        when (this) {
            is WireRequestAdmission.Admitted -> request
            is WireRequestAdmission.Rejected -> error(failure)
        }
}

@Serializable
private data class ExpectedQueryQualification(
    val knownMinimum: Int = 0,
    val limitations: List<String> = listOf("work-limit-reached"),
    val progress: ExpectedQueryProgress,
)

@Serializable
private sealed interface ExpectedQueryProgress {
    @Serializable
    @kotlinx.serialization.SerialName("resumable")
    data class Resumable(
        val checkpoint: ExpectedUpstream,
        @kotlinx.serialization.SerialName("next_action") val nextAction: String,
    ) : ExpectedQueryProgress

    @Serializable
    @kotlinx.serialization.SerialName("terminal_incomplete")
    data class Terminal(val reason: String) : ExpectedQueryProgress
}

@Serializable private data class ExpectedUpstream(val token: String, val type: String = "upstream")
