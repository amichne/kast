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

    @Test
    fun `unsupported manifest version rejects both search and exact reads`() {
        writeBundle()
        write("manifest.json", read<KnowledgeManifestDocument>("manifest.json").copy(schemaVersion = 2))
        assertRejected("Outcome")
        assertRejected("manifest.json")
    }

    @Test
    fun `unknown extraction evidence rejects`() {
        writeBundle()
        write("manifest.json", read<KnowledgeManifestDocument>("manifest.json").copy(declarationEvidence = "RESOLVED"))
        assertRejected("Outcome")
    }

    @Test
    fun `module index must retain its manifest ownership`() {
        writeBundle()
        write("modules/kernel/index.json", read<KnowledgeModuleDocument>("modules/kernel/index.json").copy(projectPath = ":cli"))
        assertRejected("Outcome")
        assertRejected("modules/kernel/index.json")
    }

    @Test
    fun `exact declaration must be listed and match its descriptor`() {
        writeBundle()
        val card = read<KnowledgeDeclarationDocument>("modules/kernel/declarations/outcome.json")
        write("modules/kernel/declarations/unlisted.json", card)
        assertRejected("modules/kernel/declarations/unlisted.json")
        write("modules/kernel/declarations/outcome.json", card.copy(name = "Unrelated"))
        assertRejected("modules/kernel/declarations/outcome.json")
    }

    @Test
    fun `exact resource must decode its declared contract`() {
        writeBundle()
        write("modules/kernel/declarations/outcome.json", read<KnowledgeManifestDocument>("manifest.json"))
        assertRejected("modules/kernel/declarations/outcome.json")
    }

    @Test
    fun `guide content must match its declared hash`() {
        writeBundle()
        write("guides/kernel.json", read<KnowledgeGuideDocument>("guides/kernel.json").copy(content = "tampered"))
        assertRejected("guides/kernel.json")
    }

    @Test
    fun `symbolic link inside the bundle is rejected`() {
        writeBundle()
        val path = root.resolve("modules/kernel/declarations/outcome.json")
        Files.move(path, root.resolve("original.json"))
        Files.createSymbolicLink(path, root.resolve("original.json"))
        assertRejected("modules/kernel/declarations/outcome.json")
    }

    @Test
    fun `oversized exact resources reject before producing output`() {
        writeBundle()
        val card = read<KnowledgeDeclarationDocument>("modules/kernel/declarations/outcome.json")
        write("modules/kernel/declarations/outcome.json", card.copy(documentation = "x".repeat(8 * 1024 * 1024)))
        assertRejected("modules/kernel/declarations/outcome.json")
    }

    private fun assertRejected(raw: String) {
        assertInstanceOf(KnowledgeLookup.Rejected::class.java, InstalledKnowledgeReader(root.toRealPath()).lookup(selection(raw)))
    }

    private inline fun <reified T> read(relative: String): T = Json.decodeFromString(Files.readString(root.resolve(relative)))

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
