package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryBytesDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDeclarationLanguageDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryNanosecondsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryOrderDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryProgressDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryStopDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryUniverseDocument
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import java.util.UUID

/** Pure synthetic evidence. These handles belong only to this fixture's retained result and live authority. */
object AgentInterfaceDiscoveryFixture {
    const val HOST = "b41c43b0-1f11-4ca9-9ec0-b6fc88cd31c4"
    const val RESULT = "result:v1:00000000-0000-0000-0000-000000000001"
    const val CONTINUATION = "query-output:v1:00000000-0000-0000-0000-000000000001"

    fun complete(
        measurement: Long = 100,
        progress: QueryDiscoveryProgressDocument = QueryDiscoveryProgressDocument.Exhausted,
        evidenceOnly: Boolean = false,
    ): ProjectedOperationOutcome.Complete =
        CanonicalQueryCliDocuments.project(
            OperationOutcome.Complete(
                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis(), result(measurement, progress, evidenceOnly))
            )
        ) as ProjectedOperationOutcome.Complete

    fun qualified(
        measurement: Long = 100,
        progress: QueryDiscoveryProgressDocument = resumableProgress(),
        evidenceOnly: Boolean = false,
    ): ProjectedOperationOutcome.Qualified =
        CanonicalQueryCliDocuments.project(
            OperationOutcome.Qualified(
                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis(), result(measurement, progress, evidenceOnly)),
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(4).value(),
                        listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.RetainedOutput(
                                QueryExecutionContinuation.Output.parse(CONTINUATION).value(),
                                QueryPreparedCoverageDocument.Resumable,
                            ),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .value(),
            )
        ) as ProjectedOperationOutcome.Qualified

    fun rejected(): ProjectedOperationOutcome.Rejected =
        CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady))
            as ProjectedOperationOutcome.Rejected

    fun result(
        measurement: Long = 100,
        progress: QueryDiscoveryProgressDocument = QueryDiscoveryProgressDocument.Exhausted,
        evidenceOnly: Boolean = false,
    ): QueryRunResult =
        QueryRunResult(
            question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(text("src/Fixture.kt"), offset(0)),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(
                        bounded(
                            listOf(
                                QuerySymbolFieldDocument.NAME,
                                QuerySymbolFieldDocument.LOCATION,
                                QuerySymbolFieldDocument.SIGNATURE,
                            )
                        )
                    ),
                ),
            items = bounded(if (evidenceOnly) emptyList() else rows()),
            failures = bounded(emptyList()),
            discoveryObservations = bounded(listOf(observation(measurement, progress))),
            retention = QueryResultRetention.Retained(QueryResultReference.parse(RESULT).value()),
            nextCursor = QueryResultCursor.parse(4).value(),
        )

    fun observation(
        measurement: Long = 100,
        progress: QueryDiscoveryProgressDocument = QueryDiscoveryProgressDocument.Exhausted,
    ): QueryDiscoveryObservationDocument =
        QueryDiscoveryObservationDocument(
            universe =
                QueryDiscoveryUniverseDocument(
                    QueryDiscoveryDeclarationLanguageDocument.KOTLIN,
                    QueryMatchDocument.All,
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                    QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoverySourceSetsDocument.Exact(bounded(listOf(text("main")))),
                    QueryDirectoryScopeDocument(text("src"), QueryContainmentDocument.DESCENDANTS),
                    QueryPackageScopeDocument(text("fixture"), QueryContainmentDocument.DIRECT),
                ),
            ordering = QueryDiscoveryOrderDocument.KOTLIN_FILE_SOURCE_V2,
            progress = progress,
            observedStops =
                bounded(
                    if (progress == QueryDiscoveryProgressDocument.Exhausted) emptyList()
                    else listOf(QueryDiscoveryStopDocument.WORK_LIMIT_REACHED)
                ),
            examinedWorkUnits = count(measurement),
            inventoryFiles = count(measurement),
            reacquiredFiles = count(measurement),
            examinedLeaves = count(measurement),
            inventoryNanoseconds = nanos(measurement),
            declarationScanNanoseconds = nanos(measurement),
            projectionNanoseconds = nanos(measurement),
        )

    fun resumableProgress(): QueryDiscoveryProgressDocument.Resumable =
        QueryDiscoveryProgressDocument.Resumable(
            count(2),
            offset(30),
            count(5),
            count(3),
            QueryDiscoveryBytesDocument.parse(512).value(),
        )

    fun rows(): List<QueryResultItemDocument> {
        val alpha = symbol("alpha", 10, 20)
        val beta = symbol("beta", 30, 40)
        fun declaration(symbol: SymbolDocument, id: Int) =
            QueryResultItemDocument.ExactSymbol(
                exact(symbol.name.value),
                symbol.kind,
                symbol.name,
                QueryExactLocationDocument(symbol.file, symbol.range),
                symbol.compilerEvidence.signature,
                bounded(emptyList()),
                SymbolIdDocument.parse("sym:" + symbol.name.value.first().toString().repeat(42) + "A").value(),
                rowId = row(id),
            )
        fun occurrence(start: Int, id: Int) =
            QueryResultItemDocument.Occurrence(
                exact("alpha"),
                RelationFactDocument(
                    RelationKindDocument.CALLERS,
                    beta,
                    alpha,
                    RelationOccurrenceDocument(
                        text("candidate:fixture:beta"),
                        text("src/Fixture.kt"),
                        range(start, start + 5),
                    ),
                    RelationProvenanceDocument.K2_AUTHORED_SOURCE,
                    RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
                ),
                row(id),
            )
        return listOf(declaration(alpha, 1), declaration(beta, 2), occurrence(50, 3), occurrence(70, 4))
    }

    fun basis(): EvidenceBasis =
        EvidenceBasis.Live(
            LiveReadEvidence.create("/workspace", UUID.fromString(HOST), 7, LiveReadContentView.SAVED_PSI_COMMITTED, 1)
                .value()
        )

    private fun symbol(name: String, start: Int, end: Int): SymbolDocument {
        val identity = text("fixture.$name")
        val signature =
            CompilerSignatureDocument.Function(
                identity,
                CompilerReceiverDocument.Absent,
                bounded(emptyList()),
                bounded(listOf(text("kotlin.String"))),
                CompilerTypeParameterCountDocument.parse(0).value(),
            )
        return SymbolDocument.create(
                text("candidate:fixture:$name"),
                SymbolKindDocument.FUNCTION,
                text(name),
                SymbolQualifiedIdentityDocument.Available(identity),
                text("src/Fixture.kt"),
                range(start, end),
                CompilerSymbolEvidenceDocument.fromSignature(signature).value(),
            )
            .value()
    }

    private fun exact(name: String) = QueryReferenceDocument.ExactSymbol(text("exact:v5:fixture-$name"))

    private fun row(id: Int) =
        QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-00000000000$id").value()

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun range(start: Int, end: Int) = SourceRangeDocument.create(offset(start), offset(end)).value()

    private fun count(value: Long) = QueryDiscoveryCountDocument.parse(value).value()

    private fun nanos(value: Long) = QueryDiscoveryNanosecondsDocument.parse(value).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid discovery fixture: $failure")
        }
}
