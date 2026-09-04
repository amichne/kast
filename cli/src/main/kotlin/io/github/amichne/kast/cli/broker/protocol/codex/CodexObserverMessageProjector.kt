package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Constructs the only synthetic assistant item owned by the broker: commentary. */
internal object CodexObserverMessageProjector {
    internal fun projectCompleted(
        admittedLifecycleParams: JsonObject,
        callId: BrokerCallId,
        presentation: ObserverPresentation.Markdown,
    ): JsonObject {
        val item = Json.encodeToJsonElement(
            CodexObserverAgentMessage.serializer(),
            CodexObserverAgentMessage(
                type = ObserverItemType.AGENT_MESSAGE,
                id = ObserverItemId.derive(callId).value,
                text = presentation.source.value,
                phase = ObserverAgentPhase.COMMENTARY,
            ),
        ).jsonObject
        return JsonObject(admittedLifecycleParams + ("item" to item))
    }

    @Serializable
    private data class CodexObserverAgentMessage(
        val type: ObserverItemType,
        val id: String,
        val text: String,
        val phase: ObserverAgentPhase,
    )

    @Serializable
    private enum class ObserverItemType {
        @SerialName("agentMessage")
        AGENT_MESSAGE,
    }

    @Serializable
    private enum class ObserverAgentPhase {
        @SerialName("commentary")
        COMMENTARY,
    }

    @JvmInline
    private value class ObserverItemId private constructor(val value: String) {
        companion object {
            fun derive(callId: BrokerCallId): ObserverItemId {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(callId.value.toByteArray(StandardCharsets.UTF_8))
                return ObserverItemId("kast-observer-${HexFormat.of().formatHex(digest)}")
            }
        }
    }
}
