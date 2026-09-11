package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class NativeOutputField(val wire: String) {
    OPERATION("operation"),
    STATUS("status"),
    LIVE("live"),
    PLAN_IDENTITY("planIdentity"),
    RECEIPT_IDENTITY("receiptIdentity"),
    CHANGES("changes"),
    STATE("state"),
    REASON("reason"),
    QUALIFICATION("qualification"),
    FAILURE("failure"),
    OUTCOME("outcome"),
    TYPE("type"),
    ITEMS("items"),
    FAILURES("failures"),
    VERSION("version"),
    UNKNOWN("");

    companion object {
        fun observe(name: String): NativeOutputField = entries.firstOrNull { it.wire == name } ?: UNKNOWN
    }
}

/** A structural observation never contains field values, executable references, diffs, or process messages. */
internal fun processObservation(result: BrokerProcessExecution): JsonObject =
    when (result) {
        is BrokerProcessExecution.Rejected ->
            buildJsonObject {
                put("outcome", "process-rejected")
                put("failure", result.failure.name)
            }
        is BrokerProcessExecution.Completed -> completedObservation(result)
    }

private fun completedObservation(result: BrokerProcessExecution.Completed): JsonObject = buildJsonObject {
    put("outcome", if (result.exitCode == 0) "zero-exit" else "nonzero-exit")
    val document = processDocument(if (result.exitCode == 0) result.stdout else result.stderr)
    put("shape", if (document == null) "non-object" else "object")
    put("fields", observedFields(document))
}

private fun processDocument(raw: String): JsonObject? =
    try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun observedFields(document: JsonObject?) = buildJsonArray {
    document
        ?.keys
        ?.map(NativeOutputField::observe)
        ?.distinct()
        ?.sortedBy { it.name }
        ?.forEach { field ->
            add(JsonPrimitive(field.name))
        }
}
