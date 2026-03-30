package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.SymbolQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntelliJAnalysisBackendDiagnosticsTest : IntelliJFixtureTestCase() {
    @Test
    fun `kotlin diagnostics stay narrow and report unresolved references`() = runBlocking {
        val brokenFile = writeWorkspaceFile(
            relativePath = "src/main/kotlin/sample/BrokenSemantic.kt",
            content = """
                package sample

                fun broken(): String = missing("kast")
            """.trimIndent() + "\n",
        )
        val backend = createBackend()

        val result = backend.diagnostics(
            DiagnosticsQuery(filePaths = listOf(brokenFile.toString())),
        )

        val diagnostic = result.diagnostics.firstOrNull()
        assertNotNull(diagnostic, "expected at least one semantic diagnostic")
        assertEquals(DiagnosticSeverity.ERROR, diagnostic!!.severity)
        assertEquals(brokenFile.toString(), diagnostic.location.filePath)
        assertEquals("UNRESOLVED_REFERENCE", diagnostic.code)
        assertTrue(
            diagnostic.message.contains("Unresolved reference", ignoreCase = true),
            "expected unresolved-reference messaging but got: ${diagnostic.message}",
        )
    }

    @Test
    fun `read endpoints observe uncommitted document changes`() = runBlocking {
        writeWorkspaceFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"

                fun hello(): String = "hello"
            """.trimIndent() + "\n",
        )
        val updatedUseContent = """
            package sample

            fun use(): String = hello()
        """.trimIndent() + "\n"
        val useFile = writeWorkspaceFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        updateDocumentWithoutCommit(useFile, updatedUseContent)
        val backend = createBackend()

        val result = backend.resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = useFile.toString(),
                    offset = updatedUseContent.indexOf("hello"),
                ),
            ),
        )

        assertEquals("sample.hello", result.symbol.fqName)
    }
}
