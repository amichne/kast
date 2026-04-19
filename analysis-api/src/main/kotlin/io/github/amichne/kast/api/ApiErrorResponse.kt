@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
    @DocField(description = "Unique identifier of the failed request for correlation.")
    val requestId: String,
    @DocField(description = "Machine-readable error code identifying the failure type.")
    val code: String,
    @DocField(description = "Human-readable error message describing the failure.")
    val message: String,
    @DocField(description = "True when retrying the same request may succeed.")
    val retryable: Boolean,
    @DocField(description = "Additional key-value metadata about the error.")
    val details: Map<String, String> = emptyMap(),
)
