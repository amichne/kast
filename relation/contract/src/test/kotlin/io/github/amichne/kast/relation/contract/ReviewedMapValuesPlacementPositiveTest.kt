package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedMapValuesPlacementPositiveTest {
    @Test
    fun `mapValues returned producer occupies output value and preserves nested suffix`() {
        val f = ReviewedMapValuesPlacementFixture()
        val key = Any()
        val value = Any()
        val oracle = mapOf(key to Any()).mapValues { value }
        assertSame(key, oracle.keys.single())
        assertSame(value, oracle.values.single())
        val nested = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(f.base.field))
        val outcome = f.read { binding -> f.returned(binding, nested) }
        assertTrue(outcome is MapValuesPlacementOutcome.Observed, "Missing mapValues placement: $outcome")
        val transfer = (outcome as MapValuesPlacementOutcome.Observed).transfer
        val path = transfer.target.valuePath.segments
        assertTrue(path.first() is ValuePathSegment.MapEntryComponent)
        assertEquals(MapEntrySlot.VALUE, (path.first() as ValuePathSegment.MapEntryComponent).slot)
        assertEquals(nested.segments, path.drop(1))
        assertEquals("MAP_VALUES", (transfer.binding as ReviewedCallbackValueBinding).selected.operation.name)
        assertEquals(f.base.occurrence.range, transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(ValueExecutionOwner.declaration(f.base.enclosing), transfer.target.executionOwner)
    }

    @Test
    fun `mapValues placement keeps previous conditions and cannot become an element path`() {
        val f = ReviewedMapValuesPlacementFixture()
        val outcome = f.read { binding ->
            val source = f.returned(binding)
            val cast =
                ValueCondition.CastSuccess.fromCompiler(
                        source,
                        source.range,
                        ValueCastKind.UNSAFE,
                        CanonicalCompilerType.fromCompiler("kotlin/Any").flowRefined(),
                        CanonicalCompilerType.fromCompiler("kast/repro/ClientValue").flowRefined(),
                    )
                    .flowRefined()
            source.withState(conditions = source.conditions.requiring(cast).flowRefined()).flowRefined()
        }
        assertTrue(outcome is MapValuesPlacementOutcome.Observed, "Missing mapValues placement: $outcome")
        val transfer = (outcome as MapValuesPlacementOutcome.Observed).transfer
        assertEquals(
            transfer.source.conditions.facts,
            transfer.target.conditions.facts.take(transfer.source.conditions.facts.size),
        )
        assertEquals(transfer.source.conditions.facts.size + 1, transfer.target.conditions.facts.size)
        assertTrue(transfer.target.conditions.facts.last() is ValueCondition.ReviewedMappedKeySurvives)
        assertTrue(transfer.target.valuePath.segments.first() is ValuePathSegment.MapEntryComponent)
        assertEquals(f.base.fieldPath.segments, transfer.target.valuePath.segments.drop(1))
    }
}
