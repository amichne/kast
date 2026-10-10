package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.protocol.QueryExecutionClaim
import io.github.amichne.kast.query.protocol.QueryExecutionPublication
import io.github.amichne.kast.query.protocol.QueryExecutionPublicationResult
import io.github.amichne.kast.query.protocol.QueryOutputIssuance
import io.github.amichne.kast.query.protocol.QueryPublicationCommit
import io.github.amichne.kast.query.protocol.QueryPublicationFailure
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.query.protocol.QueryRetentionByteCount
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationEffect
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRejectedPublication
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** One store owns execution, encoded suffixes and the final immutable published page. */
internal class HostedQueryPublicationSession(
    private val context: HostedSemanticReadContext,
    private val request: QueryRunRequest,
) : QueryExecutionPublication, HostedReadPublicationEffect {
    private sealed interface State {
        data object Empty : State

        data class Prepared(
            val store: QueryStateStore,
            val claim: QueryExecutionClaim,
            val semantic: QueryPublishedPage,
        ) : State

        data class Fitted(val prepared: Prepared, val encoded: QueryPublicationPageCharge.Encoded) : State

        data object Ended : State
    }

    private var state: State = State.Empty

    override val rejectedPublication: HostedReadRejectedPublication
        get() =
            when (val active = state) {
                is State.Fitted -> fittedRejectedQueryPublication(active.prepared.semantic, active.encoded)
                State.Empty,
                is State.Prepared,
                State.Ended -> HostedReadRejectedPublication.Discard
            }

    override fun prepare(
        store: QueryStateStore,
        claim: QueryExecutionClaim,
        page: QueryPublishedPage,
    ): QueryExecutionPublicationResult {
        if (state != State.Empty)
            return QueryExecutionPublicationResult.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
        return when (context.preparePublication(this)) {
            is Refinement.Refined -> {
                state =
                    State.Prepared(store, claim, page.withQueryDiagnosticIdentity(context.readTrace).publicationPage())
                context.observation.phase(IntellijReadPhase.RETENTION)
                measureRetention(store)
                QueryExecutionPublicationResult.PREPARED
            }
            is Refinement.Rejected ->
                QueryExecutionPublicationResult.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
        }
    }

    fun fitted(encoded: QueryPublicationPageCharge.Encoded) {
        val prepared = state as? State.Prepared ?: return
        state = State.Fitted(prepared, encoded)
    }

    fun retain(outcome: HostedQueryOutcome): HostedOutputRetention {
        val prepared =
            state as? State.Prepared
                ?: return HostedOutputRetention.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        val page = outcome.publicationPage()
        val document =
            when (val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(page)) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected ->
                    return HostedOutputRetention.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
            }
        val bytes =
            when (val measured = QueryRetentionByteCount.parse(document.toByteArray(Charsets.UTF_8).size.toLong())) {
                is Refinement.Refined -> measured.value
                is Refinement.Rejected ->
                    return HostedOutputRetention.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
            }
        context.observation.phase(IntellijReadPhase.RETENTION)
        val issued = prepared.store.issueOutput(request, context.authority, page, prepared.claim, bytes)
        measureRetention(prepared.store)
        context.observation.phase(IntellijReadPhase.ENCODING)
        return issued.hostedRetention()
    }

    override fun commit(): Refinement<Unit, HostedQueryFailure> {
        val fitted =
            state as? State.Fitted
                ?: return Refinement.Rejected(
                    HostedQueryFailure.Publication(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
                )
        context.observation.phase(IntellijReadPhase.RETENTION)
        return when (
            val committed =
                fitted.prepared.store.commitPublication(fitted.prepared.claim, fitted.encoded.page, fitted.encoded)
        ) {
            QueryPublicationCommit.Committed -> {
                fitted.prepared.store.releasePublication(fitted.prepared.claim)
                state = State.Ended
                measureRetention(fitted.prepared.store)
                Refinement.Refined(Unit)
            }
            is QueryPublicationCommit.Rejected -> {
                context.observation.terminated(committed.failure.observedTermination())
                Refinement.Rejected(HostedQueryFailure.Publication(committed.failure.hostedCause()))
            }
        }
    }

    private fun measureRetention(store: QueryStateStore) {
        val measured = store.retentionMeasurements()
        fun record(gauge: IntellijReadGauge, value: Long) {
            when (val refined = IntellijReadGaugeValue.parse(value)) {
                is Refinement.Refined -> context.observation.measure(gauge, refined.value)
                is Refinement.Rejected -> error("Retention accounting produced a negative measurement")
            }
        }
        record(IntellijReadGauge.QUERY_RETAINED_BYTES, measured.retainedBytes.value)
        record(IntellijReadGauge.QUERY_RETAINED_BYTES_HIGH_WATER, measured.highWaterBytes.value)
        record(IntellijReadGauge.QUERY_RETAINED_ENTRIES, measured.retainedEntries.value.toLong())
    }

    override fun discard() {
        val prepared =
            when (val active = state) {
                is State.Prepared -> active
                is State.Fitted -> active.prepared
                State.Empty,
                State.Ended -> null
            }
        prepared?.let {
            context.observation.phase(IntellijReadPhase.RETENTION)
            it.store.releasePublication(it.claim)
            measureRetention(it.store)
        }
        state = State.Ended
    }
}

/** Only the exact encoded page prepared by this owner can preserve rejected policy evidence. */
internal fun fittedRejectedQueryPublication(
    prepared: QueryPublishedPage,
    encoded: QueryPublicationPageCharge.Encoded,
): HostedReadRejectedPublication {
    if (encoded.page != prepared) return HostedReadRejectedPublication.Discard
    val rejected =
        encoded.page as? io.github.amichne.kast.kernel.OperationOutcome.Rejected
            ?: return HostedReadRejectedPublication.Discard
    val completion =
        rejected.reason as? io.github.amichne.kast.protocol.contract.QueryRunRejection.CompletionUnproven
            ?: return HostedReadRejectedPublication.Discard
    return when (val evidence = completion.evidence) {
        is io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument.Retained ->
            HostedReadRejectedPublication.RetainedEvidence(evidence)
        is io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument.Unavailable ->
            HostedReadRejectedPublication.Discard
    }
}

internal fun QueryOutputIssuance.hostedRetention(): HostedOutputRetention =
    when (this) {
        is QueryOutputIssuance.Issued ->
            when (val token = ProtocolText.parse(token.value)) {
                is Refinement.Refined -> HostedOutputRetention.Retained(token.value)
                is Refinement.Rejected ->
                    HostedOutputRetention.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
            }
        QueryOutputIssuance.CapacityExceeded -> HostedOutputRetention.CapacityExceeded
        QueryOutputIssuance.EncodingRejected ->
            HostedOutputRetention.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        QueryOutputIssuance.Unavailable ->
            HostedOutputRetention.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        QueryOutputIssuance.NonAdvancing ->
            HostedOutputRetention.Rejected(HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR)
        QueryOutputIssuance.PublishedPageImmutable ->
            HostedOutputRetention.Rejected(HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH)
    }

private fun QueryPublicationFailure.observedTermination(): IntellijReadTermination =
    when (this) {
        QueryPublicationFailure.OWNER_RETIRED -> IntellijReadTermination.QUERY_PUBLICATION_OWNER_RETIRED
        QueryPublicationFailure.CLAIM_UNAVAILABLE -> IntellijReadTermination.QUERY_PUBLICATION_CLAIM_UNAVAILABLE
        QueryPublicationFailure.EXPIRED -> IntellijReadTermination.QUERY_PUBLICATION_EXPIRED
        QueryPublicationFailure.DEPENDENCY_UNAVAILABLE ->
            IntellijReadTermination.QUERY_PUBLICATION_DEPENDENCY_UNAVAILABLE
        QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH -> IntellijReadTermination.QUERY_PUBLICATION_PAGE_MISMATCH
        QueryPublicationFailure.NON_ADVANCING_SUCCESSOR -> IntellijReadTermination.QUERY_PUBLICATION_NON_ADVANCING
    }

private fun QueryPublicationFailure.hostedCause(): HostedPublicationFailureCause =
    when (this) {
        QueryPublicationFailure.OWNER_RETIRED -> HostedPublicationFailureCause.OWNER_RETIRED
        QueryPublicationFailure.CLAIM_UNAVAILABLE -> HostedPublicationFailureCause.CLAIM_UNAVAILABLE
        QueryPublicationFailure.EXPIRED -> HostedPublicationFailureCause.EXPIRED
        QueryPublicationFailure.DEPENDENCY_UNAVAILABLE -> HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE
        QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH -> HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH
        QueryPublicationFailure.NON_ADVANCING_SUCCESSOR -> HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR
    }
