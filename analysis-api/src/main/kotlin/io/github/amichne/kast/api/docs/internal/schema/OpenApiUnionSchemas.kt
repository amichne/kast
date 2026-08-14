package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.WorkspaceFilesContinuationResult
import kotlinx.serialization.KSerializer

internal fun SchemaRegistry.manualUnionSchema(componentName: String): Map<String, Any?>? =
    manualMutationProofUnionSchema(componentName)
        ?: manualProgressiveReadinessSchema(componentName)
        ?: when (componentName) {
        "RuntimeReadinessProgress" -> runtimeReadinessProgressSchema()
        "RuntimeReadinessLane" -> discriminatedUnion(
            "type",
            "READY" to "RuntimeReadinessLane.Ready",
            "IN_PROGRESS" to "RuntimeReadinessLane.InProgress",
            "BLOCKED" to "RuntimeReadinessLane.Blocked",
        )
        "RuntimeReadinessLane.Ready" -> subtypeWithDiscriminator(
            RuntimeReadinessLane.Ready.serializer(),
            discriminatorValue = "READY",
        )
        "RuntimeReadinessLane.InProgress" -> subtypeWithDiscriminator(
            RuntimeReadinessLane.InProgress.serializer(),
            discriminatorValue = "IN_PROGRESS",
        )
        "RuntimeReadinessLane.Blocked" -> subtypeWithDiscriminator(
            RuntimeReadinessLane.Blocked.serializer(),
            discriminatorValue = "BLOCKED",
        )
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
        "RawExactFileObservationResult" -> discriminatedUnion(
            "type",
            "ABSENT" to "RawExactFileObservationResult.Absent",
            "PRESENT" to "RawExactFileObservationResult.Present",
        )
        "RawExactFileObservationResult.Absent" -> subtypeWithDiscriminator(
            RawExactFileObservationResult.Absent.serializer(),
            discriminatorValue = "ABSENT",
        )
        "RawExactFileObservationResult.Present" -> subtypeWithDiscriminator(
            RawExactFileObservationResult.Present.serializer(),
            discriminatorValue = "PRESENT",
        )
        "MutationScratchRecoveryPreimage" -> discriminatedUnion(
            "state",
            "ABSENT" to "MutationScratchRecoveryPreimage.Absent",
            "PRESENT" to "MutationScratchRecoveryPreimage.Present",
        )
        "MutationScratchRecoveryPreimage.Absent" -> subtypeWithDiscriminator(
            MutationScratchRecoveryPreimage.Absent.serializer(),
            discriminatorValue = "ABSENT",
            discriminatorName = "state",
        )
        "MutationScratchRecoveryPreimage.Present" -> subtypeWithDiscriminator(
            MutationScratchRecoveryPreimage.Present.serializer(),
            discriminatorValue = "PRESENT",
            discriminatorName = "state",
        )
        "MutationPostconditionAuthority" -> discriminatedUnion(
            "type",
            "RENAME" to "MutationPostconditionAuthority.Rename",
            "REPLACEMENT" to "MutationPostconditionAuthority.Replacement",
            "ADD_FILE" to "MutationPostconditionAuthority.AddFile",
            "ADD_DECLARATION" to "MutationPostconditionAuthority.AddDeclaration",
        )
        "MutationPostconditionAuthority.Rename" -> subtypeWithDiscriminator(
            MutationPostconditionAuthority.Rename.serializer(),
            discriminatorValue = "RENAME",
        )
        "MutationPostconditionAuthority.Replacement" -> subtypeWithDiscriminator(
            MutationPostconditionAuthority.Replacement.serializer(),
            discriminatorValue = "REPLACEMENT",
        )
        "MutationPostconditionAuthority.AddFile" -> subtypeWithDiscriminator(
            MutationPostconditionAuthority.AddFile.serializer(),
            discriminatorValue = "ADD_FILE",
        )
        "MutationPostconditionAuthority.AddDeclaration" -> subtypeWithDiscriminator(
            MutationPostconditionAuthority.AddDeclaration.serializer(),
            discriminatorValue = "ADD_DECLARATION",
        )
        "MutationPostconditionEvidence" -> discriminatedUnion(
            "type",
            "RENAME" to "MutationPostconditionEvidence.Rename",
            "REPLACEMENT" to "MutationPostconditionEvidence.Replacement",
            "ADD_FILE" to "MutationPostconditionEvidence.AddFile",
            "ADD_DECLARATION" to "MutationPostconditionEvidence.AddDeclaration",
        )
        "MutationPostconditionEvidence.Rename" -> subtypeWithDiscriminator(
            MutationPostconditionEvidence.Rename.serializer(),
            discriminatorValue = "RENAME",
        )
        "MutationPostconditionEvidence.Replacement" -> subtypeWithDiscriminator(
            MutationPostconditionEvidence.Replacement.serializer(),
            discriminatorValue = "REPLACEMENT",
        )
        "MutationPostconditionEvidence.AddFile" -> subtypeWithDiscriminator(
            MutationPostconditionEvidence.AddFile.serializer(),
            discriminatorValue = "ADD_FILE",
        )
        "MutationPostconditionEvidence.AddDeclaration" -> subtypeWithDiscriminator(
            MutationPostconditionEvidence.AddDeclaration.serializer(),
            discriminatorValue = "ADD_DECLARATION",
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
        else -> null
    }

private fun runtimeReadinessProgressSchema(): Map<String, Any?> = linkedMapOf(
    "type" to "object",
    "description" to "Typed stage and bounded progress evidence.",
    "properties" to linkedMapOf(
        "stage" to linkedMapOf("\$ref" to "#/components/schemas/RuntimeProgressStage"),
        "completedUnits" to linkedMapOf("type" to "integer", "format" to "int64", "minimum" to 0),
        "totalUnits" to linkedMapOf("type" to "integer", "format" to "int64", "minimum" to 0),
        "elapsedMillis" to linkedMapOf("type" to "integer", "format" to "int64", "minimum" to 0),
        "noProgressMillis" to linkedMapOf("type" to "integer", "format" to "int64", "minimum" to 0),
    ),
    "additionalProperties" to false,
    "required" to listOf("stage", "completedUnits", "totalUnits", "elapsedMillis", "noProgressMillis"),
)

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

internal fun SchemaRegistry.subtypeWithDiscriminator(
    serializer: KSerializer<*>,
    discriminatorValue: String,
    discriminatorName: String = "type",
    nonEmptyCollections: Set<String> = emptySet(),
    omittedWhenNullFields: Set<String> = emptySet(),
): Map<String, Any?> {
    val base = objectSchema(serializer.descriptor) as LinkedHashMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val props = base["properties"] as LinkedHashMap<String, Any?>
    val discriminatorProperty = linkedMapOf<String, Any?>("type" to "string", "const" to discriminatorValue)
    val withDiscriminator = linkedMapOf<String, Any?>(discriminatorName to discriminatorProperty)
    withDiscriminator.putAll(props)
    nonEmptyCollections.forEach { field ->
        @Suppress("UNCHECKED_CAST")
        val collection = withDiscriminator[field] as? LinkedHashMap<String, Any?>
            ?: error("Non-empty collection field is not an array schema: $field")
        collection["minItems"] = 1
    }
    base["properties"] = withDiscriminator
    @Suppress("UNCHECKED_CAST")
    val required = (base["required"] as? MutableList<String>) ?: mutableListOf()
    if (discriminatorName !in required) required.add(0, discriminatorName)
    require(omittedWhenNullFields.all(withDiscriminator::containsKey)) {
        "Omitted nullable fields must exist in the subtype schema: $omittedWhenNullFields"
    }
    required.removeAll(omittedWhenNullFields)
    base["required"] = required
    return base
}

internal fun SchemaRegistry.discriminatedUnion(
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
