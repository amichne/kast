package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallbackParentDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMappedEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMappedProjectionTest {
    @Test
    fun `mapped projection preserves its value origin suffix and premise`() {
        val fixture = ImpactReviewedMappedProjectionFixture()
        val projected = fixture.transfer.impactDocument().componentValue()
        assertTrue(projected.admitsEvidence())
        val component = projected.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent
        val entry = component.entry as ImpactReviewedMappedEntryOriginDocument
        assertEquals(projected.source.range, entry.returnRange)
        assertEquals(projected.source.valuePath.values, projected.target.valuePath.values.drop(1))
        assertEquals(
            entry,
            (projected.target.conditions.values.single() as ImpactValueConditionDocument.ReviewedMappedKeySurvives)
                .entry,
        )
    }

    @Test
    fun `nested mapped projection keeps the declared supplier owner`() {
        val projected = nestedTransfer().impactDocument().componentValue()
        assertTrue(projected.admitsEvidence())
        val authority = projected.binding.authority as ImpactReviewedValueBindingDocument
        val parentDocument = authority.parent as ImpactReviewedCallbackParentDocument.DeclaredCallback
        assertEquals(
            ImpactExecutionOwnerDocument.DeclaredCallback(parentDocument.binding),
            projected.target.executionOwner,
        )
        assertFalse(
            projected
                .copy(target = projected.target.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration))
                .admitsEvidence()
        )
    }

    private fun nestedTransfer(): ReviewedValueTransfer {
        val scope = ReviewedScopeProjectionFixture().transfer()
        val parent = (scope.source.executionOwner.context as ValueExecutionContext.DeclaredCallback).binding
        val owner = parent.enclosing as RelationEndpoint.Resolved
        val mapped = ImpactReviewedMappedProjectionFixture()
        val binding =
            ReviewedCallbackValueBinding.admit(
                    owner,
                    nestedBody(owner),
                    RelationOccurrence.fromBoundary(owner.file, 45, 85).componentValue(),
                    parent.body,
                    parent,
                    mapped.binding.selected,
                    mapped.binding.artifact,
                )
                .componentValue()
        val source =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(60, 70),
                    ValueRole.Return,
                    ValueExecutionOwner.reviewedCallback(binding),
                    valuePath = mapped.source.valuePath,
                )
                .componentValue()
        return ReviewedValueTransfer.callbackResult(source, binding).componentValue()
    }

    private fun nestedBody(owner: RelationEndpoint): RelationCallableBody.Anonymous {
        val range = componentRange(50, 80)
        return RelationCallableBody.Anonymous.fromCompiler(
                owner.file,
                range,
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                        null,
                        emptyList(),
                        listOf("T"),
                        0,
                    )
                    .componentValue() as CanonicalCompilerSignature.Function,
            )
            .componentValue()
    }
}
