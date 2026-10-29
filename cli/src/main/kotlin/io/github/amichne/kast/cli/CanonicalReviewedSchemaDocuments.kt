package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedOrdinaryTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import kotlinx.serialization.KSerializer

/** Exact serializer descriptors own the shared addresses for reviewed value evidence. */
internal object CanonicalReviewedSchemaDocuments {
    val serializers: Map<String, KSerializer<*>> =
        mapOf(
            "impactReviewedSourceLines" to ImpactReviewedSourceLinesDocument.serializer(),
            "impactReviewedOrdinaryTarget" to ImpactReviewedOrdinaryTargetDocument.serializer(),
            "impactReviewedArtifacts" to ImpactReviewedArtifactsDocument.serializer(),
            "impactReviewedValueBinding" to ImpactReviewedValueBindingDocument.serializer(),
            "impactReviewedValueRelationship" to ImpactReviewedValueRelationshipDocument.serializer(),
            "impactReviewedValueTransfer" to ImpactReviewedValueTransferDocument.serializer(),
            "impactReviewedCallValueBinding" to ImpactReviewedCallValueBindingDocument.serializer(),
            "impactReviewedTupleOrigin" to ImpactReviewedTupleOriginDocument.serializer(),
            "impactReviewedMapEntryCorrelation" to ImpactReviewedMapEntryCorrelationDocument.serializer(),
            "impactReviewedDistinctStringKeys" to ImpactReviewedDistinctStringKeysDocument.serializer(),
        )
}
