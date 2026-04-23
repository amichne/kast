package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.demo.ConversationTone
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConversationTemplateEngineTest {

    private val workspaceRoot: Path = Paths.get("/repo")

    @Test
    fun `builds three turns with expected user prompts`() {
        val convo = ConversationTemplateEngine.build(reportFixture())

        assertEquals(3, convo.turns.size)
        assertEquals("com.example.Foo.bar", convo.symbolFqn)
        assertEquals("bar", convo.simpleName)
        assertEquals("Find usages of `bar`", convo.turns[0].userPrompt)
        assertEquals("Rename `bar` to `barV2`", convo.turns[1].userPrompt)
        assertEquals("Who calls `bar`?", convo.turns[2].userPrompt)
    }

    @Test
    fun `turn1 left mentions totalMatches and tone classifies false positives as ERROR`() {
        val convo = ConversationTemplateEngine.build(reportFixture())
        val left = convo.turns[0].leftResponse

        assertTrue(left.any { it.text.contains("42") && it.tone == ConversationTone.NORMAL }) {
            "expected NORMAL line containing total match count: $left"
        }
        assertTrue(left.any { it.text.contains("ambiguous") && it.tone == ConversationTone.WARNING }) {
            "expected WARNING line for ambiguous matches: $left"
        }
        assertTrue(left.any { it.text.contains("false positives") && it.tone == ConversationTone.ERROR }) {
            "expected ERROR line for false positives: $left"
        }
    }

    @Test
    fun `turn1 right shows fully qualified name and reference count`() {
        val convo = ConversationTemplateEngine.build(reportFixture())
        val right = convo.turns[0].rightResponse

        val headline = right.first()
        assertEquals(ConversationTone.SUCCESS, headline.tone)
        assertTrue(headline.text.contains("com.example.Foo.bar")) { "headline: ${headline.text}" }
        assertTrue(right.any { it.text.contains("3 precise references") }) { "lines: $right" }
    }

    @Test
    fun `turn2 left warns about unsafe edits and right shows precise edit count`() {
        val convo = ConversationTemplateEngine.build(reportFixture())
        val left = convo.turns[1].leftResponse
        val right = convo.turns[1].rightResponse

        // ambiguous (5) + falsePositives (10) = 15 unsafe edits
        assertTrue(left.any { it.tone == ConversationTone.ERROR && it.text.contains("15") }) {
            "expected ERROR line citing 15 unsafe edits: $left"
        }
        assertTrue(left.any { it.tone == ConversationTone.WARNING }) { "expected WARNING line: $left" }
        assertTrue(right.first().text.contains("2 precise edits")) { "right headline: ${right.first().text}" }
        assertEquals(ConversationTone.SUCCESS, right.first().tone)
        assertTrue(right.any { it.text.contains("hash") && it.tone == ConversationTone.DIM }) {
            "expected DIM hash-guard line: $right"
        }
    }

    @Test
    fun `turn3 left says cannot determine callers and right shows call hierarchy entries`() {
        val convo = ConversationTemplateEngine.build(reportFixture())
        val left = convo.turns[2].leftResponse
        val right = convo.turns[2].rightResponse

        assertTrue(left.first().text.contains("cannot determine callers")) { "left[0]: ${left.first().text}" }
        assertEquals(ConversationTone.ERROR, left.first().tone)
        assertEquals(ConversationTone.SUCCESS, right.first().tone)
        assertTrue(right.first().text.contains("2 incoming caller")) { "right headline: ${right.first().text}" }
        assertTrue(right.any { it.text.contains("com.example.Caller1") }) { "callers: $right" }
        assertTrue(right.any { it.text.contains("com.example.Caller2") }) { "callers: $right" }
    }

    @Test
    fun `nulls in report produce graceful no result right pane`() {
        val empty = reportFixture(
            references = ReferencesResult(declaration = null, references = emptyList()),
            rename = RenameResult(edits = emptyList(), fileHashes = emptyList(), affectedFiles = emptyList()),
            callHierarchy = callHierarchyResultOf(emptyList()),
        )
        val convo = ConversationTemplateEngine.build(empty)

        convo.turns.forEach { turn ->
            val noResult = turn.rightResponse.singleOrNull()
            assertNotNull(noResult) { "expected single right line for turn '${turn.userPrompt}': ${turn.rightResponse}" }
            assertEquals("(no result)", noResult!!.text)
            assertEquals(ConversationTone.DIM, noResult.tone)
        }
    }

    // -------------- fixtures --------------

    private fun reportFixture(
        references: ReferencesResult = defaultReferences(),
        rename: RenameResult = defaultRename(),
        callHierarchy: CallHierarchyResult = defaultCallHierarchy(),
    ): DemoReport {
        val resolved = symbol("com.example.Foo.bar", "/repo/src/Foo.kt", line = 1)
        return DemoReport(
            workspaceRoot = workspaceRoot,
            selectedSymbol = resolved,
            textSearch = DemoTextSearchSummary(
                totalMatches = 42,
                likelyCorrect = 27,
                ambiguous = 5,
                falsePositives = 10,
                filesTouched = 8,
                categoryCounts = emptyMap(),
                sampleMatches = emptyList(),
            ),
            resolvedSymbol = resolved,
            references = references,
            rename = rename,
            callHierarchy = callHierarchy,
        )
    }

    private fun defaultReferences(): ReferencesResult = ReferencesResult(
        declaration = null,
        references = listOf(
            location("/repo/src/A.kt", line = 10, preview = "foo.bar()"),
            location("/repo/src/B.kt", line = 21, preview = "obj.bar(42)"),
            location("/repo/src/C.kt", line = 7, preview = "bar()"),
        ),
    )

    private fun defaultRename(): RenameResult = RenameResult(
        edits = listOf(
            TextEdit(filePath = "/repo/src/A.kt", startOffset = 100, endOffset = 103, newText = "barV2"),
            TextEdit(filePath = "/repo/src/B.kt", startOffset = 200, endOffset = 203, newText = "barV2"),
        ),
        fileHashes = listOf(
            FileHash(filePath = "/repo/src/A.kt", hash = "deadbeef"),
            FileHash(filePath = "/repo/src/B.kt", hash = "cafebabe"),
        ),
        affectedFiles = listOf("/repo/src/A.kt", "/repo/src/B.kt"),
    )

    private fun defaultCallHierarchy(): CallHierarchyResult {
        val children = listOf(
            CallNode(
                symbol = symbol("com.example.Caller1", "/repo/src/Caller1.kt", line = 5),
                callSite = location("/repo/src/Caller1.kt", line = 9, preview = "bar()"),
                children = emptyList(),
            ),
            CallNode(
                symbol = symbol("com.example.Caller2", "/repo/src/Caller2.kt", line = 12),
                callSite = location("/repo/src/Caller2.kt", line = 30, preview = "bar()"),
                children = emptyList(),
            ),
        )
        return callHierarchyResultOf(children)
    }

    private fun callHierarchyResultOf(children: List<CallNode>): CallHierarchyResult {
        val root = CallNode(
            symbol = symbol("com.example.Foo.bar", "/repo/src/Foo.kt", line = 1),
            callSite = null,
            children = children,
        )
        return CallHierarchyResult(
            root = root,
            stats = CallHierarchyStats(
                totalNodes = children.size + 1,
                totalEdges = children.size,
                truncatedNodes = 0,
                maxDepthReached = if (children.isEmpty()) 0 else 1,
                timeoutReached = false,
                maxTotalCallsReached = false,
                maxChildrenPerNodeReached = false,
                filesVisited = children.size + 1,
            ),
        )
    }

    private fun symbol(fqName: String, filePath: String, line: Int): Symbol = Symbol(
        fqName = fqName,
        kind = SymbolKind.FUNCTION,
        location = location(filePath, line = line, preview = ""),
    )

    private fun location(filePath: String, line: Int, preview: String = ""): Location = Location(
        filePath = filePath,
        startOffset = 0,
        endOffset = 0,
        startLine = line,
        startColumn = 1,
        preview = preview,
    )
}
