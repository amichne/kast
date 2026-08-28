package io.github.amichne.kast.protocol.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OperationRegistryArtifactTest {
    @Test
    fun `artifact preserves the proven registry order without another identity list`() {
        val registry = CanonicalOperationDefinitions.registry

        assertEquals(
            registry.definitions.map { it.id },
            OperationRegistryArtifact.from(registry).operationIds,
        )
        assertEquals(
            listOf("add-declaration"),
            OperationRegistryArtifact.from(registry).entries
                .single { it.operationId == CanonicalOperationDefinitions.changePlan.id }
                .hostedIntentIds,
        )
    }
}
