package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalOperationDefinitionsTest {
    @Test
    fun `production registry owns fourteen distinct typed operation definitions`() {
        val definitions = CanonicalOperationDefinitions.all

        assertEquals(CanonicalOperation.entries, definitions.map { it.operation })
        assertEquals(14, definitions.map { it.requestType }.toSet().size)
        assertEquals(14, definitions.map { it.resultType }.toSet().size)
        assertEquals(14, definitions.map { it.qualificationType }.toSet().size)
        assertEquals(14, definitions.map { it.rejectionType }.toSet().size)
        assertEquals(14, definitions.map { it.schema }.toSet().size)
        assertEquals(true, definitions.all { it.schema.value.endsWith(".v2") })
        assertEquals(definitions, CanonicalOperationDefinitions.registry.definitions)
        assertEquals(OperationLane.REGISTERED_LONG_WORK, CanonicalOperationDefinitions.topologyBuild.lane)
        assertEquals(
            OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
            CanonicalOperationDefinitions.topologyBuild.effect,
        )
        assertEquals(OperationLane.REGISTERED_LONG_WORK, CanonicalOperationDefinitions.indexSync.lane)
        assertEquals(
            OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
            CanonicalOperationDefinitions.indexSync.effect,
        )
    }
}
