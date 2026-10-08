package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolCompletionRecoveryTest {
    @Test
    fun `strict rejection next query is admitted by the actual public tool for every output`() {
        val symbols = QueryOutputDocument.Symbols(bounded(QuerySymbolFieldDocument.entries))
        val outputs =
            listOf(
                symbols,
                QueryOutputDocument.Occurrences,
                QueryOutputDocument.TraversalRecords,
                QueryOutputDocument.BindingRows,
                QueryOutputDocument.ValuePaths,
                QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.entries.first()),
            )
        for (output in outputs) {
            val question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(text("Example.kt"), ProtocolOffset.parse(0).refined()),
                    bounded(emptyList()),
                    output,
                )
            val evidence = strictEvidence(question, emptyList())
            val next = evidence.getValue("nextQuery")
            val admitted =
                assertInstanceOf(
                        Refinement.Refined::class.java,
                        PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, next),
                    )
                    .value as AdmittedPublicTool
            val canonical = (admitted.canonical as PublicToolCanonical.Query).request
            val read = assertInstanceOf(QueryRunRequest.ReadResult::class.java, canonical)
            assertEquals(resultReference(), read.result)
            assertEquals(QueryResultCursor.Start, read.cursor)
            assertEquals(output, read.output)
        }
    }

    @Test
    fun `incomplete exact name search suggests a query using only its proven declaration reference`() {
        val source =
            QueryFromDocument.Symbols(
                QueryMatchDocument.Name(text("CacheManager"), SymbolDiscoveryMatchDocument.EXACT_NAME),
                QueryScopeDocument(bounded(listOf(text("main"))), null, null),
                bounded(listOf(QueryDeclarationKindDocument.CLASS)),
            )
        val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
        val proven =
            QueryResultItemDocument.ExactSymbol(
                ref = QueryReferenceDocument.ExactSymbol(text("exact:v2:proven")),
                kind = SymbolKindDocument.CLASSLIKE,
                name = text("CacheManager"),
                location = null,
                signature = null,
                connections = bounded(emptyList()),
                symbolId = SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
            )
        val question = QueryQuestionDocument(source, bounded(emptyList()), output)
        val evidence = strictEvidence(question, listOf(proven))
        assertTrue(evidence.containsKey("seedQuery"), "Known declaration should avoid another broad search")
        val admitted =
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, evidence.getValue("seedQuery"))
                as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryFromDocument.References(bounded(listOf(proven.ref))), run.from)
        assertEquals(output, run.output)
        assertTrue(run.steps.values.isEmpty())
        assertFalse(strictEvidence(question, emptyList()).containsKey("seedQuery"))
        assertFalse(
            strictEvidence(
                    question.copy(
                        from =
                            QueryFromDocument.Location(
                                text("Example.kt"),
                                ProtocolOffset.parse(0).refined(),
                            )
                    ),
                    listOf(proven),
                )
                .containsKey("seedQuery")
        )
    }

    private fun strictEvidence(
        question: QueryQuestionDocument,
        rows: List<QueryResultItemDocument>,
    ): kotlinx.serialization.json.JsonObject {
        val retained =
            QueryCompletionEvidenceDocument.Retained(
                resultReference(),
                bounded(rows),
                question,
            )
        val reason =
            QueryRunRejection.CompletionUnproven(
                model = QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                cause = QueryCompletionCauseDocument.IncompleteExecution,
                originalCoverage =
                    QueryCompletionCoverageDocument.Qualified(
                        ProtocolOffset.parse(rows.size).refined(),
                        QueryCompletionLimitationsDocument.from(listOf(QueryLimitationDocument.TIME_LIMIT_REACHED))
                            .refined(),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    ),
                stop = QueryInvocationStop.TIME_LIMIT,
                evidence = retained,
            )
        val projected =
            CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(reason))
                as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected
        return Json.parseToJsonElement(projected.document.value)
            .jsonObject
            .getValue("rejection")
            .jsonObject
            .getValue("detail")
            .jsonObject
            .getValue("evidence")
            .jsonObject
    }

    private fun resultReference() =
        QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).refined()

    private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
}
