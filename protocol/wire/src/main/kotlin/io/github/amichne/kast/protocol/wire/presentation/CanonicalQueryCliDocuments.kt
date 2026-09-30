@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
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
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.protocol.contract.terminalReason
import io.github.amichne.kast.protocol.wire.RelationReferenceOccurrenceWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CanonicalQueryCliDocuments {
    val itemSerializer: KSerializer<*>
        get() = QueryResultItemCliDocument.serializer()

    val referenceObservationSerializer: KSerializer<*>
        get() = RelationReferenceOccurrenceWireDocument.serializer()

    val walkObservationSerializer: KSerializer<*>
        get() = QueryWalkObservationCliDocument.serializer()

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
                        referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
                        discoveryObservations = result.discoveryObservations.values,
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
            referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
            discoveryObservations = result.discoveryObservations.values,
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations")
    val referenceObservations: List<RelationReferenceOccurrenceWireDocument> = emptyList(),
    @SerialName("discovery_observations")
    val discoveryObservations: List<io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument> =
        emptyList(),
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations")
    val referenceObservations: List<RelationReferenceOccurrenceWireDocument> = emptyList(),
    @SerialName("discovery_observations")
    val discoveryObservations: List<io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument> =
        emptyList(),
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    val qualification: QueryQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val continuation: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("terminal_reason")
    val terminalReason: String? = null,
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
private data class QueryRelationOmissionCliDocument(
    val subject: String,
    val relation: String,
    val evidence: RelationOmissionCliDocument,
)

@Serializable private data class QueryRefinementLocationCliDocument(val file: String, val offset: Int)

@Serializable
private sealed interface QueryItemFailureCliDocument {
    @Serializable
    @SerialName("refinement")
    data class Refinement(val location: QueryRefinementLocationCliDocument, val reason: String) :
        QueryItemFailureCliDocument

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

private fun QueryRelationOmissionDocument.toCliDocument() =
    QueryRelationOmissionCliDocument(subject.toCliDocument(), relation.cliName(), evidence.toCliDocument())

private fun QueryItemFailureDocument.toCliDocument(): QueryItemFailureCliDocument =
    when (this) {
        is QueryItemFailureDocument.Refinement ->
            QueryItemFailureCliDocument.Refinement(
                QueryRefinementLocationCliDocument(location.file.value, location.offset.value),
                reason.cliName(),
            )
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

internal fun QueryReferenceDocument.toCliDocument(): String = token.value

private val completeFactory =
    CanonicalJsonDocument.generated(QueryCompleteCliDocument.serializer()) {
        it.copy(
            failures = it.failures?.takeIf(List<*>::isNotEmpty),
            omissions = it.omissions?.takeIf(List<*>::isNotEmpty),
            executionBudget = null,
            live = it.live?.compact(),
        )
    }
private val qualifiedFactory =
    CanonicalJsonDocument.generated(QueryQualifiedCliDocument.serializer()) {
        it.copy(
            failures = it.failures?.takeIf(List<*>::isNotEmpty),
            omissions = it.omissions?.takeIf(List<*>::isNotEmpty),
            continuation = null,
            terminalReason = null,
            live = it.live?.compact(),
        )
    }
private val rejectedFactory = CanonicalJsonDocument.generated(QueryRejectedCliDocument.serializer())
