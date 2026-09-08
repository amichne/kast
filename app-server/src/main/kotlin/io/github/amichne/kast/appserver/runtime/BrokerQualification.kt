package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerServiceReadiness
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolQualification
import kotlinx.serialization.json.*

/** Constructed only from successful installed qualification, never from socket reachability. */
internal class BrokerQualification(
    private val service: BrokerServiceReadiness,
    private val protocol: CodexProtocolQualification.Qualified,
    private val catalog: String,
) {
    fun document(): JsonObject = buildJsonObject {
        put("serviceIdentity", (service as? BrokerServiceReadiness.Managed)?.identity?.value?.let(::JsonPrimitive) ?: JsonNull)
        put("generation", (service as? BrokerServiceReadiness.Managed)?.instanceId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("codexVersion", protocol.version.value)
        put("schemaDigest", protocol.protocolDigest.value)
        put("catalogDigest", catalog)
    }
}
