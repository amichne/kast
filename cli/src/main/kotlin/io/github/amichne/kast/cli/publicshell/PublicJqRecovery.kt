package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadRequest
import io.github.amichne.kast.protocol.wire.presentation.toCompletionPublicOutput
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Narrow metadata projection: retain issued authority and output selection, never source or witness payloads. */
internal fun retainedJqRecovery(document: JsonObject): QueryCompletionPublicReadQuery? =
    try {
        recoveryFromFields(recoveryJson.decodeFromJsonElement<JqRecoveryFields>(document))
    } catch (_: SerializationException) {
        null
    }

private fun recoveryFromFields(fields: JqRecoveryFields): QueryCompletionPublicReadQuery? {
    if (fields.nextQuery != null) return fields.nextQuery
    val output = fields.question?.output ?: return null
    return when (val retention = fields.retention) {
        is QueryResultRetention.Retained ->
            QueryCompletionPublicReadQuery(
                QueryCompletionPublicReadRequest.rows(retention.reference, output.toCompletionPublicOutput())
            )
        QueryResultRetention.NotRequested -> null
    }
}

@Serializable
private data class JqRecoveryFields(
    val retention: QueryResultRetention = QueryResultRetention.NotRequested,
    val question: JqRecoveryQuestion? = null,
    val nextQuery: QueryCompletionPublicReadQuery? = null,
)

@Serializable private data class JqRecoveryQuestion(val output: QueryOutputDocument)

private val recoveryJson = Json { ignoreUnknownKeys = true }
