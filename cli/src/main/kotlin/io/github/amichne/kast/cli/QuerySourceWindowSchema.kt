package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import kotlinx.serialization.json.JsonObject

internal fun querySourceWindowSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("text", sourceTextSchema()),
        ServerSchemaProperty("startLine", integerSchema(1, description = "One-based first source line.")),
        ServerSchemaProperty("endLine", integerSchema(1, description = "One-based last source line.")),
    )

internal fun querySourceFailureSchema(): JsonObject =
    enumSchema(
        QuerySourceFailureDocument.entries.map { it.name.lowercase().replace('_', '-') },
        "Closed source-window failure.",
    )
