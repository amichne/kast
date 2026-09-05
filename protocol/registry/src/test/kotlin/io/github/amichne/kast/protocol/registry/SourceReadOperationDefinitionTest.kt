package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceReadOperationDefinitionTest {
    @Test
    fun `source read is one public bounded qualified IntelliJ source operation`() {
        val definition = CanonicalOperationDefinitions.sourceRead

        assertEquals(CanonicalOperation.SOURCE_READ, definition.operation)
        assertEquals(OperationLane.SCOPED_SEMANTIC_READ, definition.lane)
        assertEquals(OperationEffect.INTELLIJ_READ, definition.effect)
        assertEquals(OperationCost.BOUNDED_READ, definition.cost)
        assertEquals(OperationScope.SOURCE, definition.scope)
        assertEquals(CompletenessPolicy.QUALIFIED_ALLOWED, definition.completeness)
        assertEquals(HostedExposure.PUBLIC, definition.hostedExposure)
        assertEquals("kast.source.read.v3", definition.schema.value)
        assertTrue(definition in CanonicalOperationDefinitions.all)
    }
}
