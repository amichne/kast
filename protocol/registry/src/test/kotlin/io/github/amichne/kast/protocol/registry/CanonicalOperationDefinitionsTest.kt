package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalOperationDefinitionsTest {
    @Test
    fun `production registry owns exactly eleven distinct typed operation definitions`() {
        val definitions = CanonicalOperationDefinitions.all
        val expectedIds = listOf(
            "index.sync",
            "topology.build",
            "symbol.discover",
            "symbol.inspect",
            "source.read",
            "relation.read",
            "traversal.run",
            "diagnostic.check",
            "change.plan",
            "change.apply",
            "change.recover",
        )

        assertEquals(expectedIds, CanonicalOperation.entries.map { it.id.value })
        assertEquals(CanonicalOperation.entries, definitions.map { it.operation })
        assertEquals(11, definitions.map { it.requestType }.toSet().size)
        assertEquals(11, definitions.map { it.resultType }.toSet().size)
        assertEquals(11, definitions.map { it.qualificationType }.toSet().size)
        assertEquals(11, definitions.map { it.rejectionType }.toSet().size)
        assertEquals(11, definitions.map { it.schema }.toSet().size)
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
