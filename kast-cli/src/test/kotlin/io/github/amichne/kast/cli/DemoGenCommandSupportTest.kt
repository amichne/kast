package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DemoGenCommandSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runInteractive in MARKDOWN mode prints markdown without entering session`() = runBlocking {
        val recordedOutput = mutableListOf<String>()
        val sessionRunner = RecordingSessionRunner()
        val support = supportWith(
            sessionRunner = sessionRunner,
            output = { recordedOutput += it },
        )

        val outcome = support.runInteractive(demoGenOptions(DemoGenOutputFormat.MARKDOWN))

        assertTrue(outcome is DemoFlowOutcome.Completed, "Expected Completed, got $outcome")
        assertEquals(0, sessionRunner.callCount, "Session runner must NOT be invoked for headless output")
        assertEquals(1, recordedOutput.size)
        val markdown = recordedOutput.single()
        assertTrue(markdown.contains("io.example.Foo"), "Markdown should mention the curated symbol; was: $markdown")
    }

    @Test
    fun `runInteractive in JSON mode emits valid JSON for the curated symbols`() = runBlocking {
        val recordedOutput = mutableListOf<String>()
        val sessionRunner = RecordingSessionRunner()
        val support = supportWith(
            sessionRunner = sessionRunner,
            output = { recordedOutput += it },
        )

        val outcome = support.runInteractive(demoGenOptions(DemoGenOutputFormat.JSON, symbolCount = 2))

        assertTrue(outcome is DemoFlowOutcome.Completed, "Expected Completed, got $outcome")
        assertEquals(0, sessionRunner.callCount, "Session runner must NOT be invoked for headless output")
        val json = recordedOutput.single()
        // Round-trip parse to ensure validity.
        val parsed = Json.parseToJsonElement(json) as JsonObject
        assertEquals(0, parsed.getValue("activeIndex").jsonPrimitive.content.toInt())
        val conversations = parsed.getValue("conversations").jsonArray
        assertEquals(2, conversations.size, "JSON should contain one conversation per curated symbol")
    }

    @Test
    fun `runInteractive in TERMINAL mode delegates to the kotter session runner`() = runBlocking {
        val sessionRunner = RecordingSessionRunner()
        val support = supportWith(sessionRunner = sessionRunner)

        val outcome = support.runInteractive(demoGenOptions(DemoGenOutputFormat.TERMINAL))

        assertEquals(1, sessionRunner.callCount, "Terminal mode must enter the kotter session exactly once")
        // The recording runner short-circuits the block (it would otherwise block
        // on `runUntilSignal`), so the orchestration returns whatever the runner
        // chooses — here, Cancelled. The contract under test is the delegation,
        // not the block's internals.
        assertTrue(outcome is DemoFlowOutcome.Cancelled, "Expected Cancelled when runner skips the block; got $outcome")
    }

    @Test
    fun `clone failure surfaces as CliFailure`() {
        val support = supportWith(
            ingestion = object : RepoIngestionPort {
                override fun clone(repoUrl: String): Path =
                    throw CliFailure(code = "DEMO_GEN_INVALID_URL", message = "bad url: $repoUrl")
                override suspend fun bootstrap(workspaceRoot: Path, backend: DemoGenBackend): WorkspaceEnsureResult =
                    error("not reached")
            },
        )

        val ex = assertThrows(CliFailure::class.java) {
            runBlocking { support.runInteractive(demoGenOptions(DemoGenOutputFormat.MARKDOWN)) }
        }
        assertEquals("DEMO_GEN_INVALID_URL", ex.code)
    }

    @Test
    fun `headless bootstrap failure is wrapped as DemoFlowOutcome Failed`() = runBlocking {
        val baseBackend = FakeDemoGenBackend()
        val support = supportWith(
            backend = object : DemoGenBackend by baseBackend {
                override suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult =
                    throw IllegalStateException("daemon unavailable")
            },
        )

        val outcome = support.runInteractive(demoGenOptions(DemoGenOutputFormat.JSON))

        assertTrue(outcome is DemoFlowOutcome.Failed, "Expected Failed, got $outcome")
        val failed = outcome as DemoFlowOutcome.Failed
        assertTrue(
            failed.message.contains("Workspace bootstrap failed"),
            "Failure message should describe the phase; was: ${failed.message}",
        )
    }

    // ── Test fixtures ───────────────────────────────────────────────

    private fun demoGenOptions(format: DemoGenOutputFormat, symbolCount: Int = 1): DemoGenOptions =
        DemoGenOptions(
            repoUrl = "https://github.com/example/test.git",
            symbolCount = symbolCount,
            output = format,
        )

    private fun supportWith(
        backend: DemoGenBackend = FakeDemoGenBackend(),
        sessionRunner: KotterDemoSessionRunner = RecordingSessionRunner(),
        ingestion: RepoIngestionPort = FakeIngestion(tempDir),
        output: (String) -> Unit = {},
    ): DemoGenCommandSupport {
        // analyzeTextSearch on the real DemoCommandSupport just walks tempDir
        // (empty), which yields zero matches — fine for ranking the canned
        // symbols by FQName tie-break.
        val demoSupport = DemoCommandSupport()
        val curation = SymbolCurationEngine(demoSupport)
        return DemoGenCommandSupport(
            backend = backend,
            demoSupport = demoSupport,
            curationEngine = curation,
            sessionRunner = sessionRunner,
            ingestion = ingestion,
            output = DemoGenOutput { output(it) },
        )
    }

    /**
     * Records [runSession] invocations without executing the block. The block
     * contains `runUntilSignal` which would otherwise block this test forever
     * waiting on stdin keypresses; for the contract test it's enough to verify
     * delegation occurred.
     */
    private class RecordingSessionRunner : KotterDemoSessionRunner {
        var callCount: Int = 0
            private set

        override fun runSession(
            verbose: Boolean,
            block: com.varabyte.kotter.runtime.Session.(terminal: com.varabyte.kotter.runtime.terminal.Terminal) -> DemoFlowOutcome,
        ): DemoFlowOutcome {
            callCount += 1
            return DemoFlowOutcome.Cancelled
        }
    }

    private class FakeIngestion(private val workspace: Path) : RepoIngestionPort {
        override fun clone(repoUrl: String): Path = workspace
        override suspend fun bootstrap(workspaceRoot: Path, backend: DemoGenBackend): WorkspaceEnsureResult =
            backend.bootstrap(workspaceRoot)
    }

    /**
     * Returns deterministic symbols, [DemoReport]s, and bootstrap result so
     * tests don't need a running daemon.
     */
    private inner class FakeDemoGenBackend(
        private val symbolFqNames: List<String> = listOf(
            "io.example.Foo",
            "io.example.Bar",
            "io.example.Baz",
        ),
    ) : DemoGenBackend {
        override suspend fun bootstrap(workspaceRoot: Path): WorkspaceEnsureResult =
            WorkspaceEnsureResult(
                workspaceRoot = workspaceRoot.toString(),
                started = true,
                logFile = null,
                selected = fakeRuntimeStatus(workspaceRoot),
                note = "fake-bootstrap",
            )

        override suspend fun listAllSymbols(workspaceRoot: Path): List<Symbol> =
            symbolFqNames.map(::demoSymbol)

        override suspend fun buildReportInstrumented(
            workspaceRoot: Path,
            symbol: Symbol,
            onStepComplete: (index: Int, durationMs: Long) -> Unit,
        ): DemoReport {
            // Drive the progress callback so any consumer would tick correctly.
            repeat(5) { onStepComplete(it, 0L) }
            return sampleReport(symbol)
        }
    }

    private fun fakeRuntimeStatus(workspaceRoot: Path): RuntimeCandidateStatus = RuntimeCandidateStatus(
        descriptorPath = workspaceRoot.resolve(".kast/descriptor-standalone.json").toString(),
        descriptor = ServerInstanceDescriptor(
            workspaceRoot = workspaceRoot.toString(),
            backendName = "standalone",
            backendVersion = "0.0.0-test",
            socketPath = workspaceRoot.resolve(".kast/socket-standalone").toString(),
            pid = 12345L,
        ),
        pidAlive = true,
        reachable = true,
        ready = true,
    )

    private fun demoSymbol(fqName: String): Symbol {
        val simple = fqName.substringAfterLast('.')
        return Symbol(
            fqName = fqName,
            kind = SymbolKind.CLASS,
            location = location(tempDir.resolve("$simple.kt"), 1, "class $simple"),
            visibility = SymbolVisibility.PUBLIC,
            containingDeclaration = fqName.substringBeforeLast('.', ""),
        )
    }

    private fun location(filePath: Path, line: Int, preview: String): Location = Location(
        filePath = filePath.toString(),
        startOffset = 0,
        endOffset = preview.length,
        startLine = line,
        startColumn = 1,
        preview = preview,
    )

    private fun sampleReport(symbol: Symbol): DemoReport {
        val searchScope = SearchScope(
            visibility = SymbolVisibility.PUBLIC,
            scope = SearchScopeKind.DEPENDENT_MODULES,
            exhaustive = true,
            candidateFileCount = 1,
            searchedFileCount = 1,
        )
        val simple = symbol.fqName.substringAfterLast('.')
        return DemoReport(
            workspaceRoot = tempDir,
            selectedSymbol = symbol,
            textSearch = DemoTextSearchSummary(
                totalMatches = 0,
                likelyCorrect = 0,
                ambiguous = 0,
                falsePositives = 0,
                filesTouched = 0,
                categoryCounts = emptyMap(),
                sampleMatches = emptyList(),
            ),
            resolvedSymbol = symbol,
            references = ReferencesResult(
                declaration = symbol,
                references = emptyList(),
                searchScope = searchScope,
            ),
            rename = RenameResult(
                edits = listOf(TextEdit(symbol.location.filePath, 0, 3, "${simple}V2")),
                fileHashes = listOf(FileHash(symbol.location.filePath, "abc")),
                affectedFiles = listOf(symbol.location.filePath),
                searchScope = searchScope,
            ),
            callHierarchy = CallHierarchyResult(
                root = CallNode(symbol = symbol, children = emptyList()),
                stats = CallHierarchyStats(
                    totalNodes = 1,
                    totalEdges = 0,
                    truncatedNodes = 0,
                    maxDepthReached = 0,
                    timeoutReached = false,
                    maxTotalCallsReached = false,
                    maxChildrenPerNodeReached = false,
                    filesVisited = 1,
                ),
            ),
        )
    }
}
