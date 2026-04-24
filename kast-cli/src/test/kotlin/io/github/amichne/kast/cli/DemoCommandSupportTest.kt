package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

        val summary = DemoCommandSupport()
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
    fun `presentation models the live kotter shell from semantic results`() {
        val report = sampleReport(
            affectedFiles = listOf(
                tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/CliService.kt"),
                tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt"),
                tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt"),
                tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/WorkspaceRuntimeManager.kt"),
            ),
        )

        val presentation = DemoCommandSupport().presentationFor(report)

        assertEquals("references", presentation.scenario.initialOperationId)
        assertEquals(listOf("references", "rename", "callers"), presentation.operations.map { it.id })
        assertEquals(
            "kast references --symbol io.github.amichne.kast.cli.CliService",
            presentation.operation("references").query,
        )
        assertEquals(
            "kast rename --symbol io.github.amichne.kast.cli.CliService --new-name CliServiceRenamed --dry-run",
            presentation.operation("rename").query,
        )
        assertEquals(
            listOf("CliService.kt", "KastCli.kt", "+2 more"),
            presentation.operation("rename").branches.map { it.header },
        )

        val referencesScenario = presentation.scenario.operation("references")
        assertEquals(listOf("resolve", "search", "summarize"), referencesScenario.phases)
        assertTrue(
            referencesScenario.events.filterIsInstance<io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent.Line>()
                .any { it.text.contains("semantic references 1") },
        )

        val callersScenario = presentation.scenario.operation("callers")
        assertNotNull(callersScenario.events.filterIsInstance<io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent.Milestone>().lastOrNull())
        assertTrue(
            callersScenario.events.filterIsInstance<io.github.amichne.kast.cli.demo.KotterDemoScenarioEvent.Line>()
                .any { it.text.contains("incoming callers 2") },
        )
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

    private fun sampleReport(affectedFiles: List<Path> = listOf(tempDir.resolve("src/main/kotlin/io/github/amichne/kast/cli/CliService.kt"))): DemoReport {
        val selectedSymbol = demoSymbol("io.github.amichne.kast.cli.CliService")
        val searchScope = SearchScope(
            visibility = SymbolVisibility.PUBLIC,
            scope = SearchScopeKind.DEPENDENT_MODULES,
            exhaustive = true,
            candidateFileCount = 12,
            searchedFileCount = 12,
        )
        val affectedFileStrings = affectedFiles.map(Path::toString)
        return DemoReport(
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
                edits = affectedFileStrings.mapIndexed { index, filePath ->
                    TextEdit(filePath, index, index + 10, "CliServiceRenamed")
                },
                fileHashes = affectedFileStrings.map { filePath -> FileHash(filePath, "abc123") },
                affectedFiles = affectedFileStrings,
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
    }
}
