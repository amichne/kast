package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun relationOmissionSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty(
            "provider",
            enumSchema(RelationProviderDocument.entries.map { it.name }, "Native provider and contract version."),
        ),
        ServerSchemaProperty(
            "reason",
            enumSchema(RelationLimitationDocument.entries.map { it.name }, "Retained finite limitation."),
        ),
        ServerSchemaProperty(
            "measurement",
            omissionMeasurementSchema(),
        ),
        ServerSchemaProperty(
            "samples",
            boundedSampleSchema(
                objectSchema(
                    ServerSchemaProperty("file", workspaceFileSchema()),
                    ServerSchemaProperty(
                        "range",
                        objectSchema(
                            ServerSchemaProperty(
                                "startInclusive",
                                integerSchema(0, description = "Observed occurrence start."),
                            ),
                            ServerSchemaProperty(
                                "endExclusive",
                                integerSchema(0, description = "Observed occurrence end."),
                            ),
                        ),
                    ),
                )
            ),
        ),
        ServerSchemaProperty(
            "remediation",
            enumSchema(RelationRemediationDocument.entries.map { it.name }, "Closed suggested next action."),
        ),
    )

/** Schema items are a deliberately dynamic schema boundary; the array assertions remain typed. */
@Serializable
private data class BoundedOmissionSamplesSchema(val items: JsonObject, val type: String, val maxItems: Int)

private const val MAXIMUM_OMISSION_SAMPLES = 3

private fun boundedSampleSchema(item: JsonObject): JsonObject =
    Json.encodeToJsonElement(
            BoundedOmissionSamplesSchema.serializer(),
            BoundedOmissionSamplesSchema(item, "array", MAXIMUM_OMISSION_SAMPLES),
        )
        .jsonObject

private fun omissionMeasurementSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("observed_on_page", "Only omitted inputs consumed on this page are counted."),
            ),
            ServerSchemaProperty(
                "items",
                integerSchema(
                    0,
                    description = "Observed omitted input count; never an estimate of all missing facts.",
                ),
            ),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("unmeasured_on_page", "This page did not measure the missing population."),
            )
        ),
    )
