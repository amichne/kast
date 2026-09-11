package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class NativeProtocolFailure {
    OUTPUT_CONTRACT_REJECTED,
    INVALID_ARGUMENTS,
    UNKNOWN_TOOL,
    CATALOG_INCOMPATIBLE,
    NONE,
    UNCLASSIFIED,
}

/** Request/response contents stay in memory. Durable diagnostics retain only digests and closed failure identities. */
internal fun protocolObservation(raw: String): JsonObject = buildJsonObject {
    put("sha256", sha256(raw.toByteArray()))
    val document = Json.parseToJsonElement(raw) as? JsonObject
    val result = document?.get("result") as? JsonObject
    val body =
        (result?.get("contentItems") as? JsonArray)
            .orEmpty()
            .mapNotNull { item ->
                val content = ((item as? JsonObject)?.get("text") as? JsonPrimitive)?.content
                content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            }
            .singleOrNull { it.containsKey("document") || it.containsKey("failure") }
    val failure = (body?.get("failure") as? JsonPrimitive)?.content
    put("failure", protocolFailure(failure))
    val payload = body?.get("document") as? JsonObject
    val reason = (payload?.get("reason") as? JsonPrimitive)?.content
    val knownReason =
        (ChangePlanRejection.entries + ChangeApplyRejection.entries + ChangeRecoverRejection.entries).firstOrNull {
            it.name.lowercase() == reason
        }
    put("canonicalRejection", knownReason?.name ?: if (reason == null) "NONE" else "UNCLASSIFIED")
}

private fun protocolFailure(raw: String?): String {
    if (raw == null) return NativeProtocolFailure.NONE.name
    ProviderFailureCode.entries
        .firstOrNull { it.name == raw }
        ?.let {
            return it.name
        }
    HostedPlanApprovalFailure.entries
        .firstOrNull { "PLAN_APPROVAL_${it.name}" == raw }
        ?.let {
            return "PLAN_APPROVAL_${it.name}"
        }
    return NativeProtocolFailure.entries.firstOrNull { it.name == raw }?.name ?: NativeProtocolFailure.UNCLASSIFIED.name
}
