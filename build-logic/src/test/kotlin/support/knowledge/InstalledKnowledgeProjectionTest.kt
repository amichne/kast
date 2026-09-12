package support.knowledge

import conventions.jsoncontracts.KnowledgeDeclarationKind
import conventions.jsoncontracts.KnowledgeDeclarationEvidence
import conventions.jsoncontracts.KnowledgeDeclarationLimitation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue

class InstalledKnowledgeProjectionTest {
    @Test
    fun `module index stays shallow while declaration owns full documentation`() {
        val documentation = "Caller-facing summary.\n\nDetailed contract that must remain on the declaration card."
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                declarationEvidence = KnowledgeDeclarationEvidence.KOTLIN_PSI_SYNTAX,
                declarationLimitations = listOf(KnowledgeDeclarationLimitation.NO_TYPE_RESOLUTION),
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
                        kind = KnowledgeDeclarationKind.INTERFACE,
                        name = "Outcome",
                        signature = "sealed interface Outcome<out T>",
                        documentation = documentation,
                        governingGuidePaths = listOf("AGENTS.md", "kernel/AGENTS.md"),
                    ),
                ),
            ),
        )

        val complete = assertInstanceOf(InstalledKnowledgeProjectionResult.Complete::class.java, result)
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
            kind = KnowledgeDeclarationKind.FUNCTION,
            name = "read",
            signature = "fun read(): String",
            documentation = "Read.",
            governingGuidePaths = listOf("AGENTS.md"),
        )
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                declarationEvidence = KnowledgeDeclarationEvidence.KOTLIN_PSI_SYNTAX,
                declarationLimitations = emptyList(),
                modules = listOf(InstalledKnowledgeModuleInput(":kernel", "kernel", listOf("AGENTS.md"))),
                guides = listOf(InstalledKnowledgeGuideInput("AGENTS.md", ".", "root")),
                declarations = listOf(common, common.copy(declarationPath = "Second.read")),
            ),
        )

        val complete = assertInstanceOf(InstalledKnowledgeProjectionResult.Complete::class.java, result)
        assertEquals(2, complete.files.keys.count { "/declarations/" in it })
    }

    @Test
    fun `unknown declaration module rejects instead of dropping declaration`() {
        val result = InstalledKnowledgeProjection.render(
            InstalledKnowledgeInput(
                productVersion = "1.0.0",
                sourceRevision = "0123456789012345678901234567890123456789",
                declarationEvidence = KnowledgeDeclarationEvidence.KOTLIN_PSI_SYNTAX,
                declarationLimitations = emptyList(),
                modules = emptyList(),
                guides = emptyList(),
                declarations = listOf(
                    InstalledKnowledgeDeclarationInput(
                        projectPath = ":unknown",
                        sourcePath = "x.kt",
                        declarationPath = "X",
                        kind = KnowledgeDeclarationKind.CLASS,
                        name = "X",
                        signature = "class X",
                        documentation = "X",
                        governingGuidePaths = emptyList(),
                    ),
                ),
            ),
        )

        val rejected = assertInstanceOf(InstalledKnowledgeProjectionResult.Rejected::class.java, result)
        assertEquals(
            listOf(InstalledKnowledgeProjectionFailure.UnknownDeclarationModule(":unknown", "x.kt")),
            rejected.failures,
        )
    }
    @Test
    fun `projection is byte deterministic across input order`() {
        val input = fixture()
        val first = InstalledKnowledgeProjection.render(input)
        val second = InstalledKnowledgeProjection.render(input.copy(guides = input.guides.reversed()))
        assertEquals(first, second)
    }

    @Test
    fun `guide resource collisions reject instead of overwriting content`() {
        val input = fixture()
        val result = InstalledKnowledgeProjection.render(input.copy(
            guides = input.guides + InstalledKnowledgeGuideInput("root/AGENTS.md", "root", "collision"),
        ))
        assertInstanceOf(InstalledKnowledgeProjectionResult.Rejected::class.java, result)
    }

    @Test
    fun `missing governing guidance rejects`() {
        val input = fixture()
        val result = InstalledKnowledgeProjection.render(input.copy(
            modules = input.modules.map { it.copy(governingGuidePaths = emptyList()) },
        ))
        assertInstanceOf(InstalledKnowledgeProjectionResult.Rejected::class.java, result)
    }

    @Test
    fun `oversized generated resources reject before publication`() {
        val input = fixture()
        val result = InstalledKnowledgeProjection.render(input.copy(
            guides = input.guides.map { it.copy(content = "x".repeat(8 * 1024 * 1024)) },
        ))
        assertInstanceOf(InstalledKnowledgeProjectionResult.Rejected::class.java, result)
    }

    private fun fixture() = InstalledKnowledgeInput(
        productVersion = "1.0.0",
        sourceRevision = "0123456789012345678901234567890123456789",
        declarationEvidence = KnowledgeDeclarationEvidence.KOTLIN_PSI_SYNTAX,
        declarationLimitations = KnowledgeDeclarationLimitation.entries,
        modules = listOf(InstalledKnowledgeModuleInput(":kernel", "kernel", listOf("AGENTS.md"))),
        guides = listOf(InstalledKnowledgeGuideInput("AGENTS.md", ".", "Root guide.")),
        declarations = emptyList(),
    )

}
