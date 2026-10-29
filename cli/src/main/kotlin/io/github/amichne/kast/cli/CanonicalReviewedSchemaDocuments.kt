package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallbackParentDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryComponentInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedFunctionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedGroupBucketDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInputEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryFamilyDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMappedEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedOrdinaryTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedPreservedKeyOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import kotlinx.serialization.KSerializer

/** Exact serializer descriptors own the shared addresses for reviewed value evidence. */
internal object CanonicalReviewedSchemaDocuments {
    val serializers: Map<String, KSerializer<*>> =
        mapOf(
            "impactReviewedValueRule" to ImpactReviewedValueRuleDocument.serializer(),
            "impactReviewedFunction" to ImpactReviewedFunctionDocument.serializer(),
            "impactReviewedGroupBucket" to ImpactReviewedGroupBucketDocument.serializer(),
            "impactReviewedSourceLines" to ImpactReviewedSourceLinesDocument.serializer(),
            "impactReviewedOrdinaryTarget" to ImpactReviewedOrdinaryTargetDocument.serializer(),
            "impactReviewedArtifacts" to ImpactReviewedArtifactsDocument.serializer(),
            "impactReviewedValueBinding" to ImpactReviewedValueBindingDocument.serializer(),
            "impactReviewedValueRelationship" to ImpactReviewedValueRelationshipDocument.serializer(),
            "impactReviewedValueTransfer" to ImpactReviewedValueTransferDocument.serializer(),
            "impactReviewedCallValueBinding" to ImpactReviewedCallValueBindingDocument.serializer(),
            "impactReviewedTupleOrigin" to ImpactReviewedTupleOriginDocument.serializer(),
            "impactReviewedMapEntryCorrelation" to ImpactReviewedMapEntryCorrelationDocument.serializer(),
            "impactReviewedMapEntryOrigin" to ImpactReviewedMapEntryOriginDocument.serializer(),
            "impactReviewedMapEntryFamily" to ImpactReviewedMapEntryFamilyDocument.serializer(),
            "impactReviewedInputEntryOrigin" to ImpactReviewedInputEntryOriginDocument.serializer(),
            "impactReviewedPreservedKeyOrigin" to ImpactReviewedPreservedKeyOriginDocument.serializer(),
            "impactReviewedEntryReadOrder" to ImpactReviewedEntryReadOrderDocument.serializer(),
            "impactReviewedEntryComponentInvocation" to ImpactReviewedEntryComponentInvocationDocument.serializer(),
            "impactReviewedMappedEntryOrigin" to ImpactReviewedMappedEntryOriginDocument.serializer(),
            "impactReviewedCallbackParent" to ImpactReviewedCallbackParentDocument.serializer(),
            "impactReviewedDistinctStringKeys" to ImpactReviewedDistinctStringKeysDocument.serializer(),
        )
}
