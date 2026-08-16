package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalOperationDefinitionsTest {
    @Test
    fun `production registry owns eleven distinct typed operation definitions`() {
        val definitions = CanonicalOperationDefinitions.all

        assertEquals(CanonicalOperation.entries, definitions.map { it.operation })
        assertEquals(11, definitions.map { it.requestType }.toSet().size)
        assertEquals(11, definitions.map { it.resultType }.toSet().size)
        assertEquals(11, definitions.map { it.qualificationType }.toSet().size)
        assertEquals(11, definitions.map { it.rejectionType }.toSet().size)
        assertEquals(11, definitions.map { it.schema }.toSet().size)
        assertEquals(definitions, CanonicalOperationDefinitions.registry.definitions)
    }
}
