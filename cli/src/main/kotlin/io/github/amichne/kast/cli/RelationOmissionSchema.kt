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
            enumSchema(RelationProviderDocument.entries.map { it.name }, "Semantic provider and contract version."),
        ),
        ServerSchemaProperty(
            "reason",
            enumSchema(RelationLimitationDocument.entries.map { it.name }, "Retained finite limitation."),
        ),
        ServerSchemaProperty(
            "measurement",
            omissionMeasurementSchema(),
        ),
        ServerSchemaProperty("samples", omissionSamplesSchema()),
        ServerSchemaProperty(
            "remediation",
            enumSchema(RelationRemediationDocument.entries.map { it.name }, "Closed suggested next action."),
        ),
    )

/** Schema items are a deliberately dynamic schema boundary; the array assertions remain typed. */
@Serializable
private data class BoundedOmissionSamplesSchema(
    val items: JsonObject,
    val type: String,
    val minItems: Int,
    val maxItems: Int,
    val uniqueItems: Boolean,
)

private const val MAXIMUM_OMISSION_SAMPLES = 3

private fun boundedSampleSchema(item: JsonObject, minimum: Int): JsonObject =
    Json.encodeToJsonElement(
            BoundedOmissionSamplesSchema.serializer(),
            BoundedOmissionSamplesSchema(item, "array", minimum, MAXIMUM_OMISSION_SAMPLES, true),
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

private fun omissionSamplesSchema(): JsonObject =
    unionSchema(
        omissionSampleVariant(
            "complete",
            0,
            "All distinct located samples observed so far are retained; unlocated omissions remain possible.",
        ),
        omissionSampleVariant(
            "truncated",
            MAXIMUM_OMISSION_SAMPLES,
            "At least one further distinct located sample was observed and discarded.",
        ),
    )

private fun omissionSampleVariant(type: String, minimum: Int, meaning: String): JsonObject =
    objectSchema(
        ServerSchemaProperty("type", constantSchema(type, meaning)),
        ServerSchemaProperty("locations", boundedSampleSchema(omissionLocationSchema(), minimum)),
    )

private fun omissionLocationSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty(
            "range",
            objectSchema(
                ServerSchemaProperty("startInclusive", integerSchema(0, description = "Observed occurrence start.")),
                ServerSchemaProperty("endExclusive", integerSchema(0, description = "Observed occurrence end.")),
            ),
        ),
    )
