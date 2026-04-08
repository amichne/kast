package io.github.amichne.kast.standalone

import io.github.amichne.kast.standalone.analysis.ImportAnalysis
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ImportAnalysisTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `unused import is detected`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/other/Helper.kt",
            content = """
                package other

                class Helper
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/other/UnusedHelper.kt",
            content = """
                package other

                class UnusedHelper
            """.trimIndent() + "\n",
        )
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Imports.kt",
            content = """
                package sample

                import other.Helper
                import other.UnusedHelper

                fun use(value: Helper): Helper = value
            """.trimIndent() + "\n",
        )

        withSession { session ->
            val result = session.withReadAccess {
                ImportAnalysis.analyzeImports(session.findKtFile(file.toString()))
            }

            assertEquals(listOf("other.Helper"), result.usedImports.mapNotNull { it.importedFqName?.asString() })
            assertEquals(listOf("other.UnusedHelper"), result.unusedImports.mapNotNull { it.importedFqName?.asString() })
            assertTrue(result.missingImports.isEmpty())
        }
    }

    @Test
    fun `alias import is treated as used when alias is referenced`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/other/Helper.kt",
            content = """
                package other

                class Helper
            """.trimIndent() + "\n",
        )
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/AliasImports.kt",
            content = """
                package sample

                import other.Helper as AliasHelper

                fun use(value: AliasHelper): AliasHelper = value
            """.trimIndent() + "\n",
        )

        withSession { session ->
            val result = session.withReadAccess {
                ImportAnalysis.analyzeImports(session.findKtFile(file.toString()))
            }

            assertTrue(result.unusedImports.isEmpty())
            assertEquals(listOf("other.Helper"), result.usedImports.mapNotNull { it.importedFqName?.asString() })
        }
    }

    @Test
    fun `star import is treated as used when imported symbol is referenced`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/other/Helper.kt",
            content = """
                package other

                class Helper
            """.trimIndent() + "\n",
        )
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/StarImports.kt",
            content = """
                package sample

                import other.*

                fun use(value: Helper): Helper = value
            """.trimIndent() + "\n",
        )

        withSession { session ->
            val result = session.withReadAccess {
                ImportAnalysis.analyzeImports(session.findKtFile(file.toString()))
            }

            assertTrue(result.unusedImports.isEmpty())
            assertEquals(listOf("other"), result.usedImports.mapNotNull { it.importedFqName?.asString() })
        }
    }

    @Test
    fun `optimize import edits remove unused imports`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/other/Helper.kt",
            content = """
                package other

                class Helper
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/other/UnusedHelper.kt",
            content = """
                package other

                class UnusedHelper
            """.trimIndent() + "\n",
        )
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/OptimizeImports.kt",
            content = """
                package sample

                import other.Helper
                import other.UnusedHelper

                fun use(value: Helper): Helper = value
            """.trimIndent() + "\n",
        )

        withSession { session ->
            val ktFile = session.withReadAccess { session.findKtFile(file.toString()) }
            val edits = session.withReadAccess { ImportAnalysis.optimizeImportEdits(ktFile) }
            val updatedContent = applyEdit(file.readText(), edits.single())

            assertEquals(1, edits.size)
            assertTrue(updatedContent.contains("import other.Helper"))
            assertTrue(updatedContent.contains("fun use(value: Helper): Helper = value"))
            assertTrue(!updatedContent.contains("import other.UnusedHelper"))
        }
    }

    @Test
    fun `insert import edit adds import after package statement`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/InsertImport.kt",
            content = """
                package sample

                fun use(value: Helper): Helper = value
            """.trimIndent() + "\n",
        )

        withSession { session ->
            val ktFile = session.withReadAccess { session.findKtFile(file.toString()) }
            val edit = session.withReadAccess { checkNotNull(ImportAnalysis.insertImportEdit(ktFile, "other.Helper")) }
            val updatedContent = applyEdit(file.readText(), edit)

            assertEquals("import other.Helper\n", edit.newText)
            assertTrue(updatedContent.startsWith("package sample\nimport other.Helper\n\n"))
        }
    }

    private suspend fun withSession(
        block: suspend (StandaloneAnalysisSession) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { activeSession ->
            block(activeSession)
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun applyEdit(
        content: String,
        edit: io.github.amichne.kast.api.TextEdit,
    ): String = buildString {
        append(content.substring(0, edit.startOffset))
        append(edit.newText)
        append(content.substring(edit.endOffset))
    }
}
