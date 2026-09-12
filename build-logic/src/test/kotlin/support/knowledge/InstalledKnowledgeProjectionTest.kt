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
                        declarationPath = "Outcome",
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
        assertTrue(module.contains("\"declarationPath\":\"Outcome\""))
        assertTrue(card.contains(documentation.replace("\n", "\\n")))
        assertTrue(card.contains("guides/root.json"))
        assertTrue(card.contains("guides/kernel.json"))
        assertFalse(card.contains("nested guidance"))
        assertTrue(manifest.contains("KOTLIN_PSI_SYNTAX"))
        assertTrue(manifest.contains("NO_TYPE_RESOLUTION"))
    }

    @Test
    fun `same signature under different owners has distinct identities`() {
        val common = InstalledKnowledgeDeclarationInput(
            projectPath = ":kernel",
            sourcePath = "kernel/src/main/kotlin/example/Owners.kt",
            declarationPath = "First.read",
            kind = "function",
            name = "read",
            signature = "fun read(): String",
            documentation = "Read.",
            governingGuidePaths = listOf("AGENTS.md"),
        )
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                declarationEvidence = "KOTLIN_PSI_SYNTAX",
                declarationLimitations = emptyList(),
                modules = listOf(InstalledKnowledgeModuleInput(":kernel", "kernel", listOf("AGENTS.md"))),
                guides = listOf(InstalledKnowledgeGuideInput("AGENTS.md", ".", "root")),
                declarations = listOf(common, common.copy(declarationPath = "Second.read")),
            ),
        )

        val complete = assertIs<InstalledKnowledgeProjectionResult.Complete>(result)
        assertEquals(2, complete.files.keys.count { "/declarations/" in it })
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
                        declarationPath = "X",
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
