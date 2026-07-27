package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.compatibility.RuntimeCapability
import io.github.amichne.kast.api.contract.compatibility.RuntimeCompatibilityOutcome
import io.github.amichne.kast.api.contract.compatibility.RuntimeCompatibilityUpdateRequirement
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.WorkspaceFilesContinuationResult
import kotlinx.serialization.KSerializer

internal fun SchemaRegistry.manualUnionSchema(componentName: String): Map<String, Any?>? =
    when (componentName) {
        "FileOperation" -> discriminatedUnion(
            "type",
            "CREATE_FILE" to "FileOperation.CreateFile",
            "DELETE_FILE" to "FileOperation.DeleteFile",
        )
        "FileOperation.CreateFile" -> subtypeWithDiscriminator(
            FileOperation.CreateFile.serializer(),
            discriminatorValue = "CREATE_FILE",
        )
        "FileOperation.DeleteFile" -> subtypeWithDiscriminator(
            FileOperation.DeleteFile.serializer(),
            discriminatorValue = "DELETE_FILE",
        )
        "ResultCardinality" -> discriminatedUnion(
            "type",
            "EXACT" to "EXACT",
            "KNOWN_MINIMUM" to "KNOWN_MINIMUM",
        )
        "EXACT" -> subtypeWithDiscriminator(
            ResultCardinality.Exact.serializer(),
            discriminatorValue = "EXACT",
        )
        "KNOWN_MINIMUM" -> subtypeWithDiscriminator(
            ResultCardinality.KnownMinimum.serializer(),
            discriminatorValue = "KNOWN_MINIMUM",
        )
        "RelationshipResultEvidence" -> discriminatedUnion(
            "type",
            "COMPLETE" to "RelationshipResultEvidence.Complete",
            "RESUMABLE" to "RelationshipResultEvidence.Resumable",
            "LIMITED" to "RelationshipResultEvidence.Limited",
        )
        "RelationshipResultEvidence.Complete" -> relationshipEvidenceVariant(
            discriminatorValue = "COMPLETE",
            cardinalityComponent = "EXACT",
            coverageComponent = "RelationshipSearchCoverage.Complete",
        )
        "RelationshipResultEvidence.Resumable" -> relationshipEvidenceVariant(
            discriminatorValue = "RESUMABLE",
            cardinalityComponent = "KNOWN_MINIMUM",
            coverageComponent = "RelationshipSearchCoverage.Resumable",
        )
        "RelationshipResultEvidence.Limited" -> relationshipEvidenceVariant(
            discriminatorValue = "LIMITED",
            cardinalityComponent = "KNOWN_MINIMUM",
            coverageComponent = "RelationshipSearchCoverage.Limited",
        )
        "RelationshipSearchCoverage" -> discriminatedUnion(
            "type",
            "COMPLETE" to "RelationshipSearchCoverage.Complete",
            "RESUMABLE" to "RelationshipSearchCoverage.Resumable",
            "LIMITED" to "RelationshipSearchCoverage.Limited",
        )
        "RelationshipSearchCoverage.Complete" -> relationshipCoverageVariant(
            discriminatorValue = "COMPLETE",
            fixedStatuses = relationshipCoverageDimensions.associateWith { "COMPLETE" },
            limitationsMinimum = 0,
            limitationsMaximum = 0,
        )
        "RelationshipSearchCoverage.Resumable" -> relationshipCoverageVariant(
            discriminatorValue = "RESUMABLE",
            fixedStatuses = relationshipCoverageDimensions.associateWith { dimension ->
                if (dimension == "requestedFamily") "IN_PROGRESS" else "COMPLETE"
            },
            limitationsMinimum = 1,
            limitationsMaximum = 1,
            fixedLimitation = "FAMILY_SEARCH_IN_PROGRESS",
        )
        "RelationshipSearchCoverage.Limited" -> relationshipCoverageVariant(
            discriminatorValue = "LIMITED",
            fixedStatuses = emptyMap(),
            limitationsMinimum = 1,
            limitationsMaximum = null,
        )
        "WorkspaceFilesContinuationQuery" -> discriminatedUnion(
            "action",
            "ISSUE" to "WorkspaceFilesContinuationQuery.Issue",
            "CONSUME" to "WorkspaceFilesContinuationQuery.Consume",
        )
        "WorkspaceFilesContinuationQuery.Issue" -> continuationQueryVariant(
            action = "ISSUE",
            payloadName = "state",
            payloadSchema = refSchema("WorkspaceFilesPublicContinuationState"),
        )
        "WorkspaceFilesContinuationQuery.Consume" -> continuationQueryVariant(
            action = "CONSUME",
            payloadName = "pageToken",
            payloadSchema = refSchema("WorkspaceFilesPublicPageToken"),
        )
        "WorkspaceFilesContinuationResult" -> discriminatedUnion(
            "type",
            "ISSUED" to "WorkspaceFilesContinuationResult.Issued",
            "CONSUMED" to "WorkspaceFilesContinuationResult.Consumed",
        )
        "WorkspaceFilesContinuationResult.Issued" -> subtypeWithDiscriminator(
            WorkspaceFilesContinuationResult.Issued.serializer(),
            discriminatorValue = "ISSUED",
        )
        "WorkspaceFilesContinuationResult.Consumed" -> subtypeWithDiscriminator(
            WorkspaceFilesContinuationResult.Consumed.serializer(),
            discriminatorValue = "CONSUMED",
        )
        "RuntimeCapability" -> discriminatedUnion(
            "type",
            "READ" to "RuntimeCapability.Read",
            "MUTATION" to "RuntimeCapability.Mutation",
        )
        "RuntimeCapability.Read" -> subtypeWithDiscriminator(
            RuntimeCapability.Read.serializer(),
            discriminatorValue = "READ",
        )
        "RuntimeCapability.Mutation" -> subtypeWithDiscriminator(
            RuntimeCapability.Mutation.serializer(),
            discriminatorValue = "MUTATION",
        )
        "RuntimeCompatibilityUpdateRequirement" -> discriminatedUnion(
            "type",
            "UNSUPPORTED_RELEASE_PAIR" to
                "RuntimeCompatibilityUpdateRequirement.UnsupportedReleasePair",
            "UNSUPPORTED_PROTOCOL_REVISION" to
                "RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision",
            "UNSUPPORTED_WORKSPACE_METADATA_REVISION" to
                "RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision",
            "UNSUPPORTED_RUNTIME_IDENTITY" to
                "RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity",
            "MISSING_REQUIRED_CAPABILITY" to
                "RuntimeCompatibilityUpdateRequirement.MissingRequiredCapability",
        )
        "RuntimeCompatibilityUpdateRequirement.UnsupportedReleasePair" -> subtypeWithDiscriminator(
            RuntimeCompatibilityUpdateRequirement.UnsupportedReleasePair.serializer(),
            discriminatorValue = "UNSUPPORTED_RELEASE_PAIR",
        )
        "RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision" -> subtypeWithDiscriminator(
            RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision.serializer(),
            discriminatorValue = "UNSUPPORTED_PROTOCOL_REVISION",
            nonEmptyCollections = setOf("supported"),
        )
        "RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision" -> subtypeWithDiscriminator(
            RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision.serializer(),
            discriminatorValue = "UNSUPPORTED_WORKSPACE_METADATA_REVISION",
            nonEmptyCollections = setOf("supported"),
        )
        "RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity" -> subtypeWithDiscriminator(
            RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity.serializer(),
            discriminatorValue = "UNSUPPORTED_RUNTIME_IDENTITY",
            nonEmptyCollections = setOf("supported"),
        )
        "RuntimeCompatibilityUpdateRequirement.MissingRequiredCapability" -> subtypeWithDiscriminator(
            RuntimeCompatibilityUpdateRequirement.MissingRequiredCapability.serializer(),
            discriminatorValue = "MISSING_REQUIRED_CAPABILITY",
        )
        "RuntimeCompatibilityOutcome" -> discriminatedUnion(
            "type",
            "COMPATIBLE" to "RuntimeCompatibilityOutcome.Compatible",
            "UPDATE_REQUIRED" to "RuntimeCompatibilityOutcome.UpdateRequired",
            "MISSING_CAPABILITY" to "RuntimeCompatibilityOutcome.MissingCapability",
        )
        "RuntimeCompatibilityOutcome.Compatible" -> subtypeWithDiscriminator(
            RuntimeCompatibilityOutcome.Compatible.serializer(),
            discriminatorValue = "COMPATIBLE",
        )
        "RuntimeCompatibilityOutcome.UpdateRequired" -> subtypeWithDiscriminator(
            RuntimeCompatibilityOutcome.UpdateRequired.serializer(),
            discriminatorValue = "UPDATE_REQUIRED",
        )
        "RuntimeCompatibilityOutcome.MissingCapability" -> subtypeWithDiscriminator(
            RuntimeCompatibilityOutcome.MissingCapability.serializer(),
            discriminatorValue = "MISSING_CAPABILITY",
        )
        else -> null
    }

private fun SchemaRegistry.relationshipEvidenceVariant(
    discriminatorValue: String,
    cardinalityComponent: String,
    coverageComponent: String,
): Map<String, Any?> = linkedMapOf(
    "type" to "object",
    "properties" to linkedMapOf(
        "type" to linkedMapOf("type" to "string", "const" to discriminatorValue),
        "cardinality" to refSchema(cardinalityComponent),
        "coverage" to refSchema(coverageComponent),
    ),
    "additionalProperties" to false,
    "required" to listOf("type", "cardinality", "coverage"),
)

private fun SchemaRegistry.relationshipCoverageVariant(
    discriminatorValue: String,
    fixedStatuses: Map<String, String>,
    limitationsMinimum: Int,
    limitationsMaximum: Int?,
    fixedLimitation: String? = null,
): Map<String, Any?> {
    val properties = linkedMapOf<String, Any?>(
        "type" to linkedMapOf("type" to "string", "const" to discriminatorValue),
    )
    relationshipCoverageDimensions.forEach { dimension ->
        properties[dimension] = fixedStatuses[dimension]?.let { status ->
            linkedMapOf("type" to "string", "const" to status)
        } ?: refSchema("RelationshipCoverageStatus")
    }
    properties["limitations"] = linkedMapOf<String, Any?>(
        "type" to "array",
        "items" to (fixedLimitation?.let { limitation ->
            linkedMapOf("type" to "string", "const" to limitation)
        } ?: refSchema("RelationshipSearchLimitation")),
        "minItems" to limitationsMinimum,
    ).also { limitations ->
        limitationsMaximum?.let { maximum -> limitations["maxItems"] = maximum }
    }
    return linkedMapOf(
        "type" to "object",
        "properties" to properties,
        "additionalProperties" to false,
        "required" to listOf("type") + relationshipCoverageDimensions + "limitations",
    )
}

private val relationshipCoverageDimensions = listOf(
    "identity",
    "projectScope",
    "sourceSetScope",
    "indexFreshness",
    "backend",
    "requestedFamily",
)

private fun SchemaRegistry.continuationQueryVariant(
    action: String,
    payloadName: String,
    payloadSchema: Map<String, Any?>,
): Map<String, Any?> = linkedMapOf(
    "type" to "object",
    "properties" to linkedMapOf(
        "action" to linkedMapOf("type" to "string", "const" to action),
        "identity" to refSchema("WorkspaceFilesPublicContinuationIdentity"),
        payloadName to payloadSchema,
    ),
    "additionalProperties" to false,
    "required" to listOf("action", "identity", payloadName),
)

private fun SchemaRegistry.subtypeWithDiscriminator(
    serializer: KSerializer<*>,
    discriminatorValue: String,
    nonEmptyCollections: Set<String> = emptySet(),
): Map<String, Any?> {
    val base = objectSchema(serializer.descriptor) as LinkedHashMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val props = base["properties"] as LinkedHashMap<String, Any?>
    val typeProperty = linkedMapOf<String, Any?>("type" to "string", "const" to discriminatorValue)
    val withType = linkedMapOf<String, Any?>("type" to typeProperty)
    withType.putAll(props)
    nonEmptyCollections.forEach { field ->
        @Suppress("UNCHECKED_CAST")
        val collection = withType[field] as? LinkedHashMap<String, Any?>
            ?: error("Non-empty collection field is not an array schema: $field")
        collection["minItems"] = 1
    }
    base["properties"] = withType
    @Suppress("UNCHECKED_CAST")
    val required = (base["required"] as? MutableList<String>) ?: mutableListOf()
    if ("type" !in required) required.add(0, "type")
    base["required"] = required
    return base
}

private fun SchemaRegistry.discriminatedUnion(
    propertyName: String,
    vararg mappingEntries: Pair<String, String>,
): Map<String, Any?> {
    val mapping = linkedMapOf<String, String>()
    val refs = mutableListOf<Any?>()
    mappingEntries.forEach { (value, component) ->
        mapping[value] = "#/components/schemas/$component"
        refs += linkedMapOf("\$ref" to "#/components/schemas/$component")
    }
    return linkedMapOf(
        "oneOf" to refs,
        "discriminator" to linkedMapOf(
            "propertyName" to propertyName,
            "mapping" to mapping,
        ),
    )
}
