package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class WirePeerQualification { QUALIFIED, REJECTED }

/** Transport-only exchange binds a connected stream before any semantic document is sent. */
object WireRuntimeQualification {
    const val maximumBytes: Int = 16_384
    fun request(identity: WireRuntimeIdentity): String = document(identity, "request").toString()
    fun response(identity: WireRuntimeIdentity): String = document(identity, "qualified").toString()
    fun admitRequest(raw: String, expected: WireRuntimeIdentity): WirePeerQualification =
        admit(raw, document(expected, "request"))
    fun admitResponse(raw: String, expected: WireRuntimeIdentity): WirePeerQualification =
        admit(raw, document(expected, "qualified"))

    private fun admit(raw: String, expected: JsonObject): WirePeerQualification = try {
        if (raw.length <= maximumBytes && raw.toByteArray(Charsets.UTF_8).size <= maximumBytes && Json.parseToJsonElement(raw) == expected) WirePeerQualification.QUALIFIED
        else WirePeerQualification.REJECTED
    } catch (_: IllegalArgumentException) { WirePeerQualification.REJECTED }

    private fun document(identity: WireRuntimeIdentity, phase: String): JsonObject = buildJsonObject {
        put("transport", "kast.runtime.qualification.v1")
        put("phase", phase)
        put("root", identity.root.toString())
        put("runtimeId", identity.runtimeId)
        put("bootstrapAttempt", identity.bootstrapAttempt.value)
    }
}
