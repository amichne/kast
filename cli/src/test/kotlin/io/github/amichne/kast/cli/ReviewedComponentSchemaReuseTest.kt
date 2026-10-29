package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedOrdinaryTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedComponentSchemaReuseTest {
    @Test
    fun `shared component proofs preserve each independent serializer shape`() {
        val schema = installedPublicToolOutputSchema(PublicToolIdentity.QUERY_SYMBOLS)
        val assertions = LocalSchemaAssertions(schema)
        for ((name, serializer) in
            mapOf(
                "impactValueConditionOrigin" to ImpactValueConditionOriginDocument.serializer(),
                "impactReviewedSourceLines" to ImpactReviewedSourceLinesDocument.serializer(),
                "impactReviewedOrdinaryTarget" to ImpactReviewedOrdinaryTargetDocument.serializer(),
                "impactReviewedArtifacts" to ImpactReviewedArtifactsDocument.serializer(),
                "impactReviewedCallValueBinding" to ImpactReviewedCallValueBindingDocument.serializer(),
                "impactReviewedTupleOrigin" to ImpactReviewedTupleOriginDocument.serializer(),
                "impactReviewedMapEntryCorrelation" to ImpactReviewedMapEntryCorrelationDocument.serializer(),
                "impactReviewedDistinctStringKeys" to ImpactReviewedDistinctStringKeysDocument.serializer(),
            )) {
            assertions.assertDefinitionEquivalent(name, independentFactoredImpactSchema(serializer))
            assertTrue(referenceCount(schema, name) > 0, "The schema must reference the exact $name definition")
        }
    }
}
