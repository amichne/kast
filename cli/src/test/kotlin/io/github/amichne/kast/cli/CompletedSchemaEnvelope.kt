package io.github.amichne.kast.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The provider envelope owns a contract-defined dynamic canonical CLI document. */
internal fun completedSchemaEnvelope(document: JsonObject): String =
    Json.encodeToString(CompletedProviderEnvelope(ProviderStatus.COMPLETED, document))

@Serializable private data class CompletedProviderEnvelope(val status: ProviderStatus, val document: JsonObject)

@Serializable
private enum class ProviderStatus {
    @SerialName("completed") COMPLETED
}
