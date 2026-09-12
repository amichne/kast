package io.github.amichne.kast.cli.knowledge

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledKnowledgeTest {
    @TempDir lateinit var root: Path

    @Test
    fun `search returns shallow descriptor and returned resource opens exact card`() {
        writeBundle()
        val reader = InstalledKnowledgeReader(root.toRealPath())

        val search = assertInstanceOf(KnowledgeLookup.Complete::class.java, reader.lookup(selection("Outcome")))
        assertContains(search.document.value, "\"declarationPath\":\"Outcome\"")
        assertContains(search.document.value, "modules/kernel/declarations/outcome.json")
        assertContains(search.document.value, "NO_TYPE_RESOLUTION")
        assertEquals(false, search.document.value.contains("Detailed contract"))

        val read =
            assertInstanceOf(
                KnowledgeLookup.Complete::class.java,
                reader.lookup(selection("modules/kernel/declarations/outcome.json")),
            )
        assertContains(read.document.value, "Detailed contract")
        assertContains(read.document.value, "guides/kernel.json")
    }

    @Test
    fun `resource traversal rejects parent escape`() {
        writeBundle()
        val result = InstalledKnowledgeReader(root.toRealPath()).lookup(selection("modules/../../outside.json"))
        val rejected = assertInstanceOf(KnowledgeLookup.Rejected::class.java, result)
        assertEquals(KnowledgeLookupFailure.RESOURCE_REJECTED, rejected.failure)
    }

    private fun assertContains(actual: String, expected: String) = assertTrue(expected in actual, actual)

    private fun selection(raw: String): KnowledgeSelection = requireNotNull(KnowledgeSelection.parse(raw))

    private fun writeBundle() {
        write(
            "manifest.json",
            KnowledgeManifestDocument(
                1,
                "1.0.0",
                "0123456789012345678901234567890123456789",
                "KOTLIN_PSI_SYNTAX",
                listOf("NO_TYPE_RESOLUTION"),
                listOf(KnowledgeModuleDescriptor(":kernel", "modules/kernel/index.json")),
            ),
        )
        write(
            "modules/kernel/index.json",
            KnowledgeModuleDocument(
                1,
                ":kernel",
                "kernel",
                listOf(
                    KnowledgeGuideReference("AGENTS.md", "sha256:root", "guides/root.json"),
                    KnowledgeGuideReference("kernel/AGENTS.md", "sha256:kernel", "guides/kernel.json"),
                ),
                listOf(
                    KnowledgeDeclarationDescriptor(
                        "outcome",
                        "Outcome",
                        "Outcome",
                        "interface",
                        "Caller-facing summary.",
                        "modules/kernel/declarations/outcome.json",
                    )
                ),
            ),
        )
        write(
            "modules/kernel/declarations/outcome.json",
            KnowledgeDeclarationDocument(
                1,
                "outcome",
                ":kernel",
                "kernel/src/main/kotlin/Outcome.kt",
                "Outcome",
                "interface",
                "Outcome",
                "sealed interface Outcome",
                "Caller-facing summary.\n\nDetailed contract",
                listOf("guides/root.json", "guides/kernel.json"),
            ),
        )
        write("guides/root.json", KnowledgeGuideDocument(1, "AGENTS.md", ".", "sha256:root", "root"))
        write("guides/kernel.json", KnowledgeGuideDocument(1, "kernel/AGENTS.md", "kernel", "sha256:kernel", "kernel"))
    }

    private inline fun <reified T> write(relative: String, document: T) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, Json.encodeToString(document))
    }
}
