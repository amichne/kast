package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackValueBindingDocument
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
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.SourceRangeCliDocument
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedComponentSchemaReuseTest {
    @Test
    fun `shared component proofs preserve each independent serializer shape`() {
        val schema = installedPublicToolOutputSchema(PublicToolIdentity.QUERY_SYMBOLS)
        val assertions = LocalSchemaAssertions(schema)
        val independentSchemas = IndependentImpactSchemas()
        for ((name, serializer) in
            mapOf(
                "sourceRangeCli" to SourceRangeCliDocument.serializer(),
                "impactReviewedValueRule" to ImpactReviewedValueRuleDocument.serializer(),
                "impactValueConditionOrigin" to ImpactValueConditionOriginDocument.serializer(),
                "impactSourceRange" to ImpactSourceRangeDocument.serializer(),
                "impactDeclaredCallbackValueBinding" to ImpactDeclaredCallbackValueBindingDocument.serializer(),
                "impactDeclaredCallbackTarget" to ImpactDeclaredCallbackTargetDocument.serializer(),
                "impactReviewedFunction" to ImpactReviewedFunctionDocument.serializer(),
                "impactReviewedGroupBucket" to ImpactReviewedGroupBucketDocument.serializer(),
                "impactReviewedSourceLines" to ImpactReviewedSourceLinesDocument.serializer(),
                "impactReviewedOrdinaryTarget" to ImpactReviewedOrdinaryTargetDocument.serializer(),
                "impactReviewedArtifacts" to ImpactReviewedArtifactsDocument.serializer(),
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
            )) {
            assertions.assertDefinitionEquivalent(name, independentSchemas.graph(serializer))
            assertTrue(referenceCount(schema, name) > 0, "The schema must reference the exact $name definition")
        }
    }
}
