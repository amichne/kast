package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.appserver.protocol.codex.RpcId
import io.github.amichne.kast.appserver.protocol.codex.UpstreamEnvelope
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@JvmInline
internal value class SessionRequestKey private constructor(val value: String) {
    companion object {
        fun admit(id: JsonElement): Refinement<SessionRequestKey, SessionRequestFailure> =
            RpcId.admit(id)?.let { Refinement.Refined(SessionRequestKey(it.key)) }
                ?: Refinement.Rejected(SessionRequestFailure.REQUEST_ID_REJECTED)
    }
}

internal sealed interface SessionRequestAdmission {
    data object NoReply : SessionRequestAdmission

    data class AwaitingReply(val key: SessionRequestKey) : SessionRequestAdmission
}

internal enum class SessionRequestFailure {
    REQUEST_ID_REJECTED,
    REQUEST_ID_CONFLICT,
    REQUEST_CAPACITY_EXCEEDED,
    SESSION_RETIRED,
}

internal enum class SessionRetirement {
    ALREADY_RETIRED,
    IDLE,
    UNCERTAIN,
}

/** Registration and retirement are atomic even when transport cleanup runs outside session ingress. */
internal class SessionRequests {
    private enum class Lifetime {
        OPEN,
        RETIRED,
    }

    private var lifetime = Lifetime.OPEN
    private val pending = mutableSetOf<SessionRequestKey>()

    @Synchronized
    fun admit(id: JsonElement?): Refinement<SessionRequestAdmission, SessionRequestFailure> {
        if (lifetime == Lifetime.RETIRED) return Refinement.Rejected(SessionRequestFailure.SESSION_RETIRED)
        if (id == null) return Refinement.Refined(SessionRequestAdmission.NoReply)
        val key =
            when (val admitted = SessionRequestKey.admit(id)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        if (key in pending) return Refinement.Rejected(SessionRequestFailure.REQUEST_ID_CONFLICT)
        if (pending.size >= BrokerOperationalLimits.sessionChannelCapacity)
            return Refinement.Rejected(SessionRequestFailure.REQUEST_CAPACITY_EXCEEDED)
        pending.add(key)
        return Refinement.Refined(SessionRequestAdmission.AwaitingReply(key))
    }

    @Synchronized
    fun resolve(id: JsonElement?) {
        if (lifetime == Lifetime.RETIRED || id == null) return
        when (val admitted = SessionRequestKey.admit(id)) {
            is Refinement.Rejected -> Unit
            is Refinement.Refined -> pending.remove(admitted.value)
        }
    }

    fun resolveReply(document: JsonObject, routing: ProtocolRouting) {
        val response = UpstreamEnvelope.classify(document)
        if (routing !is ProtocolRouting.Close && response is UpstreamEnvelope.Response) resolve(response.id.value)
    }

    @Synchronized
    fun abandon(admission: SessionRequestAdmission) {
        if (lifetime == Lifetime.RETIRED) return
        when (admission) {
            SessionRequestAdmission.NoReply -> Unit
            is SessionRequestAdmission.AwaitingReply -> pending.remove(admission.key)
        }
    }

    @Synchronized
    fun retire(): SessionRetirement {
        if (lifetime == Lifetime.RETIRED) return SessionRetirement.ALREADY_RETIRED
        lifetime = Lifetime.RETIRED
        return if (pending.isEmpty()) SessionRetirement.IDLE else SessionRetirement.UNCERTAIN
    }

    @Synchronized
    fun upgradeBlockers(): Set<UpgradeBlocker> =
        when {
            pending.isEmpty() -> emptySet()
            lifetime == Lifetime.RETIRED -> setOf(UpgradeBlocker.RECONCILIATION_REQUIRED)
            else -> setOf(UpgradeBlocker.REQUEST_PENDING)
        }
}
