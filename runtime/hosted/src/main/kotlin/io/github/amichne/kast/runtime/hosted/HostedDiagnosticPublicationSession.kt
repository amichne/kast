package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.protocol.DiagnosticCheckpointStore
import io.github.amichne.kast.query.protocol.DiagnosticExecutionClaim
import io.github.amichne.kast.query.protocol.DiagnosticExecutionPublication
import io.github.amichne.kast.query.protocol.DiagnosticExecutionPublicationResult
import io.github.amichne.kast.query.protocol.DiagnosticPublicationFailure
import io.github.amichne.kast.query.protocol.DiagnosticPublishedPage
import io.github.amichne.kast.query.protocol.QueryRetentionByteCount
import io.github.amichne.kast.query.protocol.publicationPage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationEffect
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Scan and output ownership commit together in the existing diagnostic checkpoint store. */
internal class HostedDiagnosticPublicationSession(private val context: HostedSemanticReadContext) :
    DiagnosticExecutionPublication, HostedReadPublicationEffect {
    private sealed interface State {
        data object Empty : State

        data class Prepared(val store: DiagnosticCheckpointStore, val claim: DiagnosticExecutionClaim) : State

        data class Fitted(val prepared: Prepared, val page: DiagnosticPublishedPage) : State

        data object Ended : State
    }

    private var state: State = State.Empty

    override fun prepare(
        store: DiagnosticCheckpointStore,
        claim: DiagnosticExecutionClaim,
        page: DiagnosticPublishedPage,
    ): DiagnosticExecutionPublicationResult {
        if (state != State.Empty)
            return DiagnosticExecutionPublicationResult.Rejected(
                io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE
            )
        return when (context.preparePublication(this)) {
            is Refinement.Refined -> {
                state = State.Prepared(store, claim)
                context.measureRetainedState(HostedRetentionOwner.DIAGNOSTIC, store.retentionMeasurements())
                DiagnosticExecutionPublicationResult.PREPARED
            }
            is Refinement.Rejected ->
                DiagnosticExecutionPublicationResult.Rejected(
                    io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE
                )
        }
    }

    fun retain(
        page: HostedDiagnosticOutcome
    ): Refinement<
        io.github.amichne.kast.protocol.contract.ProtocolText,
        io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection,
    > {
        val prepared =
            state as? State.Prepared
                ?: return Refinement.Rejected(
                    io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE
                )
        val detached = page.publicationPage()
        val encoded =
            when (val encoded = CanonicalOperationWireBindings.diagnosticCheck.encodeOutcome(detached)) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected ->
                    return Refinement.Rejected(
                        io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION
                    )
            }
        val bytes =
            when (val measured = QueryRetentionByteCount.parse(encoded.toByteArray(Charsets.UTF_8).size.toLong())) {
                is Refinement.Refined -> measured.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION
                    )
            }
        return prepared.store.issueOutput(prepared.claim, detached, bytes).also {
            context.measureRetainedState(HostedRetentionOwner.DIAGNOSTIC, prepared.store.retentionMeasurements())
        }
    }

    fun fitted(response: HostedResponse): Refinement<Unit, HostedQueryFailure> {
        val prepared =
            state as? State.Prepared ?: return publicationRejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        return when (val decoded = CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(response.document)) {
            is WireDecoding.Decoded -> {
                state = State.Fitted(prepared, decoded.value.publicationPage())
                Refinement.Refined(Unit)
            }
            is WireDecoding.Rejected -> publicationRejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        }
    }

    override fun commit(): Refinement<Unit, HostedQueryFailure> {
        val fitted =
            state as? State.Fitted ?: return publicationRejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        return when (val committed = fitted.prepared.store.commit(fitted.prepared.claim, fitted.page)) {
            is Refinement.Refined -> {
                state = State.Ended
                context.measureRetainedState(
                    HostedRetentionOwner.DIAGNOSTIC,
                    fitted.prepared.store.retentionMeasurements(),
                )
                Refinement.Refined(Unit)
            }
            is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.Publication(committed.failure.hostCause()))
        }
    }

    override fun discard() {
        when (val active = state) {
            is State.Prepared -> {
                active.store.discard(active.claim)
                context.measureRetainedState(HostedRetentionOwner.DIAGNOSTIC, active.store.retentionMeasurements())
            }
            is State.Fitted -> {
                active.prepared.store.discard(active.prepared.claim)
                context.measureRetainedState(
                    HostedRetentionOwner.DIAGNOSTIC,
                    active.prepared.store.retentionMeasurements(),
                )
            }
            State.Empty,
            State.Ended -> Unit
        }
        state = State.Ended
    }
}

private fun DiagnosticPublicationFailure.hostCause(): HostedPublicationFailureCause =
    when (this) {
        DiagnosticPublicationFailure.OWNER_RETIRED -> HostedPublicationFailureCause.OWNER_RETIRED
        DiagnosticPublicationFailure.CLAIM_UNAVAILABLE -> HostedPublicationFailureCause.CLAIM_UNAVAILABLE
        DiagnosticPublicationFailure.EXPIRED -> HostedPublicationFailureCause.EXPIRED
        DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE -> HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE
        DiagnosticPublicationFailure.PUBLISHED_PAGE_MISMATCH -> HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH
        DiagnosticPublicationFailure.NON_ADVANCING_SUCCESSOR -> HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR
        DiagnosticPublicationFailure.INVALID_FITTED_PAGE -> HostedPublicationFailureCause.INVALID_FITTED_PAGE
        DiagnosticPublicationFailure.CAPACITY_EXCEEDED -> HostedPublicationFailureCause.CAPACITY_EXCEEDED
    }

private fun publicationRejected(cause: HostedPublicationFailureCause) =
    Refinement.Rejected(HostedQueryFailure.Publication(cause))
