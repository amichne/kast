package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedEntryProjectionTest {
    @Test
    fun `entry read rejects an exchanged slot for property and component targets`() {
        val f = ReviewedEntryReadFixture()
        for (operation in
            listOf(ReviewedLibraryOperation.MAP_ENTRY_KEY, ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1)) {
            val binding = f.binding(operation)
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
                ReviewedValueTransfer.entryProject(f.receiver(binding), binding),
            )
        }
    }

    @Test
    fun `entry read rejects another execution owner and unselected map content`() {
        val f = ReviewedEntryReadFixture()
        val binding = f.binding()
        val receiver = f.receiver(binding)
        val declarationCall =
            ValueInvocation.getterFromCompiler(
                    receiver.enclosing,
                    binding.invocation.range,
                    binding.invocation.callable,
                    ValueExecutionOwner.declaration(receiver.enclosing),
                )
                .flowRefined()
        val wrongOwner =
            ValueSite.fromCompiler(
                    enclosing = receiver.enclosing,
                    range = receiver.range,
                    role = ValueRole.Receiver(declarationCall, ValueReceiverKind.DISPATCH),
                    valuePath = receiver.valuePath,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.entryProject(wrongOwner, binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            ReviewedValueTransfer.entryProject(
                receiver.withState(valuePath = ValueAccessPath.Whole).flowRefined(),
                binding,
            ),
        )
    }

    @Test
    fun `metadata getter and JVM component preserve suffix with stable slot premise`() {
        val f = ReviewedEntryReadFixture()
        for (operation in
            listOf(ReviewedLibraryOperation.MAP_ENTRY_VALUE, ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2)) {
            val binding = f.binding(operation)
            val source = f.receiver(binding)
            val transfer = ReviewedValueTransfer.entryProject(source, binding).flowRefined()
            assertEquals(source.valuePath.segments.drop(1), transfer.target.valuePath.segments)
            assertEquals(source.conditions.facts, transfer.target.conditions.facts.dropLast(1))
            val premise = transfer.target.conditions.facts.last() as ValueCondition.ReviewedEntryComponentStable
            assertSame(binding, premise.binding)
            assertEquals(MapEntrySlot.VALUE, premise.slot)
            assertTrue(premise.origin.matches(source))
            assertEquals(binding.invocation.range, premise.useRange)
            assertEquals(false, source.conditions.admits(source, transfer.target, ValueTransferKind.FIELD_READ))
        }
    }

    @Test
    fun `metadata artifact rejects wrong archives and cannot become a JVM implementation`() {
        val f = ReviewedEntryReadFixture()
        val binding = f.binding()
        assertTrue(binding.artifact is ReviewedEntryDeclarationArtifact)
        assertEquals(
            "kotlin/collections/collections.kotlin_builtins",
            (binding.artifact as ReviewedEntryDeclarationArtifact).metadata.entry,
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(
                binding.selected,
                f.input.base.digest("ab".repeat(32)),
                binding.artifact.sourceArtifact,
            ),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(
                binding.selected,
                binding.artifact.binaryArtifact,
                f.input.base.digest("cd".repeat(32)),
            ),
        )
    }

    @Test
    fun `selected key and actual mapped value share a static family but distinct witnesses`() {
        val f = ReviewedEntryReadFixture(MapEntrySlot.KEY)
        val key = ReviewedValueTransfer.mapPreservedKey(f.source, f.input.binding).flowRefined()
        val returned = f.input.base.source(owner = ValueExecutionOwner.reviewedCallback(f.input.binding))
        val mapped = ReviewedValueTransfer.callbackResult(returned, f.input.binding).flowRefined()
        val keyOrigin = (key.relation as ReviewedValueRelation.PreservedKey).entry
        val valueOrigin = (mapped.relation as ReviewedValueRelation.MappedValue).entry
        assertEquals(keyOrigin.family, valueOrigin.family)
        assertNotEquals(keyOrigin, valueOrigin)
        assertTrue(key.target.conditions.facts.last() is ValueCondition.ReviewedPreservedKeySurvives)
        assertEquals(f.source.valuePath.segments.drop(1), key.target.valuePath.segments.drop(1))
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.COMPONENT_MISMATCH),
            ValueAccessPath.fromSegments(listOf(ValuePathSegment.MapEntryComponent(keyOrigin, MapEntrySlot.VALUE))),
        )
        val value = ReviewedEntryReadFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.mapPreservedKey(value.source, value.input.binding),
        )
    }

    @Test
    fun `whole map input stays unsupported until its derived content relationship exists`() {
        val f = ReviewedMapEntryInputFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackInput(f.receiver(path = ValueAccessPath.Whole), f.binding, f.formal()),
        )
    }
}
