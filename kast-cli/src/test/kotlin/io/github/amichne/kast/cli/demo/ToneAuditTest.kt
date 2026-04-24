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
import io.github.amichne.kast.cli.DemoCommandSupport
import io.github.amichne.kast.cli.DemoReport
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ToneAuditTest {
    private val root = Path.of("/project")

    private fun loc(file: String, line: Int, preview: String = "val x = 1") = Location(
        filePath = file, startOffset = 0, endOffset = preview.length,
        startLine = line, startColumn = 1, preview = preview,
    )

    private fun sym(fqName: String, file: String = "/project/src/${fqName.substringAfterLast('.')}.kt") = Symbol(
        fqName = fqName, kind = SymbolKind.CLASS,
        location = loc(file, 1, "class ${fqName.substringAfterLast('.')}"),
        visibility = SymbolVisibility.PUBLIC, containingDeclaration = fqName.substringBeforeLast('.', ""),
    )

    private fun makeReport(): DemoReport {
        val selected = sym("com.example.Foo")
        val scope = SearchScope(
            visibility = SymbolVisibility.PUBLIC, scope = SearchScopeKind.DEPENDENT_MODULES,
            exhaustive = true, candidateFileCount = 60, searchedFileCount = 50,
        )
        return DemoReport(
            workspaceRoot = root,
            selectedSymbol = selected,
            textSearch = DemoTextSearchSummary(
                totalMatches = 20, likelyCorrect = 15, ambiguous = 0, falsePositives = 5,
                filesTouched = 10, categoryCounts = emptyMap(), sampleMatches = emptyList(),
            ),
            resolvedSymbol = selected,
            references = ReferencesResult(
                declaration = selected,
                references = listOf(
                    loc("/project/src/Bar.kt", 10, "Foo()"),
                    loc("/project/src/Baz.kt", 20, "val f: Foo"),
                ),
                searchScope = scope,
            ),
            rename = RenameResult(
                edits = listOf(TextEdit("/project/src/Foo.kt", 0, 3, "FooRenamed")),
                fileHashes = listOf(FileHash("/project/src/Foo.kt", "abc123")),
                affectedFiles = listOf("/project/src/Foo.kt"),
                searchScope = scope,
            ),
            callHierarchy = CallHierarchyResult(
                root = CallNode(symbol = selected, children = emptyList()),
                stats = CallHierarchyStats(
                    totalNodes = 1, totalEdges = 0, truncatedNodes = 0, maxDepthReached = 0,
                    timeoutReached = false, maxTotalCallsReached = false, maxChildrenPerNodeReached = false,
                    filesVisited = 1,
                ),
            ),
        )
    }

    private fun lineEvents(operationId: String): List<KotterDemoScenarioEvent.Line> {
        val presentation = DemoCommandSupport().presentationFor(makeReport())
        return presentation.scenario.operation(operationId)
            .events.filterIsInstance<KotterDemoScenarioEvent.Line>()
    }

    @Test
    fun `grep baseline line uses ERROR tone`() {
        val grepLine = lineEvents("references").first { it.text.contains("grep baseline") }
        assertTrue(grepLine.tone == KotterDemoStreamTone.ERROR, "grep baseline should be ERROR, got ${grepLine.tone}")
    }

    @Test
    fun `reference preview lines use DETAIL tone with codePreview`() {
        val refLines = lineEvents("references")
            .filter { it.text.contains(".kt:") && !it.text.contains("grep") && !it.text.contains("declaration") }
        assertTrue(refLines.isNotEmpty(), "expected reference preview lines")
        refLines.forEach { line ->
            assertTrue(line.tone == KotterDemoStreamTone.DETAIL, "reference preview should be DETAIL, got ${line.tone}: ${line.text}")
            assertTrue(line.codePreview != null, "reference preview should have codePreview: ${line.text}")
        }
    }

    @Test
    fun `compare against grep line uses ERROR tone`() {
        val grepLine = lineEvents("rename").first { it.text.contains("compare against grep") }
        assertTrue(grepLine.tone == KotterDemoStreamTone.ERROR, "compare-grep should be ERROR, got ${grepLine.tone}")
    }

    @Test
    fun `grep cannot recover caller identity uses ERROR tone`() {
        val grepLine = lineEvents("callers").first { it.text.contains("grep cannot recover") }
        assertTrue(grepLine.tone == KotterDemoStreamTone.ERROR, "grep-cannot-recover should be ERROR, got ${grepLine.tone}")
    }

    @Test
    fun `semantic plan avoids line uses CONFIRMED tone`() {
        val avoidsLine = lineEvents("rename").first { it.text.contains("semantic plan avoids") }
        assertTrue(avoidsLine.tone == KotterDemoStreamTone.CONFIRMED, "semantic-plan-avoids should be CONFIRMED, got ${avoidsLine.tone}")
    }
}
