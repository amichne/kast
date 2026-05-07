package io.amichne.konditional.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublishingConventionsTest {
    @Test
    fun `deriveModuleName converts kebab-case artifact ids to title words`() {
        assertEquals("Konditional Runtime", deriveModuleName("konditional-runtime"))
    }

    @Test
    fun `deriveModuleName normalizes underscores and dots`() {
        assertEquals("Config Metadata Core", deriveModuleName("config_metadata.core"))
    }

    @Test
    fun `deriveModuleDescription prefers non-blank project description`() {
        assertEquals(
            "Runtime execution engine",
            deriveModuleDescription(
                moduleName = "Konditional Runtime",
                projectDescription = "  Runtime execution engine  ",
            ),
        )
    }

    @Test
    fun `deriveModuleDescription falls back to module name when description is blank`() {
        assertEquals(
            "Konditional Runtime module",
            deriveModuleDescription(
                moduleName = "Konditional Runtime",
                projectDescription = "   ",
            ),
        )
    }
}
