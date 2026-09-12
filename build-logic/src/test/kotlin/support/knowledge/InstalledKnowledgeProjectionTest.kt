package support.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstalledKnowledgeProjectionTest {
    @Test
    fun `module index stays shallow while declaration owns full documentation`() {
        val documentation = "Caller-facing summary.\n\nDetailed contract that must remain on the declaration card."
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                modules = listOf(
                    InstalledKnowledgeModuleInput(":kernel", "kernel", listOf("AGENTS.md", "kernel/AGENTS.md")),
                ),
                guides = listOf(
                    InstalledKnowledgeGuideInput("AGENTS.md", ".", "root guidance"),
                    InstalledKnowledgeGuideInput("kernel/AGENTS.md", "kernel", "kernel guidance"),
                ),
                declarations = listOf(
                    InstalledKnowledgeDeclarationInput(
                        projectPath = ":kernel",
                        sourcePath = "kernel/src/main/kotlin/example/Outcome.kt",
                        kind = "interface",
                        name = "Outcome",
                        signature = "sealed interface Outcome<out T>",
                        documentation = documentation,
                    ),
                ),
            ),
        )

        val complete = assertIs<InstalledKnowledgeProjectionResult.Complete>(result)
        val module = requireNotNull(complete.files["modules/kernel/index.json"])
        val card = complete.files.entries.single { it.key.contains("/declarations/") }.value
        assertFalse(module.contains("Detailed contract"))
        assertFalse(module.contains("sealed interface Outcome"))
        assertTrue(module.contains("Caller-facing summary."))
        assertTrue(card.contains(documentation.replace("\n", "\\n")))
        assertTrue(card.contains("guides/root.json"))
        assertTrue(card.contains("guides/kernel.json"))
    }

    @Test
    fun `unknown declaration module rejects instead of dropping declaration`() {
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                modules = emptyList(),
                guides = emptyList(),
                declarations = listOf(
                    InstalledKnowledgeDeclarationInput(
                        projectPath = ":unknown",
                        sourcePath = "x.kt",
                        kind = "class",
                        name = "X",
                        signature = "class X",
                        documentation = "X",
                    ),
                ),
            ),
        )

        val rejected = assertIs<InstalledKnowledgeProjectionResult.Rejected>(result)
        assertEquals(
            listOf(InstalledKnowledgeProjectionFailure.UnknownDeclarationModule(":unknown", "x.kt")),
            rejected.failures,
        )
    }
}
