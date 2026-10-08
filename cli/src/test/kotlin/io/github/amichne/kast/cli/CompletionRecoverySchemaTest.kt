package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Actual projection and generated schema evidence, independent of native reference authority. */
class CompletionRecoverySchemaTest {
    private val schemas = LiveReadOutputSchemaTest()

    @Test
    fun `strict recovery rejection validates original question and next query for every output family`() {
        val outputs =
            listOf(
                QueryOutputDocument.Symbols(bounded(QuerySymbolFieldDocument.entries)),
                QueryOutputDocument.Occurrences,
                QueryOutputDocument.TraversalRecords,
                QueryOutputDocument.BindingRows,
                QueryOutputDocument.ValuePaths,
                QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.entries.first()),
            )
        for (output in outputs) {
            val question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(text("CacheManager.kt"), offset(0)),
                    bounded(emptyList()),
                    output,
                )
            val rows = if (output is QueryOutputDocument.Symbols) listOf(exact()) else emptyList()
            val evidence =
                QueryCompletionEvidenceDocument.Retained(
                    QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined(),
                    bounded(rows),
                    question,
                )
            schemas.assertAdmits(CanonicalOperation.QUERY_RUN, projection(evidence))
        }
    }

    @Test
    fun `strict recovery unavailable retains each finite retention reason in installed schema`() {
        for (cause in QueryCompletionRetentionFailure.entries) {
            val document = projection(QueryCompletionEvidenceDocument.Unavailable(cause))
            schemas.assertAdmits(CanonicalOperation.QUERY_RUN, document)
            val evidence =
                document.getValue("rejection").jsonObject.getValue("detail").jsonObject.getValue("evidence").jsonObject
            assertEquals(cause.name, evidence.getValue("cause").toString().trim('"'))
        }
    }

    private fun projection(evidence: QueryCompletionEvidenceDocument) =
        (CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(reason(evidence)))
                as ProjectedOperationOutcome.Rejected)
            .document
            .let { Json.parseToJsonElement(it.value).jsonObject }

    private fun reason(evidence: QueryCompletionEvidenceDocument) =
        QueryRunRejection.CompletionUnproven(
            QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
            QueryCompletionCauseDocument.IncompleteExecution,
            QueryCompletionCoverageDocument.Qualified(
                offset(0),
                QueryCompletionLimitationsDocument.from(listOf(QueryLimitationDocument.TIME_LIMIT_REACHED)).refined(),
                QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
            ),
            QueryInvocationStop.TIME_LIMIT,
            evidence,
        )

    private fun exact() =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(text("exact:v2:proven")),
            SymbolKindDocument.CLASSLIKE,
            text("CacheManager"),
            null,
            null,
            bounded(emptyList()),
            SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
        )

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).refined()

    private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
}
