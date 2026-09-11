package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal enum class WorkerSeedMessageKind {
    SEED_CONSENT_REQUEST,
    SEED_CONSENT_REPLY,
}

@Serializable
internal data class WorkerSeedPromptDocument(
    val kind: WorkerSeedMessageKind,
    val challenge: String,
    val categories: List<WorkerSeedCategory>,
    val estimatedBytes: Long,
)

@Serializable
internal data class WorkerSeedReplyDocument(
    val kind: WorkerSeedMessageKind,
    val challenge: String,
    val consent: WorkerSeedConsent,
)

internal class WorkerSeedPrompt private constructor(val challenge: UUID, val disclosure: WorkerSeedDisclosure) {
    fun encode(): String =
        Json.encodeToString(
            WorkerSeedPromptDocument(
                WorkerSeedMessageKind.SEED_CONSENT_REQUEST,
                challenge.toString(),
                disclosure.categories.sortedBy { it.name },
                disclosure.estimatedBytes,
            )
        )

    fun reply(consent: WorkerSeedConsent): String =
        Json.encodeToString(
            WorkerSeedReplyDocument(WorkerSeedMessageKind.SEED_CONSENT_REPLY, challenge.toString(), consent)
        )

    fun decision(raw: String): WorkerSeedConsent =
        try {
            val reply = Json.decodeFromString<WorkerSeedReplyDocument>(raw)
            if (reply.kind == WorkerSeedMessageKind.SEED_CONSENT_REPLY && reply.challenge == challenge.toString())
                reply.consent
            else WorkerSeedConsent.ABSENT
        } catch (_: Exception) {
            WorkerSeedConsent.ABSENT
        }

    companion object {
        fun create(disclosure: WorkerSeedDisclosure) = WorkerSeedPrompt(UUID.randomUUID(), disclosure)

        fun isPrompt(raw: String): Boolean =
            try {
                Json.parseToJsonElement(raw).jsonObject["kind"] ==
                    JsonPrimitive(WorkerSeedMessageKind.SEED_CONSENT_REQUEST.name)
            } catch (_: Exception) {
                false
            }

        fun decode(raw: String): Refinement<WorkerSeedPrompt, WorkerControlFailure> =
            try {
                val value = Json.decodeFromString<WorkerSeedPromptDocument>(raw)
                val challenge = UUID.fromString(value.challenge)
                if (
                    value.kind != WorkerSeedMessageKind.SEED_CONSENT_REQUEST ||
                        challenge.toString() != value.challenge ||
                        value.categories.distinct().size != value.categories.size
                )
                    Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST)
                else
                    when (val disclosure = WorkerSeedDisclosure.admit(value.categories.toSet(), value.estimatedBytes)) {
                        is Refinement.Refined -> Refinement.Refined(WorkerSeedPrompt(challenge, disclosure.value))
                        is Refinement.Rejected -> disclosure
                    }
            } catch (_: Exception) {
                Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST)
            }
    }
}

/** A single, non-transferable consent opportunity on the requesting connection. */
internal class WorkerSeedConsentExchange(private val session: DefaultWebSocketServerSession) :
    WorkerSeedConsentAuthority {
    private val requested = AtomicBoolean(false)

    override suspend fun request(disclosure: WorkerSeedDisclosure): WorkerSeedConsent {
        if (!requested.compareAndSet(false, true)) return WorkerSeedConsent.ABSENT
        val prompt = WorkerSeedPrompt.create(disclosure)
        return try {
            withTimeoutOrNull(BrokerOperationalLimits.seedConsent.value) {
                session.send(prompt.encode())
                val frame =
                    session.incoming.receiveCatching().getOrNull() as? Frame.Text
                        ?: return@withTimeoutOrNull WorkerSeedConsent.ABSENT
                val raw = frame.readText()
                if (raw.toByteArray().size > BrokerOperationalLimits.maximumSeedConsentBytes) WorkerSeedConsent.ABSENT
                else prompt.decision(raw)
            } ?: WorkerSeedConsent.ABSENT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WorkerSeedConsent.ABSENT
        }
    }
}
