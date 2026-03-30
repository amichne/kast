package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.RenameQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntelliJAnalysisBackendRenameTest : IntelliJFixtureTestCase() {
    @Test
    fun `rename planning includes kotlin overrides and usages`() = runBlocking {
        val content = """
            package sample

            open class BaseGreeter {
                open fun greet(): String = "hi"
            }

            class FancyGreeter : BaseGreeter() {
                override fun greet(): String = super.greet()
            }

            fun use(base: BaseGreeter, fancy: FancyGreeter): String =
                base.greet() + fancy.greet()
        """.trimIndent() + "\n"
        val file = writeWorkspaceFile(
            relativePath = "src/main/kotlin/sample/KotlinOverrides.kt",
            content = content,
        )
        val backend = createBackend()

        val result = backend.rename(
            RenameQuery(
                position = FilePosition(
                    filePath = file.toString(),
                    offset = offsetOfNthOccurrence(content, "greet", 1),
                ),
                newName = "welcome",
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertEquals(
            allOccurrenceOffsets(content, "greet"),
            result.edits.map { it.startOffset },
        )
        assertTrue(result.edits.all { it.newText == "welcome" })
    }

    @Test
    fun `rename planning crosses java kotlin overrides and skips non code usages`() = runBlocking {
        val javaContent = """
            package sample;

            public class BaseGreeter {
                public String greet() {
                    return "hi";
                }
            }
        """.trimIndent() + "\n"
        val kotlinContent = """
            package sample

            class FancyGreeter : BaseGreeter() {
                override fun greet(): String = super.greet()
            }

            fun use(greeter: FancyGreeter): String {
                // greet should stay in comments
                val label = "greet should stay in strings"
                return greeter.greet() + label
            }
        """.trimIndent() + "\n"
        val javaFile = writeWorkspaceFile(
            relativePath = "src/main/java/sample/BaseGreeter.java",
            content = javaContent,
        )
        val kotlinFile = writeWorkspaceFile(
            relativePath = "src/main/kotlin/sample/FancyGreeter.kt",
            content = kotlinContent,
        )
        val backend = createBackend()
        val javaDeclarationOffset = offsetOfNthOccurrence(javaContent, "greet", 1)
        val kotlinOverrideOffset = kotlinContent.indexOf("override fun greet") + "override fun ".length
        val kotlinSuperOffset = kotlinContent.indexOf("super.greet") + "super.".length
        val kotlinUsageOffset = kotlinContent.indexOf("greeter.greet") + "greeter.".length
        val commentOffset = kotlinContent.indexOf("// greet") + "// ".length
        val stringOffset = kotlinContent.indexOf("\"greet") + 1

        val result = backend.rename(
            RenameQuery(
                position = FilePosition(
                    filePath = javaFile.toString(),
                    offset = javaDeclarationOffset,
                ),
                newName = "welcome",
            ),
        )

        assertEquals(
            listOf(javaFile.toString(), kotlinFile.toString()),
            result.affectedFiles,
        )
        assertEquals(
            mapOf(
                javaFile.toString() to listOf(javaDeclarationOffset),
                kotlinFile.toString() to listOf(kotlinOverrideOffset, kotlinSuperOffset, kotlinUsageOffset),
            ),
            result.edits
                .groupBy { it.filePath }
                .mapValues { (_, edits) -> edits.map { it.startOffset } },
        )
        assertFalse(
            result.edits.any { it.filePath == kotlinFile.toString() && it.startOffset == commentOffset },
            "comment occurrence should not be renamed",
        )
        assertFalse(
            result.edits.any { it.filePath == kotlinFile.toString() && it.startOffset == stringOffset },
            "string occurrence should not be renamed",
        )
        assertTrue(result.edits.all { it.newText == "welcome" })
    }

    private fun allOccurrenceOffsets(
        content: String,
        needle: String,
    ): List<Int> {
        val offsets = mutableListOf<Int>()
        var fromIndex = 0
        while (true) {
            val match = content.indexOf(needle, fromIndex)
            if (match < 0) {
                return offsets
            }
            offsets += match
            fromIndex = match + needle.length
        }
    }

    private fun offsetOfNthOccurrence(
        content: String,
        needle: String,
        occurrence: Int,
    ): Int = allOccurrenceOffsets(content, needle).elementAt(occurrence - 1)
}
