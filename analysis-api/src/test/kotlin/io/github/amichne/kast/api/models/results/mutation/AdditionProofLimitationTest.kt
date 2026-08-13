package io.github.amichne.kast.api

import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceException
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdditionProofLimitationTest {
    @Test
    fun `plan persistence failures retain one finite protocol reason`() {
        AddDeclarationPlanPersistenceFailure.entries.forEach { failure ->
            val exception = AddDeclarationPlanPersistenceException.of(failure)

            assertEquals("ADD_DECLARATION_PLAN_PERSISTENCE_FAILED", exception.errorCode)
            assertEquals(failure, exception.failure)
            assertEquals(failure.name, exception.details["persistenceFailure"])
            if (failure == AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE) {
                assertEquals(503, exception.statusCode)
                assertTrue(exception.retryable)
            } else {
                assertEquals(409, exception.statusCode)
                assertFalse(exception.retryable)
            }
        }
    }

    @Test
    fun `incomplete addition proof has a closed sorted limitation set`() {
        val exception = AdditionProofIncompleteException.of(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            AdditionProofLimitation.GENERATION_CHANGED,
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        )

        assertEquals(
            listOf(
                AdditionProofLimitation.GENERATION_CHANGED,
                AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            ),
            exception.limitations,
        )
        assertEquals("ADDITION_PROOF_INCOMPLETE", exception.errorCode)
        assertTrue(exception.retryable)
        assertThrows(IllegalArgumentException::class.java) {
            AdditionProofIncompleteException.of()
        }
    }
}
