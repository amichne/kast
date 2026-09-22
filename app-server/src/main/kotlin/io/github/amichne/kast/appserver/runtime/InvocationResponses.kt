package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal sealed interface InvocationResponseAdmission {
    data class Started(val invocation: AdmittedInvocation) : InvocationResponseAdmission

    data class Existing(val result: Deferred<ProtocolRouting>) : InvocationResponseAdmission

    data class Rejected(val failure: InvocationFenceFailure) : InvocationResponseAdmission
}

/** Only the owner returned by durable admission can settle or publish this invocation's response. */
internal interface AdmittedInvocation {
    val result: Deferred<ProtocolRouting>

    fun settle(routing: ProtocolRouting): ProtocolRouting

    fun complete(routing: ProtocolRouting): ProtocolRouting
}

/** Active responses cannot be evicted. Completed cache eviction never deletes durable replay evidence. */
internal class InvocationResponses(
    private val fence: InvocationFence,
    private val maximumCompleted: InvocationCapacityLimit = InvocationCapacityLimit.Default,
) {
    private val active = mutableMapOf<InvocationIdentity, OwnedInvocation>()
    private val completed = linkedMapOf<InvocationIdentity, OwnedInvocation>()

    @Synchronized
    fun begin(
        identity: InvocationIdentity,
        fingerprint: InvocationFingerprint,
        reject: (InvocationFenceFailure) -> ProtocolRouting.ReplyUpstream,
        settled: (InvocationCertainty) -> Unit,
    ): InvocationResponseAdmission {
        val existing = active[identity] ?: completed[identity]
        if (existing != null)
            return if (existing.fingerprint == fingerprint) InvocationResponseAdmission.Existing(existing.result)
            else InvocationResponseAdmission.Rejected(InvocationFenceFailure.INPUT_CONFLICT)
        return when (val admission = fence.admit(identity.persistenceKey(), fingerprint.value)) {
            is InvocationAdmission.Rejected -> InvocationResponseAdmission.Rejected(admission.failure)
            InvocationAdmission.Admitted -> {
                val invocation = OwnedInvocation(identity, fingerprint, reject, settled)
                active[identity] = invocation
                InvocationResponseAdmission.Started(invocation)
            }
        }
    }

    private sealed interface ResponsePhase {
        data object Open : ResponsePhase

        data class Settled(val routing: ProtocolRouting) : ResponsePhase

        data class Completed(val routing: ProtocolRouting) : ResponsePhase
    }

    private inner class OwnedInvocation(
        private val identity: InvocationIdentity,
        val fingerprint: InvocationFingerprint,
        private val reject: (InvocationFenceFailure) -> ProtocolRouting.ReplyUpstream,
        private val publishSettlement: (InvocationCertainty) -> Unit,
    ) : AdmittedInvocation {
        private val response = CompletableDeferred<ProtocolRouting>()
        override val result: Deferred<ProtocolRouting> = response
        private var phase: ResponsePhase = ResponsePhase.Open

        override fun settle(routing: ProtocolRouting): ProtocolRouting =
            synchronized(this@InvocationResponses) {
                when (val current = phase) {
                    is ResponsePhase.Completed -> current.routing
                    is ResponsePhase.Settled ->
                        if (
                            current.routing.certainty() == InvocationCertainty.UNCERTAIN &&
                                routing.certainty() == InvocationCertainty.KNOWN
                        )
                            current.routing
                        else routing
                    ResponsePhase.Open -> settleOpen(routing)
                }
            }

        private fun settleOpen(routing: ProtocolRouting): ProtocolRouting {
            val requested =
                if (routing is ProtocolRouting.ReplyUpstream && routing.certainty == InvocationCertainty.KNOWN)
                    InvocationSettlement.COMPLETED
                else InvocationSettlement.UNCERTAIN
            val admission = fence.finish(identity.persistenceKey(), requested)
            val certainty =
                if (admission is InvocationAdmission.Rejected || requested == InvocationSettlement.UNCERTAIN)
                    InvocationCertainty.UNCERTAIN
                else InvocationCertainty.KNOWN
            val result =
                if (admission is InvocationAdmission.Rejected) reject(InvocationFenceFailure.OUTCOME_UNCERTAIN)
                else routing
            phase = ResponsePhase.Settled(result)
            publishSettlement(certainty)
            return result
        }

        override fun complete(routing: ProtocolRouting): ProtocolRouting =
            synchronized(this@InvocationResponses) {
                when (val current = phase) {
                    is ResponsePhase.Completed -> current.routing
                    else -> {
                        val settled = settle(routing)
                        phase = ResponsePhase.Completed(settled)
                        active.remove(identity)
                        completed[identity] = this
                        while (completed.size > maximumCompleted.value) completed.remove(completed.keys.first())
                        response.complete(settled)
                        settled
                    }
                }
            }
    }
}

private fun ProtocolRouting.certainty(): InvocationCertainty =
    if (this is ProtocolRouting.ReplyUpstream) certainty else InvocationCertainty.UNCERTAIN
