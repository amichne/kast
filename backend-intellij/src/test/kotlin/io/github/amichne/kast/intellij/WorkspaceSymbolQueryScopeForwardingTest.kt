package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.WorkspaceSymbolQuery
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceSymbolQueryScopeForwardingTest {
    @Test
    fun `withDeclarationScopeRequested forwards true when requested`() {
        val forwarded = WorkspaceSymbolQuery(
            pattern = "greet",
            includeDeclarationScope = true,
        ).withDeclarationScopeRequested { includeDeclarationScope -> includeDeclarationScope }

        assertTrue(forwarded)
    }

    @Test
    fun `withDeclarationScopeRequested forwards false by default`() {
        val forwarded = WorkspaceSymbolQuery(
            pattern = "greet",
        ).withDeclarationScopeRequested { includeDeclarationScope -> includeDeclarationScope }

        assertFalse(forwarded)
    }
}
