package io.github.amichne.kast.cli.demo

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
import io.github.amichne.kast.cli.DemoReport
import io.github.amichne.kast.cli.DemoTextMatch
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DualPaneRoundBuilderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `references round contrasts grep matches with typed references`() {
        val report = sampleReport()

        val round = buildReferencesRound(report, report.textSearch)

        assertEquals("References", round.title)
        assertEquals(4, round.leftLines.size)
        assertTrue(round.leftFooter.contains("4 hits"))
        assertTrue(round.rightFooter.contains("2 refs"))
        assertTrue(round.scoreboard.any { it.metric == "Type information" && it.isNewCapability })
        assertTrue(round.scoreboard.any { it.metric == "Scope proof" && it.isNewCapability })
    }

    @Test
    fun `rename round shows only unsafe grep rewrites against hash guarded edits`() {
        val report = sampleReport()

        val round = buildRenameRound(report, report.textSearch)

        assertEquals(3, round.leftLines.size)
        assertEquals(2, round.rightLines.size)
        assertTrue(round.leftFooter.contains("3 blind edits"))
        assertTrue(round.rightLines.any { it.text.contains("SHA-256 abcdef123456") })
        assertTrue(round.scoreboard.single { it.metric == "Rename safety" }.isNewCapability)
    }

    @Test
    fun `call graph round synthesizes caller grep noise through injected text search`() {
        val report = sampleReport()

        val round = buildCallGraphRound(
            report = report,
            workspaceRoot = tempDir,
            verbose = true,
            textSearchOf = { caller ->
                textSearch(
                    DemoTextMatch(tempDir.resolve("$caller.kt").toString(), 7, "fun $caller()", DemoTextMatchCategory.SUBSTRING),
                    DemoTextMatch(tempDir.resolve("${caller}Test.kt").toString(), 9, "\"$caller\"", DemoTextMatchCategory.STRING),
                )
            },
        )

        assertEquals("Call Graph", round.title)
        assertEquals(2, round.leftLines.size)
        assertTrue(round.leftFooter.contains("2 hits across 1 names"))
        assertTrue(round.rightLines.any { it.text.contains("run") })
        assertTrue(round.scoreboard.single().isNewCapability)
    }

    private fun sampleReport(): DemoReport {
        val selected = symbol("io.example.ExecuteService.execute", SymbolKind.FUNCTION, tempDir.resolve("ExecuteService.kt"))
        val references = listOf(
            location(tempDir.resolve("ExecuteService.kt"), 12, "execute()"),
            location(tempDir.resolve("ExecuteServiceTest.kt"), 34, "service.execute()"),
        )
        val scope = SearchScope(
            visibility = SymbolVisibility.PUBLIC,
            scope = SearchScopeKind.DEPENDENT_MODULES,
            exhaustive = true,
            candidateFileCount = 9,
            searchedFileCount = 9,
        )
        return DemoReport(
            workspaceRoot = tempDir,
            selectedSymbol = selected,
            textSearch = textSearch(
                DemoTextMatch(tempDir.resolve("ExecuteService.kt").toString(), 12, "execute()", DemoTextMatchCategory.LIKELY_CORRECT),
                DemoTextMatch(tempDir.resolve("Imports.kt").toString(), 3, "import io.example.execute", DemoTextMatchCategory.IMPORT),
                DemoTextMatch(tempDir.resolve("Notes.kt").toString(), 5, "// execute later", DemoTextMatchCategory.COMMENT),
                DemoTextMatch(tempDir.resolve("Strings.kt").toString(), 6, "\"execute\"", DemoTextMatchCategory.STRING),
            ),
            resolvedSymbol = selected,
            references = ReferencesResult(declaration = selected, references = references, searchScope = scope),
            rename = RenameResult(
                edits = listOf(TextEdit(tempDir.resolve("ExecuteService.kt").toString(), 10, 17, "executeRenamed")),
                fileHashes = listOf(FileHash(tempDir.resolve("ExecuteService.kt").toString(), "abcdef1234567890")),
                affectedFiles = listOf(tempDir.resolve("ExecuteService.kt").toString()),
                searchScope = scope,
            ),
            callHierarchy = CallHierarchyResult(
                root = CallNode(
                    symbol = selected,
                    children = listOf(
                        CallNode(
                            symbol = symbol("io.example.ExecuteController.run", SymbolKind.FUNCTION, tempDir.resolve("ExecuteController.kt")),
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

    private fun textSearch(vararg matches: DemoTextMatch): DemoTextSearchSummary {
        val categoryCounts = DemoTextMatchCategory.entries.associateWith { category ->
            matches.count { it.category == category }
        }
        return DemoTextSearchSummary(
            totalMatches = matches.size,
            likelyCorrect = matches.count { it.category == DemoTextMatchCategory.LIKELY_CORRECT },
            ambiguous = matches.count { it.category == DemoTextMatchCategory.IMPORT },
            falsePositives = matches.count {
                it.category == DemoTextMatchCategory.COMMENT ||
                    it.category == DemoTextMatchCategory.STRING ||
                    it.category == DemoTextMatchCategory.SUBSTRING
            },
            filesTouched = matches.map(DemoTextMatch::filePath).distinct().size,
            categoryCounts = categoryCounts,
            sampleMatches = matches.toList(),
        )
    }

    private fun symbol(fqName: String, kind: SymbolKind, filePath: Path): Symbol =
        Symbol(
            fqName = fqName,
            kind = kind,
            location = location(filePath, 1, "fun ${fqName.substringAfterLast('.')}()"),
            visibility = SymbolVisibility.PUBLIC,
            containingDeclaration = fqName.substringBeforeLast('.', ""),
        )

    private fun location(filePath: Path, line: Int, preview: String): Location =
        Location(
            filePath = filePath.toString(),
            startOffset = 0,
            endOffset = preview.length,
            startLine = line,
            startColumn = 1,
            preview = preview,
        )
}
