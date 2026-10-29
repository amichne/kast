package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedDistinctStringKeysTest {
    @Test
    fun `duplicate constants cannot discharge entry uniqueness`() {
        val f = ReviewedDistinctKeyFixture()
        val input = f.input(listOf("same", "same"))
        assertEquals(
            ReviewedDistinctKeyFailure.DUPLICATE_KEYS,
            ReviewedDistinctStringKeys.admit(input, f.key(), f.receiver).reviewedFailure(),
        )
        val conditional = ReviewedValueTransfer.associatedReturn(f.returned, f.positions.callback).flowRefined()
        assertEquals(
            1,
            conditional.target.conditions.facts.filterIsInstance<ValueCondition.ReviewedEntryKeyUnique>().size,
        )
        assertEquals(listOf("last"), listOf("same" to "first", "same" to "last").toMap().values.toList())
    }

    @Test
    fun `complete inventory rejects missing surplus and reordered observations`() {
        val f = ReviewedDistinctKeyFixture()
        val args = f.arguments(listOf("a", "b"))
        for (invalid in listOf(args.take(1), args + args.last(), args.reversed())) {
            assertEquals(
                ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH,
                ReviewedStringListInput.fromCompiler(f.varargFormal, f.artifact, 2, f.ranges, invalid)
                    .reviewedFailure(),
            )
        }
        assertEquals(
            ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH,
            ReviewedStringListInput.fromCompiler(f.varargFormal, f.artifact, 1, f.ranges.take(1), args.take(1))
                .reviewedFailure(),
        )
    }

    @Test
    fun `wrong artifact epoch and argument range reject before distinctness`() {
        val f = ReviewedDistinctKeyFixture()
        assertEquals(
            ReviewedDistinctKeyFailure.ARTIFACT_MISMATCH,
            ReviewedStringListArtifact.admit(f.varargFormal, f.base.digest("0".repeat(64)), f.artifact.sources)
                .reviewedFailure(),
        )
        assertEquals(
            ReviewedDistinctKeyFailure.ARGUMENT_RANGE_MISMATCH,
            ReviewedStringVarargArgument.fromCompiler(f.varargFormal, f.base.position(0), flowRange(440, 441), "a")
                .reviewedFailure(),
        )
        val foreign = ReviewedDistinctKeyFixture(2)
        assertEquals(
            ReviewedDistinctKeyFailure.BASIS_MISMATCH,
            ReviewedDistinctStringKeys.admit(f.input(), foreign.key(), f.receiver).reviewedFailure(),
        )
    }

    @Test
    fun `wrong tuple slot and extra callback statements reject direct key`() {
        val f = ReviewedDistinctKeyFixture()
        val wrong = f.keyUse(ValueRole.Argument(f.origin.binding.invocation, f.base.position(0)))
        assertEquals(
            ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH,
            ReviewedDirectFormalKey.fromCompiler(
                    f.positions.callback,
                    f.formal,
                    f.origin,
                    wrong,
                    listOf(f.returned.range),
                )
                .reviewedFailure(),
        )
        assertEquals(
            ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED,
            ReviewedDirectFormalKey.fromCompiler(
                    f.positions.callback,
                    f.formal,
                    f.origin,
                    f.keyUse(),
                    listOf(flowRange(438, 439), f.returned.range),
                )
                .reviewedFailure(),
        )
    }

    @Test
    fun `complete distinct constants preserve proof on entry and discharge only uniqueness`() {
        val f = ReviewedDistinctKeyFixture()
        val proof = ReviewedDistinctStringKeys.admit(f.input(), f.key(), f.receiver).flowRefined()
        val transfer = ReviewedValueTransfer.associatedReturn(f.returned, f.positions.callback, proof).flowRefined()
        assertTrue(transfer.target.conditions.facts.none { it is ValueCondition.ReviewedEntryKeyUnique })
        val component = transfer.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
        assertEquals(proof, (component.entry.keyEvidence as ReviewedEntryKeyEvidence.Distinct).proof)
        assertEquals(MapEntrySlot.VALUE, component.slot)
        assertEquals(listOf("first", "second"), listOf("a" to "first", "b" to "second").toMap().values.toList())
        assertTrue(transfer.retainedBytes > f.returned.retainedBytes + proof.retainedBytes)
        val duplicate = ReviewedValueTransfer.associatedReturn(f.returned, f.positions.callback, proof).flowRefined()
        assertEquals(transfer, duplicate)
        assertEquals(transfer.hashCode(), duplicate.hashCode())
    }

    @Test
    fun `input copies mutable observations and equality preserves literal values`() {
        val f = ReviewedDistinctKeyFixture()
        val args = f.arguments(listOf("a", "b")).toMutableList()
        val ranges = f.ranges.toMutableList()
        val input = ReviewedStringListInput.fromCompiler(f.varargFormal, f.artifact, 2, ranges, args).flowRefined()
        args.clear()
        ranges.clear()
        assertEquals(listOf("a", "b"), input.arguments.map { it.value })
        assertEquals(f.input(), input)
        assertEquals(f.input().hashCode(), input.hashCode())
        assertNotEquals(f.input(listOf("a", "c")), input)
        assertTrue(input.retainedBytes < f.input(listOf("a".repeat(100), "b")).retainedBytes)
    }

    @Test
    fun `distinct proof cannot authorize another tuple return`() {
        val f = ReviewedDistinctKeyFixture()
        val proof = ReviewedDistinctStringKeys.admit(f.input(), f.key(), f.receiver).flowRefined()
        val other = f.positions.construct(PairSlot.SECOND, 447)
        val returned = f.returned.withState(valuePath = other.target.valuePath).flowRefined()
        assertTrue(ReviewedValueTransfer.associatedReturn(returned, f.positions.callback, proof) is Refinement.Rejected)
    }

    @Test
    fun `wrong overload selection and foreign mapped formal reject`() {
        val f = ReviewedDistinctKeyFixture()
        val wrong = f.base.target("kotlin.collections.listOf", null, listOf("T"), 2)
        assertEquals(
            ReviewedDistinctKeyFailure.CALLABLE_MISMATCH,
            ReviewedVarargFormal.fromCompiler(f.invocation, wrong, f.base.position(0)).reviewedFailure(),
        )
        val foreign = ReviewedDistinctKeyFixture(2)
        assertEquals(
            ReviewedDistinctKeyFailure.FORMAL_MISMATCH,
            ReviewedStringListInput.fromCompiler(
                    f.varargFormal,
                    f.artifact,
                    2,
                    f.ranges,
                    foreign.arguments(listOf("a", "b")),
                )
                .reviewedFailure(),
        )
        assertEquals(
            ReviewedDistinctKeyFailure.FORMAL_MISMATCH,
            ReviewedVarargFormal.fromCompiler(f.invocation, f.varargFormal.target, f.base.position(1))
                .reviewedFailure(),
        )
    }

    @Test
    fun `distinct proof preserves prior conditions sharing and canonical identity`() {
        val f = ReviewedDistinctKeyFixture()
        val proof = ReviewedDistinctStringKeys.admit(f.input(), f.key(), f.receiver).flowRefined()
        val cast =
            ValueCondition.CastSuccess.fromCompiler(
                    f.returned,
                    f.returned.range,
                    ValueCastKind.UNSAFE,
                    CanonicalCompilerType.fromCompiler("kotlin/Any").flowRefined(),
                    CanonicalCompilerType.fromCompiler("kotlin/Pair").flowRefined(),
                )
                .flowRefined()
        val source =
            f.returned.withState(conditions = f.returned.conditions.requiring(cast).flowRefined()).flowRefined()
        val transfer = ReviewedValueTransfer.associatedReturn(source, f.positions.callback, proof).flowRefined()
        assertEquals(listOf(cast), transfer.target.conditions.facts)
        val step =
            ValueFlowStep.fromEvidence(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    f.base.domain(bytes = 8_388_608),
                    RelationWorkCount.parse(1).flowRefined(),
                    listOf(transfer),
                )
                .flowRefined()
        val shared = step.shareCallableEvidence(listOf(step))
        assertEquals(listOf(transfer), shared.reviewedTransfers)
        assertEquals(step.retainedBytes, shared.retainedBytes)
        val changed = ReviewedDistinctStringKeys.admit(f.input(listOf("a", "c")), f.key(), f.receiver).flowRefined()
        val changedTransfer =
            ReviewedValueTransfer.associatedReturn(source, f.positions.callback, changed).flowRefined()
        assertNotEquals(transfer.target.identity, changedTransfer.target.identity)
        assertNotEquals(buildString { valueState(transfer.target) }, buildString { valueState(changedTransfer.target) })
    }

    @Test
    fun `another association invocation cannot consume complete literals`() {
        val f = ReviewedDistinctKeyFixture()
        val call = (f.receiver.role as ValueRole.Receiver).call
        val other = ValueInvocation.fromCompiler(f.base.enclosing, flowRange(420, 459), call.callable).flowRefined()
        val receiver =
            ValueSite.fromCompiler(
                    f.base.enclosing,
                    f.receiver.range,
                    ValueRole.Receiver(other, ValueReceiverKind.EXTENSION),
                )
                .flowRefined()
        assertEquals(
            ReviewedDistinctKeyFailure.INVOCATION_MISMATCH,
            ReviewedDistinctStringKeys.admit(f.input(), f.key(), receiver).reviewedFailure(),
        )
    }
}
