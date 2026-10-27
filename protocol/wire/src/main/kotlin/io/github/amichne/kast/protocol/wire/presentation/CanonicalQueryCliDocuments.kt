@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRowWindowDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.protocol.contract.originalEvidenceSummaryDocument
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
    fun symbolPreviewBytes(items: List<QueryResultItemDocument.ExactSymbol>): Long = previewBytes(items)

    fun previewBytes(items: List<QueryResultItemDocument>): Long =
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

    val callbackObservationSerializer: KSerializer<*>
        get() = io.github.amichne.kast.protocol.wire.QueryCallbackObservationWireDocument.serializer()

    val callableObservationSerializer: KSerializer<*>
        get() = io.github.amichne.kast.protocol.wire.QueryCallableObservationWireDocument.serializer()

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
            originalEvidenceSummary = result.originalEvidenceSummaryDocument,
            deferredEvidence = result.deferredEvidence.values,
            detail = result.detail,
            interpretation = result.interpretation,
            question = result.question,
            impactAccounting = result.impactAccounting,
            operation = QueryCliOperation.RUN,
            status = QueryCompleteCliStatus.COMPLETE,
            items = result.items.values.map(QueryResultItemDocument::toCliDocument),
            originalItems = result.items.values,
            presentationOutput = result.presentationOutput,
            failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
            omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
            walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
            referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
            discoveryObservations = result.discoveryObservations.values.map(QueryDiscoveryObservationCliDocument::from),
            relationObservations = result.relationObservations.values.map { it.toWireDocument() },
            retention = result.retention,
            rowWindow = result.presentationWindow?.let(QueryRowWindowDocument::from),
            nextCursor = result.presentationWindow?.nextCursor,
            coverage = QueryCoverageCliDocument(exhaustive = true),
            pageProgress = result.pageProgress(),
            executionBudget = result.executionBudget,
            referenceAcquisitions = result.referenceAcquisitions,
            invocation = result.invocation,
            evidenceWindow = result.evidenceWindow,
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
            originalEvidenceSummary = result.originalEvidenceSummaryDocument,
            deferredEvidence = result.deferredEvidence.values,
            detail = result.detail,
            interpretation = result.interpretation,
            question = result.question,
            impactAccounting = result.impactAccounting,
            operation = QueryCliOperation.RUN,
            status = QueryQualifiedCliStatus.QUALIFIED,
            items = result.items.values.map(QueryResultItemDocument::toCliDocument),
            originalItems = result.items.values,
            presentationOutput = result.presentationOutput,
            failures = result.failures.values.map(QueryItemFailureDocument::toCliDocument),
            omissions = result.omissions.values.map(QueryRelationOmissionDocument::toCliDocument),
            walkObservations = result.walkObservations.values.map { it.toQueryCliDocument() },
            referenceObservations = result.referenceObservations.values.map { it.toWireDocument() },
            discoveryObservations = result.discoveryObservations.values.map(QueryDiscoveryObservationCliDocument::from),
            relationObservations = result.relationObservations.values.map { it.toWireDocument() },
            retention = result.retention,
            rowWindow = result.presentationWindow?.let(QueryRowWindowDocument::from),
            nextCursor = result.presentationWindow?.nextCursor,
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
            evidenceWindow = result.evidenceWindow,
            live = live,
        )
    )

@Serializable data class QueryCoverageCliDocument(val exhaustive: Boolean)

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

private val completeFactory =
    CanonicalJsonDocument.generated(QueryCompleteCliDocument.serializer()) {
        it.compactGraph()
            .copy(
                failures = it.failures?.takeIf(List<*>::isNotEmpty),
                omissions = it.omissions?.takeIf(List<*>::isNotEmpty),
                discoveryObservations = it.discoveryObservations.map(QueryDiscoveryObservationCliDocument::compact),
                executionBudget = null,
                live = it.live?.compact(),
            )
    }
private val qualifiedFactory =
    CanonicalJsonDocument.generated(QueryQualifiedCliDocument.serializer()) {
        it.compactGraph()
            .copy(
                failures = it.failures?.takeIf(List<*>::isNotEmpty),
                omissions = it.omissions?.takeIf(List<*>::isNotEmpty),
                discoveryObservations = it.discoveryObservations.map(QueryDiscoveryObservationCliDocument::compact),
                continuation = null,
                terminalReason = null,
                live = it.live?.compact(),
            )
    }
private val rejectedFactory = CanonicalJsonDocument.generated(QueryRejectedCliDocument.serializer())

private fun QueryCompleteCliDocument.compactGraph(): QueryCompleteCliDocument {
    val retained = retention as? QueryResultRetention.Retained ?: return this
    return when (
        val projected =
            projectRetainedRelationGraph(
                retained.reference,
                items,
                referenceObservations,
                originalItems,
                presentationOutput,
            )
    ) {
        is QueryRelationGraphProjection.Projected ->
            copy(items = projected.items, relationGraph = projected.graph, referenceObservations = emptyList())
        is QueryRelationGraphProjection.Ineligible -> this
    }
}

private fun QueryQualifiedCliDocument.compactGraph(): QueryQualifiedCliDocument {
    val retained = retention as? QueryResultRetention.Retained ?: return this
    return when (
        val projected =
            projectRetainedRelationGraph(
                retained.reference,
                items,
                referenceObservations,
                originalItems,
                presentationOutput,
            )
    ) {
        is QueryRelationGraphProjection.Projected ->
            copy(items = projected.items, relationGraph = projected.graph, referenceObservations = emptyList())
        is QueryRelationGraphProjection.Ineligible -> this
    }
}

internal fun QueryReferenceDocument.toCliDocument(): String = token.value
