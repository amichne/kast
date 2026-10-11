package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.cli.LiveReadOutputSchemaTest
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Canonical production serialization, with explicit portable facts and no provider effects. */
internal object QueryDeliveryFixtures {
    private const val ROW_COUNT = 43
    private val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
    private val question =
        QueryQuestionDocument(
            QueryFromDocument.Location(text("Fixture.kt"), ProtocolOffset.parse(0).refined()),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))),
        )
    private val basis =
        EvidenceBasis.Live(
            LiveReadEvidence.create(
                    "/workspace",
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .refined()
        )
    private val cause = QueryCompletionCauseDocument.IncompleteExecution
    private val coverage =
        QueryCompletionCoverageDocument.Qualified(
            ProtocolOffset.parse(ROW_COUNT).refined(),
            QueryCompletionLimitationsDocument.from(listOf(QueryLimitationDocument.TIME_LIMIT_REACHED)).refined(),
            QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
        )
    private val rows =
        (0 until ROW_COUNT).map { index ->
            QueryResultItemDocument.ExactSymbol(
                QueryReferenceDocument.ExactSymbol(text("exact:v2:fixture-$index")),
                SymbolKindDocument.FUNCTION,
                text("function$index"),
                null,
                null,
                bounded(emptyList()),
                SymbolIdDocument.parse("sym:" + "A".repeat(40) + index.toString().padStart(2, '0') + "A").refined(),
            )
        }
    private val evidence =
        rows.take(2).map { row ->
            QueryItemFailureDocument.ExactReference(row.ref, QueryExactFailureDocument.COMPILER_IDENTITY_UNAVAILABLE)
        }

    fun serialized(): String {
        val cases = cases()
        val schemas = LiveReadOutputSchemaTest()
        for (reply in cases.values) {
            val document =
                when (reply) {
                    is ToolRpcReply.Complete -> reply.document
                    is ToolRpcReply.Qualified -> reply.document
                    is ToolRpcReply.RejectedDocument -> reply.document
                    else -> error("Unexpected fixture")
                }
            schemas.assertAdmits(CanonicalOperation.QUERY_RUN, document)
        }
        return Json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                ToolRpcReply.serializer(),
            ),
            cases,
        )
    }

    private fun cases(): Map<String, ToolRpcReply> =
        linkedMapOf(
            "inline" to page(0, ROW_COUNT, 0, 0, summary = true),
            "prefix" to page(0, 1, 0, 0, summary = true, evidenceTotal = 2),
            "emptyPrefix" to page(0, 0, 0, 0, summary = true, evidenceTotal = 2),
            "rows" to page(1, ROW_COUNT, 0, 1, evidenceTotal = 2),
            "allRows" to page(0, ROW_COUNT, 0, 1, evidenceTotal = 2),
            "emptyAdvancing" to page(0, 0, 0, 1, evidenceTotal = 2),
            "rowsAfterEvidence" to page(0, ROW_COUNT, 1, 2, evidenceTotal = 2),
            "tail" to page(ROW_COUNT, ROW_COUNT, 1, 2, evidenceTotal = 2),
            "rejected" to rejection(),
            "proof" to page(0, ROW_COUNT, 0, 1, evidenceTotal = 2, rejectedProof = true),
            "proofTail" to page(ROW_COUNT, ROW_COUNT, 1, 2, evidenceTotal = 2, rejectedProof = true),
            "outputPrefix" to page(0, 1, 0, 0, output = true),
            "outputFinal" to page(1, ROW_COUNT, 0, 0, outputSuffix = true),
            "outputUnavailable" to
                outputBlocker(
                    QueryQualifiedProgressDocument.RetentionUnavailable(QueryPreparedCoverageDocument.Complete)
                ),
            "outputBudget" to outputBlocker(budgetProgress()),
            "readBudget" to outputBlocker(budgetProgress(), retained = true),
            "stale" to unavailable(QueryExecutionRejectionDocument.RESULT_STALE_BASIS),
            "expiredResult" to unavailable(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
            "disposedResult" to unavailable(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
            "expiredOutput" to unavailable(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
            "disposedOutput" to unavailable(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
        )

    private fun budgetProgress() =
        QueryQualifiedProgressDocument.Resumable(
            QueryCheckpointDocument.RetainedOutput(
                QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000002")
                    .refined(),
                QueryPreparedCoverageDocument.Complete,
            ),
            ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
        )

    private fun outputBlocker(progress: QueryQualifiedProgressDocument, retained: Boolean = false): ToolRpcReply {
        val result =
            QueryRunResult(
                question,
                bounded(rows.subList(1, if (retained) ROW_COUNT else 2)),
                bounded(if (retained) evidence.take(1) else emptyList()),
                retention = retention(!retained),
                evidenceWindow = if (retained) evidenceWindow(0, 1, 2) else null,
            )
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(ROW_COUNT).refined(),
                    listOf(QueryLimitationDocument.RETENTION_LIMIT_REACHED),
                    progress,
                )
                .refined()
        return ToolRpcReply.Qualified(
            project(
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, result),
                    qualification,
                )
            )
        )
    }

    private fun unavailable(reason: QueryExecutionRejectionDocument) =
        ToolRpcReply.RejectedDocument(project(OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))))

    private fun page(
        start: Int,
        end: Int,
        evidenceStart: Int,
        evidenceEnd: Int,
        summary: Boolean = false,
        evidenceTotal: Int = 0,
        rejectedProof: Boolean = false,
        output: Boolean = false,
        outputSuffix: Boolean = false,
    ): ToolRpcReply {
        val items = rows.subList(start, end)
        val outputOnly = output || outputSuffix
        val window = if (outputOnly) null else evidenceWindow(evidenceStart, evidenceEnd, evidenceTotal)
        val nextCursor = if (end < ROW_COUNT && !output) QueryResultCursor.parse(end).refined() else null
        val invocation = if (summary) previewSummary(items) else null
        val result =
            QueryRunResult(
                question,
                bounded(items),
                bounded(evidence.subList(evidenceStart, evidenceEnd)),
                retention = retention(outputOnly),
                nextCursor = nextCursor,
                invocation = invocation,
                evidenceWindow = window,
                interpretation = interpretation(rejectedProof),
            )
        val envelope = EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, result)
        return if (rejectedProof || output)
            ToolRpcReply.Qualified(project(OperationOutcome.Qualified(envelope, qualification(output))))
        else ToolRpcReply.Complete(project(OperationOutcome.Complete(envelope)))
    }

    private fun retention(outputOnly: Boolean) =
        if (outputOnly) QueryResultRetention.NotRequested else QueryResultRetention.Retained(reference)

    private fun interpretation(rejectedProof: Boolean) =
        if (rejectedProof)
            QueryResultInterpretationDocument.EvidenceOnly(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                cause,
                coverage,
            )
        else QueryResultInterpretationDocument.QueryResult

    private fun evidenceWindow(start: Int, end: Int, total: Int) =
        QueryEvidenceWindowDocument.create(
                QueryEvidenceCursor.parse(start).refined(),
                QueryEvidenceCursor.parse(end).refined(),
                QueryEvidenceCursor.parse(total).refined(),
            )
            .refined()

    private fun previewSummary(items: List<QueryResultItemDocument.ExactSymbol>): QueryInvocationDocument {
        val bytes = CanonicalQueryCliDocuments.previewBytes(items)
        val preview =
            if (items.size == ROW_COUNT) QueryPreviewDocument.Inline(ROW_COUNT, bytes)
            else QueryPreviewDocument.Prefix(items.size, bytes)
        return QueryInvocationDocument.create(ROW_COUNT, preview, QueryInvocationStop.COMPLETED).refined()
    }

    private fun qualification(output: Boolean): QueryRunQualification {
        val progress =
            if (output)
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.RetainedOutput(
                        QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000002")
                            .refined(),
                        QueryPreparedCoverageDocument.Complete,
                    ),
                    ReadResumeActionDocument.RESUME,
                )
            else QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE)
        return QueryRunQualification.create(
                QueryKnownMinimum.parse(ROW_COUNT).refined(),
                listOf(QueryLimitationDocument.TIME_LIMIT_REACHED),
                progress,
            )
            .refined()
    }

    private fun rejection() =
        ToolRpcReply.RejectedDocument(
            project(
                OperationOutcome.Rejected(
                    QueryRunRejection.CompletionUnproven(
                        QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                        cause,
                        coverage,
                        QueryInvocationStop.TIME_LIMIT,
                        QueryCompletionEvidenceDocument.Retained(reference, bounded(rows.take(1)), question),
                    )
                )
            )
        )

    private fun project(outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>): JsonObject {
        val projection = CanonicalQueryCliDocuments.project(outcome)
        val document =
            when (projection) {
                is ProjectedOperationOutcome.Complete -> projection.document
                is ProjectedOperationOutcome.Qualified -> projection.document
                is ProjectedOperationOutcome.Rejected -> projection.document
            }
        return Json.parseToJsonElement(document.value).jsonObject
    }

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T> Refinement<T, *>.refined(): T = (this as Refinement.Refined).value
}
