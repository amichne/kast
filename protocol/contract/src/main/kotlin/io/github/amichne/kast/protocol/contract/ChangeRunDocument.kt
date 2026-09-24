@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/** One caller-visible mutation result. Native phase documents stay opaque and retain their owning contracts. */
@Serializable
@JsonClassDiscriminator("status")
sealed interface ChangeRunDocument {
    @Serializable
    @SerialName("complete")
    data class Complete(
        val planIdentity: String,
        val plan: JsonObject,
        val application: JsonObject,
    ) : ChangeRunDocument

    @Serializable @SerialName("rejected") data class Rejected(val error: ChangeRunError) : ChangeRunDocument
}

@Serializable
data class ChangeRunError(
    val code: ChangeRejection,
    val planIdentity: String? = null,
    val plan: JsonObject? = null,
    val application: JsonObject? = null,
    val recovery: JsonObject? = null,
)
