package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionRebindingCurrentTarget
import io.github.amichne.kast.api.contract.result.AdditionResolvedTarget
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSignature
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPropertySignature
import io.github.amichne.kast.api.contract.result.ReplacementProofDimension

internal fun SchemaRegistry.manualMutationProofUnionSchema(componentName: String): Map<String, Any?>? =
    when (componentName) {
        "ContainingSymbolEvidence" -> discriminatedUnion(
            "type",
            "KNOWN" to "ContainingSymbolEvidence.Known",
            "TOP_LEVEL" to "ContainingSymbolEvidence.TopLevel",
            "UNAVAILABLE" to "ContainingSymbolEvidence.Unavailable",
        )
        "ContainingSymbolEvidence.Known" -> subtypeWithDiscriminator(
            ContainingSymbolEvidence.Known.serializer(),
            discriminatorValue = "KNOWN",
        )
        "ContainingSymbolEvidence.TopLevel" -> subtypeWithDiscriminator(
            ContainingSymbolEvidence.TopLevel.serializer(),
            discriminatorValue = "TOP_LEVEL",
        )
        "ContainingSymbolEvidence.Unavailable" -> subtypeWithDiscriminator(
            ContainingSymbolEvidence.Unavailable.serializer(),
            discriminatorValue = "UNAVAILABLE",
        )
        "ReplacementDeclarationSignature" -> discriminatedUnion(
            "type",
            "function" to "ReplacementDeclarationSignature.Function",
            "property" to "ReplacementDeclarationSignature.Property",
        )
        "ReplacementDeclarationSignature.Function" -> subtypeWithDiscriminator(
            ReplacementFunctionSignature.serializer(),
            discriminatorValue = "function",
            omittedWhenNullFields = setOf("receiverType"),
        )
        "ReplacementDeclarationSignature.Property" -> subtypeWithDiscriminator(
            ReplacementPropertySignature.serializer(),
            discriminatorValue = "property",
            omittedWhenNullFields = setOf("receiverType", "setterVisibility"),
        )
        "ReplacementOutboundEvidence" -> discriminatedUnion(
            "type",
            "complete" to "ReplacementOutboundEvidence.Complete",
            "limited" to "ReplacementOutboundEvidence.Limited",
        )
        "ReplacementOutboundEvidence.Complete" -> replacementEvidenceVariant(
            discriminatorValue = "complete",
            cardinalityComponent = "EXACT",
            minimumDimensionCount = ReplacementProofDimension.entries.size,
            maximumDimensionCount = ReplacementProofDimension.entries.size,
        )
        "ReplacementOutboundEvidence.Limited" -> replacementEvidenceVariant(
            discriminatorValue = "limited",
            cardinalityComponent = "KNOWN_MINIMUM",
            minimumDimensionCount = 1,
        )
        "ReplacementOutboundTarget" -> discriminatedUnion(
            "type",
            "source" to "ReplacementOutboundTarget.Source",
            "external" to "ReplacementOutboundTarget.External",
        )
        "ReplacementOutboundTarget.Source" -> subtypeWithDiscriminator(
            ReplacementOutboundTarget.Source.serializer(),
            discriminatorValue = "source",
        )
        "ReplacementOutboundTarget.External" -> subtypeWithDiscriminator(
            ReplacementOutboundTarget.External.serializer(),
            discriminatorValue = "external",
        )
        "AdditionKotlinPackage" -> discriminatedUnion(
            "type",
            "ROOT" to "AdditionKotlinPackage.Root",
            "NAMED" to "AdditionKotlinPackage.Named",
        )
        "AdditionKotlinPackage.Root" -> subtypeWithDiscriminator(
            AdditionKotlinPackage.Root.serializer(),
            discriminatorValue = "ROOT",
        )
        "AdditionKotlinPackage.Named" -> subtypeWithDiscriminator(
            AdditionKotlinPackage.Named.serializer(),
            discriminatorValue = "NAMED",
            nonEmptyCollections = setOf("segments"),
        )
        "AdditionResolvedTarget" -> discriminatedUnion(
            "type",
            "SOURCE" to "AdditionResolvedTarget.Source",
            "EXTERNAL" to "AdditionResolvedTarget.External",
        )
        "AdditionResolvedTarget.Source" -> subtypeWithDiscriminator(
            AdditionResolvedTarget.Source.serializer(),
            discriminatorValue = "SOURCE",
        )
        "AdditionResolvedTarget.External" -> subtypeWithDiscriminator(
            AdditionResolvedTarget.External.serializer(),
            discriminatorValue = "EXTERNAL",
        )
        "AdditionRebindingCurrentTarget" -> discriminatedUnion(
            "type",
            "RESOLVED" to "AdditionRebindingCurrentTarget.Resolved",
            "UNRESOLVED" to "AdditionRebindingCurrentTarget.Unresolved",
        )
        "AdditionRebindingCurrentTarget.Resolved" -> subtypeWithDiscriminator(
            AdditionRebindingCurrentTarget.Resolved.serializer(),
            discriminatorValue = "RESOLVED",
        )
        "AdditionRebindingCurrentTarget.Unresolved" -> subtypeWithDiscriminator(
            AdditionRebindingCurrentTarget.Unresolved.serializer(),
            discriminatorValue = "UNRESOLVED",
        )
        else -> null
    }

private fun SchemaRegistry.replacementEvidenceVariant(
    discriminatorValue: String,
    cardinalityComponent: String,
    minimumDimensionCount: Int,
    maximumDimensionCount: Int? = null,
): Map<String, Any?> = linkedMapOf(
    "type" to "object",
    "properties" to linkedMapOf(
        "type" to linkedMapOf("type" to "string", "const" to discriminatorValue),
        "cardinality" to refSchema(cardinalityComponent),
        "dimensions" to linkedMapOf<String, Any?>(
            "type" to "array",
            "items" to refSchema("ReplacementProofDimension"),
            "minItems" to minimumDimensionCount,
            "uniqueItems" to true,
        ).also { dimensions ->
            maximumDimensionCount?.let { dimensions["maxItems"] = it }
        },
    ),
    "additionalProperties" to false,
    "required" to listOf("type", "cardinality", "dimensions"),
)
