@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.QueryBindingCellDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.protocol.contract.terminalReason
import io.github.amichne.kast.protocol.wire.SymbolKindWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CanonicalQueryCliDocuments {
    /** The sealed serializer retains the item discriminator for installed output schemas. */
    val itemSerializer: KSerializer<*>
        get() = QueryResultItemCliDocument.serializer()

    fun project(outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>) =
        projectClosedOutcome(
            outcome,
            complete = { result, live ->
                completeFactory.create(
                    QueryCompleteCliDocument(
                        operation = CanonicalOperation.QUERY_RUN.id.value,
                        status = "complete",
                        items = result.items.values.map(QueryResultItemDocument::toCliDocument),
                        failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
                        omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
                        walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
                        retention = result.retention,
                        nextCursor = result.nextCursor,
                        coverage = QueryCoverageCliDocument(exhaustive = true),
                        executionBudget = result.executionBudget,
                        referenceAcquisitions = result.referenceAcquisitions,
                        live = live,
                    )
                )
            },
            qualified = ::projectQualified,
            rejected = { rejection ->
                rejectedFactory.create(
                    QueryRejectedCliDocument(
                        CanonicalOperation.QUERY_RUN.id.value,
                        "rejected",
                        rejection.reason().toCliDocument(),
                        rejection.recoveryAction(),
                        rejection.budgetPresence(),
                    )
                )
            },
        )
}

private fun projectQualified(
    result: QueryRunResult,
    qualification: QueryRunQualification,
    live: LiveReadCliEvidence?,
): CanonicalJsonDocument =
    qualifiedFactory.create(
        QueryQualifiedCliDocument(
            operation = CanonicalOperation.QUERY_RUN.id.value,
            status = "qualified",
            items = result.items.values.map(QueryResultItemDocument::toCliDocument),
            failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
            omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
            walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
            retention = result.retention,
            nextCursor = result.nextCursor,
            coverage = QueryCoverageCliDocument(exhaustive = false),
            qualification =
                QueryQualificationCliDocument(
                    qualification.knownMinimum.value,
                    qualification.limitations.map(Enum<*>::cliName),
                    qualification.progress,
                ),
            continuation = qualification.progress.continuationToken?.value,
            terminalReason = qualification.progress.terminalReason?.cliName(),
            executionBudget = result.executionBudget,
            referenceAcquisitions = result.referenceAcquisitions,
            live = live,
        )
    )

@Serializable
private data class QueryCompleteCliDocument(
    val operation: String,
    val status: String,
    val items: List<QueryResultItemCliDocument>,
    val failures: List<QueryItemFailureCliDocument>,
    val omissions: List<QueryRelationOmissionCliDocument>,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private data class QueryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val items: List<QueryResultItemCliDocument>,
    val failures: List<QueryItemFailureCliDocument>,
    val omissions: List<QueryRelationOmissionCliDocument>,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    val qualification: QueryQualificationCliDocument,
    val continuation: String?,
    @SerialName("terminal_reason") val terminalReason: String?,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable data class QueryCoverageCliDocument(val exhaustive: Boolean)

@Serializable
private data class QueryRejectedCliDocument(
    val operation: String,
    val status: String,
    val rejection: QueryRejectionCliDocument,
    @SerialName("next_action") val nextAction: ReadRecoveryAction,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
)

@Serializable
private data class QueryQualificationCliDocument(
    val knownMinimum: Int,
    val limitations: List<String>,
    val progress: QueryQualifiedProgressDocument,
)

@Serializable
private sealed interface QueryResultItemCliDocument {
    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(
        @ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        val kind: SymbolKindWireDocument,
        val name: String?,
        val location: QueryExactLocationCliDocument?,
        val signature: CompilerSignatureCliDocument?,
        val connections: List<RelationFactCliDocument>,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val source: QuerySourceWindowCliDocument? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = "^result-row:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        val ref: String,
        val relation: RelationFactCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("traversal_record")
    data class TraversalRecord(
        val ref: String,
        val record: QueryTraversalRecordCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("binding_row")
    data class BindingRow(
        val left: QueryBindingCellCliDocument,
        val right: QueryBindingCellCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = "^result-row:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val rowId: String? = null,
    ) : QueryResultItemCliDocument
}

@Serializable
private sealed interface QueryBindingCellCliDocument {
    @Serializable
    @SerialName("symbol")
    data class Symbol(
        @ProtocolStringConstraint(pattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$", maximumLength = 64) val name: String,
        val symbol: QueryResultItemCliDocument.ExactSymbol,
    ) : QueryBindingCellCliDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        @ProtocolStringConstraint(pattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$", maximumLength = 64) val name: String,
        val symbol: QueryResultItemCliDocument.ExactSymbol,
        val relation: RelationFactCliDocument,
    ) : QueryBindingCellCliDocument
}

@Serializable
private data class QueryRelationOmissionCliDocument(
    val subject: String,
    val relation: String,
    val evidence: RelationOmissionCliDocument,
)

@Serializable
private data class QuerySourceWindowCliDocument(
    val text: String,
    val startLine: Long,
    val endLine: Long,
)

@Serializable private data class QueryExactLocationCliDocument(val file: String, val range: SourceRangeCliDocument)

@Serializable
private sealed interface QueryItemFailureCliDocument {
    @Serializable
    @SerialName("refinement")
    data class Refinement(val ref: String, val reason: String) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("exact-reference")
    data class ExactReference(val ref: String, val reason: String) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("predicate")
    data class Predicate(val ref: String, val reason: String) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("source")
    data class Source(val ref: String, val reason: String) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("relation")
    data class Relation(
        val ref: String,
        val relation: String,
        val reason: String,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("walk")
    data class Walk(
        val ref: String,
        val relation: String,
        val reason: QueryWalkFailureCliDocument,
    ) : QueryItemFailureCliDocument
}

private fun QueryResultItemDocument.toCliDocument(): QueryResultItemCliDocument =
    when (this) {
        is QueryResultItemDocument.ExactSymbol -> toExactCliDocument()
        is QueryResultItemDocument.Occurrence ->
            QueryResultItemCliDocument.Occurrence(ref.toCliDocument(), relation.toCliDocument(), rowId?.value)
        is QueryResultItemDocument.TraversalRecord ->
            QueryResultItemCliDocument.TraversalRecord(ref.toCliDocument(), record.toQueryCliDocument(), rowId?.value)
        is QueryResultItemDocument.BindingRow ->
            QueryResultItemCliDocument.BindingRow(left.toCliDocument(), right.toCliDocument(), rowId?.value)
    }

private fun QueryResultItemDocument.ExactSymbol.toExactCliDocument(): QueryResultItemCliDocument.ExactSymbol =
    QueryResultItemCliDocument.ExactSymbol(
        ref.toCliDocument(),
        kind.toWireDocument(),
        name?.value,
        location?.let {
            QueryExactLocationCliDocument(
                it.file.value,
                SourceRangeCliDocument(it.range.startInclusive.value, it.range.endExclusive.value),
            )
        },
        signature?.toCliDocument(),
        connections.values.map(RelationFactDocument::toCliDocument),
        source?.let {
            QuerySourceWindowCliDocument(
                it.text.value,
                it.lines.startInclusive.value,
                it.lines.endInclusive.value,
            )
        },
        rowId?.value,
    )

private fun QueryBindingCellDocument.toCliDocument(): QueryBindingCellCliDocument =
    when (this) {
        is QueryBindingCellDocument.Symbol ->
            QueryBindingCellCliDocument.Symbol(
                name.value,
                symbol.toExactCliDocument(),
            )
        is QueryBindingCellDocument.Occurrence ->
            QueryBindingCellCliDocument.Occurrence(
                name.value,
                symbol.toExactCliDocument(),
                relation.toCliDocument(),
            )
    }

private fun QueryRelationOmissionDocument.toCliDocument() =
    QueryRelationOmissionCliDocument(subject.toCliDocument(), relation.cliName(), evidence.toCliDocument())

private fun QueryItemFailureDocument.toCliDocument(): QueryItemFailureCliDocument =
    when (this) {
        is QueryItemFailureDocument.Refinement ->
            QueryItemFailureCliDocument.Refinement(ref.toCliDocument(), reason.cliName())
        is QueryItemFailureDocument.ExactReference ->
            QueryItemFailureCliDocument.ExactReference(ref.toCliDocument(), reason.cliName())
        is QueryItemFailureDocument.Predicate ->
            QueryItemFailureCliDocument.Predicate(ref.toCliDocument(), reason.cliName())
        is QueryItemFailureDocument.Source -> QueryItemFailureCliDocument.Source(ref.toCliDocument(), reason.cliName())
        is QueryItemFailureDocument.Relation ->
            QueryItemFailureCliDocument.Relation(
                ref.toCliDocument(),
                relation.cliName(),
                reason.cliName(),
            )
        is QueryItemFailureDocument.Walk ->
            QueryItemFailureCliDocument.Walk(ref.toCliDocument(), relation.cliName(), reason.toQueryCliDocument())
    }

/** Presentation extracts the issued token verbatim; the retained domain type still owns its family. */
private fun QueryReferenceDocument.toCliDocument(): String = token.value

private val completeFactory = CanonicalJsonDocument.generated(QueryCompleteCliDocument.serializer())
private val qualifiedFactory = CanonicalJsonDocument.generated(QueryQualifiedCliDocument.serializer())
private val rejectedFactory = CanonicalJsonDocument.generated(QueryRejectedCliDocument.serializer())
