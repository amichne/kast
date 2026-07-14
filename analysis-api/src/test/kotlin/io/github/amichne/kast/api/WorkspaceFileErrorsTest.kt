package io.github.amichne.kast.api

import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceFileErrorsTest {
    @Test
    fun `stale inventory is a retryable conflict`() {
        val exception = WorkspaceInventoryStaleException()

        assertEquals(409, exception.statusCode)
        assertEquals("STALE_WORKSPACE_INVENTORY", exception.errorCode)
        assertTrue(exception.retryable)
        assertEquals(emptyMap<String, String>(), exception.details)
    }

    @Test
    fun `invalid cursor exposes only its typed handle scope`() {
        InvalidWorkspaceFileCursorScope.entries.forEach { scope ->
            val exception = InvalidWorkspaceFileCursorException(scope)

            assertEquals(400, exception.statusCode)
            assertEquals("INVALID_WORKSPACE_FILE_CURSOR", exception.errorCode)
            assertFalse(exception.retryable)
            assertEquals(mapOf("scope" to scope.name), exception.details)
        }
    }

    @Test
    fun `incomplete project model preserves its typed retryable reason`() {
        WorkspaceProjectModelIncompleteReason.entries.forEach { reason ->
            val exception = WorkspaceProjectModelIncompleteException(reason)

            assertEquals(503, exception.statusCode)
            assertEquals("WORKSPACE_PROJECT_MODEL_INCOMPLETE", exception.errorCode)
            assertTrue(exception.retryable)
            assertEquals(mapOf("reason" to reason.name), exception.details)
        }
    }
}
