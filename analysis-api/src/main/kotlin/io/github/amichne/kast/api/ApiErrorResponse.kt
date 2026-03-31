package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val schemaVersion: Int = SCHEMA_VERSION,
    val requestId: String,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, String> = emptyMap(),
)
