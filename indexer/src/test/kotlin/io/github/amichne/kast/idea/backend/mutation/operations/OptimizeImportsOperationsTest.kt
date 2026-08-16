package io.github.amichne.kast.idea.backend.mutation.operations

import io.github.amichne.kast.idea.backend.mutation.VerifiedOptimizeImportsOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OptimizeImportsOperationsTest {
    @Test
    fun `verified optimize imports uses an operation specific authority`() {
        assertEquals(
            "VerifiedOptimizeImportsOperations",
            VerifiedOptimizeImportsOperations::class.simpleName,
        )
    }
}
