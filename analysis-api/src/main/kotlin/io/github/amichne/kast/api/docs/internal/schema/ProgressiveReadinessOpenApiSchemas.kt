package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.CurrentCapabilityLaneReadiness
import io.github.amichne.kast.api.contract.RetainedCapabilityLaneFallback
import io.github.amichne.kast.api.contract.RetainedCapabilityLaneReadiness
import io.github.amichne.kast.api.contract.RetainedWorkspaceGenerationStatus

internal fun SchemaRegistry.manualProgressiveReadinessSchema(componentName: String): Map<String, Any?>? =
    when (componentName) {
        "CurrentCapabilityLaneEvidence" -> capabilityLaneEvidenceSchema("CurrentCapabilityLaneFreshness")
        "RetainedCapabilityLaneEvidence" -> capabilityLaneEvidenceSchema("RetainedCapabilityLaneFreshness")
        "PreviousCapabilityLaneEvidence" -> capabilityLaneEvidenceSchema("PreviousCapabilityLaneFreshness")
        "CurrentCapabilityLaneReadiness" -> discriminatedUnion(
            "type",
            "AVAILABLE" to "CurrentCapabilityLaneReadiness.Available",
            "BUILDING" to "CurrentCapabilityLaneReadiness.Building",
            "BLOCKED" to "CurrentCapabilityLaneReadiness.Blocked",
        )
        "CurrentCapabilityLaneReadiness.Available" -> subtypeWithDiscriminator(
            CurrentCapabilityLaneReadiness.Available.serializer(),
            discriminatorValue = "AVAILABLE",
        )
        "CurrentCapabilityLaneReadiness.Building" -> subtypeWithDiscriminator(
            CurrentCapabilityLaneReadiness.Building.serializer(),
            discriminatorValue = "BUILDING",
        )
        "CurrentCapabilityLaneReadiness.Blocked" -> subtypeWithDiscriminator(
            CurrentCapabilityLaneReadiness.Blocked.serializer(),
            discriminatorValue = "BLOCKED",
        )
        "RetainedCapabilityLaneReadiness" -> discriminatedUnion(
            "type",
            "AVAILABLE" to "RetainedCapabilityLaneReadiness.Available",
            "BUILDING" to "RetainedCapabilityLaneReadiness.Building",
            "BLOCKED" to "RetainedCapabilityLaneReadiness.Blocked",
        )
        "RetainedCapabilityLaneReadiness.Available" -> subtypeWithDiscriminator(
            RetainedCapabilityLaneReadiness.Available.serializer(),
            discriminatorValue = "AVAILABLE",
        )
        "RetainedCapabilityLaneReadiness.Building" -> subtypeWithDiscriminator(
            RetainedCapabilityLaneReadiness.Building.serializer(),
            discriminatorValue = "BUILDING",
        )
        "RetainedCapabilityLaneReadiness.Blocked" -> subtypeWithDiscriminator(
            RetainedCapabilityLaneReadiness.Blocked.serializer(),
            discriminatorValue = "BLOCKED",
        )
        "RetainedCapabilityLaneFallback" -> discriminatedUnion(
            "type",
            "NONE" to "RetainedCapabilityLaneFallback.None",
            "PREVIOUS" to "RetainedCapabilityLaneFallback.Previous",
        )
        "RetainedCapabilityLaneFallback.None" -> subtypeWithDiscriminator(
            RetainedCapabilityLaneFallback.None.serializer(),
            discriminatorValue = "NONE",
        )
        "RetainedCapabilityLaneFallback.Previous" -> subtypeWithDiscriminator(
            RetainedCapabilityLaneFallback.Previous.serializer(),
            discriminatorValue = "PREVIOUS",
        )
        "RetainedWorkspaceGenerationStatus" -> discriminatedUnion(
            "type",
            "NONE" to "RetainedWorkspaceGenerationStatus.None",
            "PREVIOUS" to "RetainedWorkspaceGenerationStatus.Previous",
        )
        "RetainedWorkspaceGenerationStatus.None" -> subtypeWithDiscriminator(
            RetainedWorkspaceGenerationStatus.None.serializer(),
            discriminatorValue = "NONE",
        )
        "RetainedWorkspaceGenerationStatus.Previous" -> subtypeWithDiscriminator(
            RetainedWorkspaceGenerationStatus.Previous.serializer(),
            discriminatorValue = "PREVIOUS",
        )
        else -> null
    }

private fun SchemaRegistry.capabilityLaneEvidenceSchema(freshnessComponent: String): Map<String, Any?> =
    linkedMapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "revision" to linkedMapOf("type" to "integer", "format" to "int64", "minimum" to 1),
            "freshness" to refSchema(freshnessComponent),
        ),
        "additionalProperties" to false,
        "required" to listOf("revision", "freshness"),
    )
