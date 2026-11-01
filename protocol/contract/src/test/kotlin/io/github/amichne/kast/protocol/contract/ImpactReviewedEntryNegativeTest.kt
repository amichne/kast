package io.github.amichne.kast.protocol.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ImpactReviewedEntryNegativeTest {
    @Test
    fun `entry input rejects result shortcuts and callback owner erasure`() {
        val fixture = ImpactReviewedEntryFixture()
        assertFalse(fixture.input.copy(target = fixture.producer.transfer.target).admitsEvidence())
        assertFalse(
            fixture.input
                .copy(target = fixture.input.target.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration))
                .admitsEvidence()
        )
        assertFalse(
            fixture.input.copy(target = fixture.input.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence()
        )
    }

    @Test
    fun `entry getter rejects slot swaps and omitted stability premise`() {
        val fixture = ImpactReviewedEntryFixture()
        val read = fixture.read(false)
        val relation = read.binding.relation as ImpactReviewedValueRelationDocument.EntryProjection
        assertFalse(
            read
                .copy(binding = read.binding.copy(relation = relation.copy(slot = ImpactMapEntrySlotDocument.KEY)))
                .admitsEvidence()
        )
        assertFalse(read.copy(target = read.target.copy(conditions = read.source.conditions)).admitsEvidence())
    }

    @Test
    fun `metadata getter rejects altered declaration bytes and JVM substitution`() {
        val fixture = ImpactReviewedEntryFixture()
        val read = fixture.read(true)
        val authority = read.binding.authority as ImpactReviewedCallValueBindingDocument
        val artifact = authority.artifacts as ImpactReviewedEntryDeclarationArtifactDocument
        val wrong = artifact.copy(metadata = artifact.metadata.copy(sha256 = fixture.base.text("0".repeat(64))))
        listOf(wrong, fixture.binding.artifacts).forEach { candidate ->
            assertFalse(
                read
                    .copy(binding = read.binding.copy(authority = authority.copy(artifacts = candidate)))
                    .admitsEvidence()
            )
        }
    }
}
