package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.CompactSourceEntityDocument
import io.github.amichne.kast.protocol.wire.CompactSourceRegionDocument
import io.github.amichne.kast.protocol.wire.CompactSourceSelectionEntry
import io.github.amichne.kast.protocol.wire.CompactSourceSnapshotDocument
import io.github.amichne.kast.protocol.wire.CompactSourceTextDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun sourceReadOutputSchema(operation: CanonicalOperation): JsonObject =
    unionSchema(
        proofQualifiedOutcomeSchema(
            operation,
            sourceReadQualificationSchema(),
            executionBudgetProperty(),
            ServerSchemaProperty("snapshot", sourceSnapshotSchema()),
            ServerSchemaProperty("region", sourceRegionSchema()),
            ServerSchemaProperty("entities", arraySchema(sourceEntitySchema())),
            ServerSchemaProperty("text", sourceTextProjectionSchema()),
        ),
        proofQualifiedOutcomeSchema(
            operation,
            sourceReadQualificationSchema(),
            executionBudgetProperty(),
            ServerSchemaProperty("format", constantSchema("compact", "Source projection format.")),
            ServerSchemaProperty("content", compactSourceContentSchema()),
        ),
    )

private fun compactSourceContentSchema(): JsonObject =
    schemaJson
        .encodeToJsonElement(
            SourceContentTupleSchema.serializer(),
            SourceContentTupleSchema(
                prefixItems =
                    listOf(
                        objectSchema(
                            ServerSchemaProperty("type", constantSchema("source", "Primary source presentation.")),
                            ServerSchemaProperty(
                                "text",
                                generatedRequestSchema(CompactSourceTextDocument.serializer()),
                            ),
                        ),
                        objectSchema(
                            ServerSchemaProperty(
                                "type",
                                constantSchema("structure", "Structural detail after source."),
                            ),
                            ServerSchemaProperty(
                                "snapshot",
                                generatedRequestSchema(CompactSourceSnapshotDocument.serializer()),
                            ),
                            ServerSchemaProperty(
                                "region",
                                generatedRequestSchema(CompactSourceRegionDocument.serializer()),
                            ),
                            ServerSchemaProperty("selections", compactSourceSelectionsSchema()),
                            ServerSchemaProperty(
                                "entities",
                                arraySchema(generatedRequestSchema(CompactSourceEntityDocument.serializer())),
                            ),
                        ),
                    )
            ),
        )
        .jsonObject

private fun compactSourceSelectionsSchema(): JsonObject =
    schemaJson
        .encodeToJsonElement(
            SourceSelectionTableSchema.serializer(),
            SourceSelectionTableSchema(items = generatedRequestSchema(CompactSourceSelectionEntry.serializer())),
        )
        .jsonObject

private val schemaJson = Json { encodeDefaults = true }

/** JSON Schema subschemas are intentionally dynamic; tuple order and cardinality are fixed here. */
@Serializable
private data class SourceContentTupleSchema(
    val type: String = "array",
    val minItems: Int = 2,
    val maxItems: Int = 2,
    val items: Boolean = false,
    val prefixItems: List<JsonObject>,
)

/** Item schema remains generated from the typed selector entry; collection invariants are fixed. */
@Serializable
private data class SourceSelectionTableSchema(
    val type: String = "array",
    val minItems: Int = 1,
    val maxItems: Int = MAXIMUM_SOURCE_SELECTIONS,
    val uniqueItems: Boolean = true,
    val items: JsonObject,
)

// Each call can retain parent, selection, callee and local target, plus region and text for the page.
private const val MAXIMUM_SOURCE_SELECTIONS = MAXIMUM_PROTOCOL_COUNT * 4 + 2
