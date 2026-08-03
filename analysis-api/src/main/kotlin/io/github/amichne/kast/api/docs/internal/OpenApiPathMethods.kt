package io.github.amichne.kast.api.docs.internal

internal fun systemMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String? = null,
    responseSchema: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("system"),
        "x-jsonrpc-method" to method,
    ).also { operation ->
        if (requestSchema != null) {
            operation["requestBody"] = linkedMapOf(
                "required" to true,
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(requestSchema),
                    ),
                ),
            )
        }
        operation["responses"] = linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        )
    },
)

internal fun readMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
    capability: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("read"),
        "x-jsonrpc-method" to method,
        "x-kast-required-capability" to capability,
        "requestBody" to linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        ),
        "responses" to linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        ),
    ),
)

internal fun internalReadMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("read"),
        "x-jsonrpc-method" to method,
        "requestBody" to linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        ),
        "responses" to linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        ),
    ),
)

internal fun mutationMethod(
    operationId: String,
    summary: String,
    method: String,
    requestSchema: String,
    responseSchema: String,
    capability: String,
    extraExtensions: Map<String, String> = emptyMap(),
): Map<String, Any?> = linkedMapOf(
    "post" to linkedMapOf(
        "operationId" to operationId,
        "summary" to summary,
        "tags" to listOf("mutation"),
        "x-jsonrpc-method" to method,
        "x-kast-required-capability" to capability,
    ).also { op ->
        extraExtensions.forEach { (k, v) -> op[k] = v }
        op["requestBody"] = linkedMapOf(
            "required" to true,
            "content" to linkedMapOf(
                "application/json" to linkedMapOf(
                    "schema" to ref(requestSchema),
                ),
            ),
        )
        op["responses"] = linkedMapOf(
            "200" to linkedMapOf(
                "description" to "JSON-RPC success result",
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(responseSchema),
                    ),
                ),
            ),
            "default" to errorResponse(),
        )
    },
)

private fun errorResponse(): Map<String, Any?> = linkedMapOf(
    "description" to "JSON-RPC error response",
    "content" to linkedMapOf(
        "application/json" to linkedMapOf(
            "schema" to ref("JsonRpcErrorResponse"),
        ),
    ),
)

private fun ref(name: String): Map<String, Any?> = linkedMapOf("\$ref" to "#/components/schemas/$name")
