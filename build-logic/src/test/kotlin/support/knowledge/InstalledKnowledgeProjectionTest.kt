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
                declarationEvidence = "KOTLIN_PSI_SYNTAX",
                declarationLimitations = listOf("NO_TYPE_RESOLUTION"),
                modules = listOf(
                    InstalledKnowledgeModuleInput(":kernel", "kernel", listOf("AGENTS.md", "kernel/AGENTS.md")),
                ),
                guides = listOf(
                    InstalledKnowledgeGuideInput("AGENTS.md", ".", "root guidance"),
                    InstalledKnowledgeGuideInput("kernel/AGENTS.md", "kernel", "kernel guidance"),
                    InstalledKnowledgeGuideInput("kernel/internal/AGENTS.md", "kernel/internal", "nested guidance"),
                ),
                declarations = listOf(
                    InstalledKnowledgeDeclarationInput(
                        projectPath = ":kernel",
                        sourcePath = "kernel/src/main/kotlin/example/Outcome.kt",
                        kind = "interface",
                        name = "Outcome",
                        signature = "sealed interface Outcome<out T>",
                        documentation = documentation,
                        governingGuidePaths = listOf("AGENTS.md", "kernel/AGENTS.md"),
                    ),
                ),
            ),
        )

        val complete = assertIs<InstalledKnowledgeProjectionResult.Complete>(result)
        val manifest = requireNotNull(complete.files["manifest.json"])
        val module = requireNotNull(complete.files["modules/kernel/index.json"])
        val card = complete.files.entries.single { it.key.contains("/declarations/") }.value
        assertFalse(module.contains("Detailed contract"))
        assertFalse(module.contains("sealed interface Outcome"))
        assertTrue(module.contains("Caller-facing summary."))
        assertTrue(card.contains(documentation.replace("\n", "\\n")))
        assertTrue(card.contains("guides/root.json"))
        assertTrue(card.contains("guides/kernel.json"))
        assertFalse(card.contains("nested guidance"))
        assertTrue(manifest.contains("KOTLIN_PSI_SYNTAX"))
        assertTrue(manifest.contains("NO_TYPE_RESOLUTION"))
    }

    @Test
    fun `unknown declaration module rejects instead of dropping declaration`() {
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                declarationEvidence = "KOTLIN_PSI_SYNTAX",
                declarationLimitations = emptyList(),
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
                        governingGuidePaths = emptyList(),
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
