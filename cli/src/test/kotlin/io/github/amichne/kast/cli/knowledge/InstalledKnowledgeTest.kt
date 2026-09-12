package io.github.amichne.kast.cli.knowledge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

class InstalledKnowledgeTest {
    @TempDir lateinit var root: Path

    @Test
    fun `search returns shallow descriptor and returned resource opens exact card`() {
        writeBundle()
        val reader = InstalledKnowledgeReader(root)

        val search = assertIs<KnowledgeLookup.Complete>(reader.lookup(selection("Outcome")))
        assertContains(search.document.value, "\"declarationPath\":\"Outcome\"")
        assertContains(search.document.value, "modules/kernel/declarations/outcome.json")
        assertContains(search.document.value, "NO_TYPE_RESOLUTION")
        assertEquals(false, search.document.value.contains("Detailed contract"))

        val read =
            assertIs<KnowledgeLookup.Complete>(
                reader.lookup(selection("modules/kernel/declarations/outcome.json"))
            )
        assertContains(read.document.value, "Detailed contract")
        assertContains(read.document.value, "guides/kernel.json")
    }

    @Test
    fun `resource traversal rejects parent escape`() {
        writeBundle()
        val result = InstalledKnowledgeReader(root).lookup(selection("modules/../../outside.json"))
        val rejected = assertIs<KnowledgeLookup.Rejected>(result)
        assertEquals(KnowledgeLookupFailure.RESOURCE_REJECTED, rejected.failure)
    }

    private fun selection(raw: String): KnowledgeSelection = requireNotNull(KnowledgeSelection.parse(raw))

    private fun writeBundle() {
        write(
            "manifest.json",
            """{"schemaVersion":1,"productVersion":"1.0.0","sourceRevision":"0123456789012345678901234567890123456789","declarationEvidence":"KOTLIN_PSI_SYNTAX","declarationLimitations":["NO_TYPE_RESOLUTION"],"modules":[{"projectPath":":kernel","resource":"modules/kernel/index.json"}]}""",
        )
        write(
            "modules/kernel/index.json",
            """{"schemaVersion":1,"projectPath":":kernel","moduleDirectory":"kernel","governingGuides":[{"path":"AGENTS.md","sha256":"sha256:root","resource":"guides/root.json"},{"path":"kernel/AGENTS.md","sha256":"sha256:kernel","resource":"guides/kernel.json"}],"declarations":[{"id":"outcome","declarationPath":"Outcome","name":"Outcome","kind":"interface","summary":"Caller-facing summary.","resource":"modules/kernel/declarations/outcome.json"}]}""",
        )
        write(
            "modules/kernel/declarations/outcome.json",
            """{"schemaVersion":1,"id":"outcome","projectPath":":kernel","sourcePath":"kernel/src/main/kotlin/Outcome.kt","declarationPath":"Outcome","kind":"interface","name":"Outcome","signature":"sealed interface Outcome","documentation":"Caller-facing summary.\\n\\nDetailed contract","governingGuides":["guides/root.json","guides/kernel.json"]}""",
        )
        write("guides/root.json", """{"schemaVersion":1,"path":"AGENTS.md","scopeDirectory":".","sha256":"sha256:root","content":"root"}""")
        write("guides/kernel.json", """{"schemaVersion":1,"path":"kernel/AGENTS.md","scopeDirectory":"kernel","sha256":"sha256:kernel","content":"kernel"}""")
    }

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
