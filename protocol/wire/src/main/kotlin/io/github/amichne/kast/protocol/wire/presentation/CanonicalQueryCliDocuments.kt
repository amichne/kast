@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.protocol.contract.terminalReason
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.protocol.contract.validateImpactCompletion
import io.github.amichne.kast.protocol.wire.RelationReferenceOccurrenceWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CanonicalQueryCliDocuments {
    /** Exact UTF-8 size of the public items array, including punctuation and retained row identities. */
    fun symbolPreviewBytes(items: List<QueryResultItemDocument.ExactSymbol>): Long =
        CanonicalJsonDocument.encodedBytes(
            kotlinx.serialization.builtins.ListSerializer(QueryResultItemCliDocument.serializer()),
            items.map(QueryResultItemDocument::toCliDocument),
        )

    val itemFailureSerializer: KSerializer<*>
        get() = QueryItemFailureCliDocument.serializer()

    val qualificationSerializer: KSerializer<*>
        get() = QueryQualificationCliDocument.serializer()

    val terminalReasonSerializer: KSerializer<*>
        get() = QueryTerminalReasonCliSerializer

    val completeSerializer: KSerializer<*>
        get() = QueryCompleteCliDocument.serializer()

    val qualifiedSerializer: KSerializer<*>
        get() = QueryQualifiedCliDocument.serializer()

    val rejectedSerializer: KSerializer<*>
        get() = QueryRejectedCliDocument.serializer()

    val rejectionSerializer: KSerializer<*>
        get() = QueryRejectionCliDocument.serializer()

    val itemSerializer: KSerializer<*>
        get() = QueryResultItemCliDocument.serializer()

    val referenceObservationSerializer: KSerializer<*>
        get() = RelationReferenceOccurrenceWireDocument.serializer()

    val walkObservationSerializer: KSerializer<*>
        get() = QueryWalkObservationCliDocument.serializer()

    fun project(
        outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure>
    ): ProjectedOperationOutcome {
        val validation =
            when (outcome) {
                is OperationOutcome.Complete -> outcome.evidence.payload.validateImpactCompletion()
                is OperationOutcome.Qualified -> outcome.evidence.payload.validateImpactAccounting()
                is OperationOutcome.Rejected -> Refinement.Refined(Unit)
            }
        when (validation) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected ->
                return project(
                    OperationOutcome.Rejected(
                        QueryRunRejection.ImpactPresentationRejected(
                            ImpactPresentationFailureDocument.Accounting(validation.failure)
                        )
                    )
                )
        }
        return projectClosedOutcome(
            outcome,
            complete = ::projectComplete,
            qualified = ::projectQualified,
            rejected = { rejection ->
                rejectedFactory.create(
                    QueryRejectedCliDocument(
                        QueryCliOperation.RUN,
                        QueryRejectedCliStatus.REJECTED,
                        rejection.reason().toCliDocument(),
                        rejection.recoveryAction(),
                        rejection.budgetPresence(),
                    )
                )
            },
        )
    }
}

private fun projectComplete(result: QueryRunResult, live: LiveReadCliEvidence?): CanonicalJsonDocument =
    completeFactory.create(
        QueryCompleteCliDocument(
            question = result.question,
            impactAccounting = result.impactAccounting,
            operation = QueryCliOperation.RUN,
            status = QueryCompleteCliStatus.COMPLETE,
            items = result.items.values.map(QueryResultItemDocument::toCliDocument),
            failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
            omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
            walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
            referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
            discoveryObservations = result.discoveryObservations.values,
            relationObservations = result.relationObservations.values.map { it.toWireDocument() },
            retention = result.retention,
            nextCursor = result.nextCursor,
            coverage = QueryCoverageCliDocument(exhaustive = true),
            pageProgress = result.pageProgress(),
            executionBudget = result.executionBudget,
            referenceAcquisitions = result.referenceAcquisitions,
            invocation = result.invocation,
            live = live,
        )
    )

private fun projectQualified(
    result: QueryRunResult,
    qualification: QueryRunQualification,
    live: LiveReadCliEvidence?,
): CanonicalJsonDocument =
    qualifiedFactory.create(
        QueryQualifiedCliDocument(
            question = result.question,
            impactAccounting = result.impactAccounting,
            operation = QueryCliOperation.RUN,
            status = QueryQualifiedCliStatus.QUALIFIED,
            items = result.items.values.map(QueryResultItemDocument::toCliDocument),
            failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
            omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
            walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
            referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
            discoveryObservations = result.discoveryObservations.values,
            relationObservations = result.relationObservations.values.map { it.toWireDocument() },
            retention = result.retention,
            nextCursor = result.nextCursor,
            coverage = QueryCoverageCliDocument(exhaustive = false),
            pageProgress = result.pageProgress(),
            qualification =
                QueryQualificationCliDocument(
                    qualification.knownMinimum.value,
                    qualification.limitations,
                    qualification.progress,
                ),
            continuation = qualification.progress.continuationToken?.value,
            terminalReason = qualification.progress.terminalReason,
            executionBudget = result.executionBudget,
            referenceAcquisitions = result.referenceAcquisitions,
            invocation = result.invocation,
            live = live,
        )
    )

@Serializable
private data class QueryCompleteCliDocument(
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocation: io.github.amichne.kast.protocol.contract.QueryInvocationDocument? = null,
    val question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
    @SerialName("impact_accounting")
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val operation: QueryCliOperation,
    val status: QueryCompleteCliStatus,
    val items: List<QueryResultItemCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations") val referenceObservations: List<RelationReferenceOccurrenceWireDocument>,
    @SerialName("discovery_observations")
    val discoveryObservations: List<io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument>,
    @SerialName("relation_observations")
    val relationObservations: List<io.github.amichne.kast.protocol.wire.QueryRelationObservationWireDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    @SerialName("page_progress") val pageProgress: QueryPageProgressCliDocument,
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocation: io.github.amichne.kast.protocol.contract.QueryInvocationDocument? = null,
    val question: io.github.amichne.kast.protocol.contract.QueryQuestionDocument,
    @SerialName("impact_accounting")
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val operation: QueryCliOperation,
    val status: QueryQualifiedCliStatus,
    val items: List<QueryResultItemCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val failures: List<QueryItemFailureCliDocument>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val omissions: List<QueryRelationOmissionCliDocument>? = null,
    @SerialName("walk_observations") val walkObservations: List<QueryWalkObservationCliDocument>,
    @SerialName("reference_observations") val referenceObservations: List<RelationReferenceOccurrenceWireDocument>,
    @SerialName("discovery_observations")
    val discoveryObservations: List<io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument>,
    @SerialName("relation_observations")
    val relationObservations: List<io.github.amichne.kast.protocol.wire.QueryRelationObservationWireDocument>,
    val retention: QueryResultRetention,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("next_cursor")
    val nextCursor: QueryResultCursor? = null,
    val coverage: QueryCoverageCliDocument,
    @SerialName("page_progress") val pageProgress: QueryPageProgressCliDocument,
    val qualification: QueryQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = MAXIMUM_QUERY_CLI_TEXT_LENGTH,
    )
    val continuation: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("terminal_reason")
    val terminalReason:
        @Serializable(with = QueryTerminalReasonCliSerializer::class)
        io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument? =
        null,
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

/** Counts describe this returned page, including productive pages with no semantic rows. */
@Serializable
private data class QueryPageProgressCliDocument(
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val rows: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("walk_evidence")
    val walkEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("reference_evidence")
    val referenceEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("relation_evidence")
    val relationEvidence: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("scope_exclusions")
    val scopeExclusions: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("callback_observations")
    val callbackObservations: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    @SerialName("excluded_callbacks")
    val excludedCallbacks: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val omissions: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val failures: Int,
)

private fun QueryRunResult.pageProgress(): QueryPageProgressCliDocument =
    QueryPageProgressCliDocument(
        rows = items.values.size,
        walkEvidence = walkObservations.values.size,
        referenceEvidence =
            referenceObservations.values.size + walkObservations.values.sumOf { it.referenceOccurrences.values.size },
        relationEvidence = relationObservations.values.size,
        scopeExclusions =
            relationObservations.values.sumOf { it.scopeExclusions.values.size } +
                walkObservations.values.sumOf { it.scopeExclusions.values.size },
        callbackObservations =
            relationObservations.values.sumOf { it.callbackObservations.values.size } +
                walkObservations.values.sumOf { it.callbackObservations.values.size },
        excludedCallbacks =
            relationObservations.values.sumOf { observation ->
                observation.callbackObservations.values.count {
                    it.namedPolicy is io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument.Excluded
                }
            } +
                walkObservations.values.sumOf { observation ->
                    observation.callbackObservations.values.count {
                        it.observation.namedPolicy is
                            io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument.Excluded
                    }
                },
        omissions = omissions.values.size,
        failures = failures.values.size,
    )

@Serializable
private data class QueryRejectedCliDocument(
    val operation: QueryCliOperation,
    val status: QueryRejectedCliStatus,
    val rejection: QueryRejectionCliDocument,
    @SerialName("next_action") val nextAction: ReadRecoveryAction,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
)

@Serializable
private data class QueryQualificationCliDocument(
    val knownMinimum: Int,
    val limitations:
        List<
            @Serializable(with = QueryLimitationCliSerializer::class)
            io.github.amichne.kast.protocol.contract.QueryLimitationDocument
        >,
    val progress: QueryQualifiedProgressDocument,
)

@Serializable
private data class QueryRelationOmissionCliDocument(
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val subject: String,
    @Serializable(with = RelationKindCliSerializer::class)
    val relation: io.github.amichne.kast.protocol.contract.RelationKindDocument,
    val evidence: RelationOmissionCliDocument,
)

private fun QueryRelationOmissionDocument.toCliDocument() =
    QueryRelationOmissionCliDocument(subject.toCliDocument(), relation, evidence.toCliDocument())

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

private const val MAXIMUM_QUERY_CLI_TEXT_LENGTH = 1_048_576

internal fun QueryReferenceDocument.toCliDocument(): String = token.value
