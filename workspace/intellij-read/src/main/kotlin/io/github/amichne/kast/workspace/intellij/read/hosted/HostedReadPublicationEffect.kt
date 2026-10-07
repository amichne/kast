package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** A fitted policy rejection may publish its exact retained evidence without changing its semantic verdict. */
sealed interface HostedReadRejectedPublication {
    data object Discard : HostedReadRejectedPublication

    data class RetainedEvidence(val evidence: QueryCompletionEvidenceDocument.Retained) : HostedReadRejectedPublication
}

/** One attempt's detached store publication, committed only after freshness, deadline and permit completion. */
interface HostedReadPublicationEffect {
    val rejectedPublication: HostedReadRejectedPublication
        get() = HostedReadRejectedPublication.Discard

    fun commit(): Refinement<Unit, HostedQueryFailure>

    fun discard()
}

internal class HostedReadPublicationOwner(
    private val observation: () -> IntellijReadObservation = { IntellijReadObservation.None }
) {
    private sealed interface State {
        data object Empty : State

        data class Prepared(val effect: HostedReadPublicationEffect) : State

        data object Ended : State
    }

    private var state: State = State.Empty

    @Synchronized
    fun prepare(effect: HostedReadPublicationEffect): Refinement<Unit, HostedQueryFailure> =
        when (state) {
            State.Empty -> {
                state = State.Prepared(effect)
                Refinement.Refined(Unit)
            }
            is State.Prepared,
            State.Ended -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }

    @Synchronized
    fun commit(): Refinement<Unit, HostedQueryFailure> {
        val current = state
        state = State.Ended
        return when (current) {
            State.Empty -> Refinement.Refined(Unit)
            is State.Prepared -> {
                val capability = current.effect.rejectedPublication
                current.effect.commit().also {
                    when (it) {
                        is Refinement.Refined ->
                            observe(capability, IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED)
                        is Refinement.Rejected -> {
                            observe(capability, IntellijReadCounter.QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS)
                            current.effect.discard()
                            observe(capability, IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED)
                        }
                    }
                }
            }
            State.Ended -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }
    }

    /** Semantic rejection alone never authorizes storage; the fitted owner must retain exact policy evidence. */
    @Synchronized
    fun commitRetainedRejection(): Refinement<Unit, HostedQueryFailure> =
        when (val current = state) {
            is State.Prepared ->
                when (current.effect.rejectedPublication) {
                    is HostedReadRejectedPublication.RetainedEvidence -> commit()
                    HostedReadRejectedPublication.Discard -> {
                        discard()
                        Refinement.Refined(Unit)
                    }
                }
            State.Empty -> {
                discard()
                Refinement.Refined(Unit)
            }
            State.Ended -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }

    @Synchronized
    fun discard() {
        val current = state
        state = State.Ended
        if (current is State.Prepared) {
            val capability = current.effect.rejectedPublication
            current.effect.discard()
            observe(capability, IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED)
        }
    }

    @Synchronized
    fun restart() {
        discard()
        state = State.Empty
    }

    private fun observe(capability: HostedReadRejectedPublication, counter: IntellijReadCounter) {
        if (capability is HostedReadRejectedPublication.RetainedEvidence) observation().count(counter)
    }
}
