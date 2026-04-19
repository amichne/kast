package io.github.amichne.kast.cli

import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallHierarchyStats
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.SearchScope
import io.github.amichne.kast.api.SearchScopeKind
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.api.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class DemoCommandSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `select symbol delegates to the chooser when no filter is provided`() {
        val expected = demoSymbol("io.github.amichne.kast.cli.CliService")
        val support = DemoCommandSupport(
            symbolChooser = DemoSymbolChooser { candidates -> candidates.last() },
            themeProvider = CliTextTheme::ansi,
        )

        val selected = support.selectSymbol(
            DemoOptions(workspaceRoot = tempDir, symbolFilter = null),
            listOf(demoSymbol("io.github.amichne.kast.cli.KastCli"), expected),
        )

        assertSame(expected, selected)
    }

    @Test
    fun `text search baseline distinguishes likely real matches from grep false positives`() {
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/cli/CliService.kt",
            "package io.github.amichne.kast.cli\nclass CliService\n",
        )
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/cli/Imports.kt",
            "package io.github.amichne.kast.cli\nimport io.github.amichne.kast.cli.CliService\n",
        )
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/cli/Comment.kt",
            "package io.github.amichne.kast.cli\n// CliService appears in a comment\n",
        )
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/cli/StringLiteral.kt",
            """package io.github.amichne.kast.cli
               |fun banner() = println("CliService")
            """.trimMargin(),
        )
        writeKotlinFile(
            "src/main/kotlin/io/github/amichne/kast/cli/Substring.kt",
            "package io.github.amichne.kast.cli\nclass CliServiceFactory\n",
        )

        val summary = DemoCommandSupport(themeProvider = CliTextTheme::ansi)
            .analyzeTextSearch(tempDir, demoSymbol("io.github.amichne.kast.cli.CliService"))

        assertEquals(5, summary.totalMatches)
        assertEquals(1, summary.likelyCorrect)
        assertEquals(1, summary.ambiguous)
        assertEquals(3, summary.falsePositives)
        assertEquals(5, summary.filesTouched)
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.COMMENT))
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.IMPORT))
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.STRING))
        assertEquals(1, summary.categoryCounts.getValue(DemoTextMatchCategory.SUBSTRING))
    }

    @Test
    fun `render prints the semantic comparison summary`() {
        val selectedSymbol = demoSymbol("io.github.amichne.kast.cli.CliService")
        val searchScope = SearchScope(
            visibility = SymbolVisibility.PUBLIC,
            scope = SearchScopeKind.DEPENDENT_MODULES,
            exhaustive = true,
            candidateFileCount = 12,
            searchedFileCount = 12,
        )
        val report = DemoReport(
            workspaceRoot = tempDir,
            selectedSymbol = selectedSymbol,
            textSearch = DemoTextSearchSummary(
                totalMatches = 5,
                likelyCorrect = 1,
                ambiguous = 1,
                falsePositives = 3,
                filesTouched = 5,
                categoryCounts = mapOf(
                    DemoTextMatchCategory.COMMENT to 1,
                    DemoTextMatchCategory.IMPORT to 1,
                    DemoTextMatchCategory.STRING to 1,
                    DemoTextMatchCategory.SUBSTRING to 1,
                ),
                sampleMatches = emptyList(),
            ),
            resolvedSymbol = selectedSymbol.copy(containingDeclaration = "io.github.amichne.kast.cli"),
            references = ReferencesResult(
                declaration = selectedSymbol,
                references = listOf(location(tempDir.resolve("src/test/kotlin/io/github/amichne/kast/cli/KastCliTest.kt"), 18, "CliService()")),
                searchScope = searchScope,
            ),
            rename = RenameResult(
                edits = listOf(TextEdit(selectedSymbol.location.filePath, 0, 10, "CliServiceRenamed")),
                fileHashes = listOf(FileHash(selectedSymbol.location.filePath, "abc123")),
                affectedFiles = listOf(selectedSymbol.location.filePath),
                searchScope = searchScope,
            ),
            callHierarchy = CallHierarchyResult(
                root = CallNode(
                    symbol = selectedSymbol,
                    children = listOf(
                        CallNode(
                            symbol = demoSymbol(
                                fqName = "io.github.amichne.kast.cli.KastCli.run",
                                kind = SymbolKind.FUNCTION,
                                filePath = tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt"),
                                preview = "fun run()",
                            ),
                            children = emptyList(),
                        ),
                    ),
                ),
                stats = CallHierarchyStats(
                    totalNodes = 2,
                    totalEdges = 1,
                    truncatedNodes = 0,
                    maxDepthReached = 1,
                    timeoutReached = false,
                    maxTotalCallsReached = false,
                    maxChildrenPerNodeReached = false,
                    filesVisited = 2,
                ),
            ),
        )

        val output = DemoCommandSupport(themeProvider = CliTextTheme::ansi).render(report)

        assertTrue(output.contains("Act 1"))
        assertTrue(output.contains("Act 2"))
        assertTrue(output.contains("semantic references"))
        assertTrue(output.contains("incoming callers"))
        assertTrue(output.contains("grep + sed"))
        assertTrue(output.contains("why the semantic pass wins"))
    }

    private fun writeKotlinFile(relativePath: String, content: String) {
        val file = tempDir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun demoSymbol(
        fqName: String,
        kind: SymbolKind = SymbolKind.CLASS,
        filePath: Path = tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/${fqName.substringAfterLast('.')}.kt"),
        preview: String = "class ${fqName.substringAfterLast('.')}",
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = location(filePath, 1, preview),
        visibility = SymbolVisibility.PUBLIC,
        containingDeclaration = fqName.substringBeforeLast('.', ""),
    )

    private fun location(filePath: Path, line: Int, preview: String): Location = Location(
        filePath = filePath.toString(),
        startOffset = 0,
        endOffset = preview.length,
        startLine = line,
        startColumn = 1,
        preview = preview,
    )
}
