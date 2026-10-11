package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached compiler facts are inputs; production retained presentation owns every emitted page. */
class PartialPrefixProducerTest {
    @Test
    fun `production retained pages and recovery projection admit bounded prefixes without semantic execution`() =
        runTest {
            val cases =
                listOf(
                    produce("exact-symbol-row", exactName = true),
                    produce("location-symbol-row"),
                    produce("fuzzy-symbol-row", fuzzy = true),
                    produce("exact-symbol-evidence", exactName = true, evidenceOnly = true),
                    produce("location-occurrence-row", occurrences = true),
                )
            val report = Path.of("build/reports/partial-prefix-producer")
            Files.createDirectories(report)
            val fixture = report.resolve("cases.json")
            Files.writeString(fixture, Json.encodeToString(cases))
            val output = report.resolve("node.log")
            val process =
                ProcessBuilder(
                        "node",
                        "../../experiments/host-observation/verify_partial_prefix_producer.mjs",
                        fixture.toString(),
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            try {
                assertTrue(
                    process.waitFor(30, TimeUnit.SECONDS),
                    "Pure producer fixture admission must finish within 30 seconds",
                )
                assertEquals(0, process.exitValue(), Files.readString(output))
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Only the owned fixture child must terminate")
                }
            }
        }

    private suspend fun produce(
        name: String,
        exactName: Boolean = false,
        fuzzy: Boolean = false,
        evidenceOnly: Boolean = false,
        occurrences: Boolean = false,
    ): PartialPrefixProducerCase {
        val fixture = RelationPagingFixture.live()
        val count = if (evidenceOnly) 3 else 40
        val selectors =
            (0 until count).map { index ->
                SymbolSelector.issue(
                    fixture.authority,
                    fixture.selector.scope,
                    CompilerGroundedSymbolEvidence.fromBoundary(
                            fixture.selector.file,
                            index * 10,
                            index * 10 + 6,
                            "subject",
                            "sample.subject",
                            CompilerSymbolKind.FUNCTION,
                            CanonicalCompilerSignature.function(
                                    "sample.subject",
                                    null,
                                    List(1000) { "T" },
                                    emptyList(),
                                    0,
                                )
                                .value(),
                        )
                        .value(),
                )
            }
        fun occurrence(selector: SymbolSelector, index: Int): RelationReferenceOccurrence {
            val request = RelationRequest.start(selector, RelationMeaning.References, fixture.budget)
            val target =
                RelationConfirmedReferenceTarget.fromCompiler(
                        request.subject,
                        CompilerGroundedSymbolEvidence.fromSelector(selector),
                    )
                    .value()
            return RelationReferenceOccurrence.confirmed(
                    request,
                    target,
                    RelationOccurrence.fromBoundary(selector.file, index * 2, index * 2 + 1).value(),
                    RelationReferenceContext.IMPORT,
                    RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .value()
        }
        val rows =
            if (occurrences)
                QueryRows.Occurrences.of(selectors.mapIndexed { i, s -> QueryOccurrence.Reference(occurrence(s, i)) })
            else QueryRows.Symbols.of(selectors.map { QuerySymbol(SymbolDescription.from(it), emptyList()) })
        val result =
            QueryResult(
                rows,
                emptyList(),
                referenceObservations =
                    if (evidenceOnly) (0 until 40).map { occurrence(selectors.first(), it) } else emptyList(),
            )
        val execution =
            QueryExecutionResult.Qualified(
                result,
                QueryCoverage.Qualified.create(
                        QueryCount.parse(count).value(),
                        setOf(QueryLimitation.TIME_LIMIT_REACHED),
                    )
                    .value(),
            )
        val retained = QueryRetainedResult.capture(fixture.authority, execution).value()
        val output =
            if (occurrences) QueryOutputDocument.Occurrences
            else
                QueryOutputDocument.Symbols(
                    bounded(
                        listOf(
                            QuerySymbolFieldDocument.NAME,
                            QuerySymbolFieldDocument.LOCATION,
                            QuerySymbolFieldDocument.SIGNATURE,
                        )
                    )
                )
        val from =
            if (exactName || fuzzy)
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(
                        text("subject"),
                        if (fuzzy) SymbolDiscoveryMatchDocument.FUZZY else SymbolDiscoveryMatchDocument.EXACT_NAME,
                    ),
                    QueryScopeDocument(bounded(listOf(text("main"))), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
            else QueryFromDocument.Location(text("Subject.kt"), ProtocolOffset.parse(0).value())
        val run =
            QueryRunRequest.Run(
                from,
                bounded(emptyList()),
                output,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        val state = QueryStateStore(clock = { 0L })
        val issued = state.issueResult(run, retained) as QueryResultIssuance.Issued
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Retained fixture must never execute semantics") },
                fixture.references,
                state,
            )
        val budget =
            QueryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).value(),
                    WorkUnitLimit.parse(100).value(),
                    ElapsedTimeLimitMillis.parse(1000).value(),
                ),
                QueryByteLimit.parse(100000).value(),
            )
        var row = 0
        var evidence = 0
        val pages = mutableListOf<String>()
        var initial: String? = null
        do {
            val read =
                if (occurrences)
                    QueryRunRequest.ReadResult.occurrences(
                        issued.reference,
                        QueryResultCursor.parse(row).value(),
                        evidenceCursor = QueryEvidenceCursor.parse(evidence).value(),
                    )
                else
                    QueryRunRequest.ReadResult.symbols(
                        issued.reference,
                        QueryResultCursor.parse(row).value(),
                        output as QueryOutputDocument.Symbols,
                        evidenceCursor = QueryEvidenceCursor.parse(evidence).value(),
                    )
            val page = protocol.executePage(read, fixture.authority, budget) as OperationOutcome.Qualified
            val payload = page.evidence.payload
            if (initial == null) {
                val interpretation = payload.interpretation as QueryResultInterpretationDocument.EvidenceOnly
                val cause = (completionProof(retained) as Refinement.Rejected).failure.protocolCompletionCause()
                assertEquals(cause, interpretation.cause)
                val rejected =
                    QueryRunRejection.CompletionUnproven(
                        QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                        cause,
                        interpretation.originalCoverage,
                        QueryInvocationStop.TERMINAL_INCOMPLETE,
                        QueryCompletionEvidenceDocument.Retained(
                            issued.reference,
                            bounded(payload.items.values.take(1)),
                            payload.question,
                        ),
                    )
                initial =
                    (CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejected))
                            as ProjectedOperationOutcome.Rejected)
                        .document
                        .value
            }
            pages += (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Qualified).document.value
            row += payload.items.values.size
            evidence = requireNotNull(payload.evidenceWindow).end.value
        } while (payload.nextCursor != null || evidence < requireNotNull(payload.evidenceWindow).total.value)
        assertEquals(count, row)
        return PartialPrefixProducerCase(name, requireNotNull(initial), pages, count)
    }

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}

@Serializable
private data class PartialPrefixProducerCase(
    val name: String,
    val initial: String,
    val pages: List<String>,
    val rowCount: Int,
)
