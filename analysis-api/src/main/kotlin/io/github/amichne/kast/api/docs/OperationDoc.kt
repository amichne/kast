package io.github.amichne.kast.api.docs

/**
 * Per-operation editorial metadata used by [DocsDocument] to enrich
 * generated documentation beyond what the schema models carry.
 *
 * Each entry corresponds to one JSON-RPC method dispatched by the analysis
 * daemon. The [operationId] matches the value used in
 * [OpenApiDocument.writePaths].
 */
data class OperationDoc(
    // Structural metadata (mirrors OpenApiDocument.writePaths)
    val operationId: String,
    val jsonRpcMethod: String,
    val summary: String,
    val tag: String,
    val capability: String? = null,
    val requestSchema: String? = null,
    val responseSchema: String,

    // Editorial metadata
    val description: String,
    val behavioralNotes: List<String> = emptyList(),
    val exampleFixtureId: String? = null,
    val errorCodes: List<String> = emptyList(),
)
