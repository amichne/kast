package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.cli.CliTextTheme
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SymbolWalkerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `walker emits intro and cursor card, exits on quit with zero hops`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ScriptedIO(inputs = listOf("q"))
        val summary = runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        assertEquals(0, summary.hops)
        val transcript = io.emitted.joinToString("\n")
        assertTrue(transcript.contains("Act 3 · walk the symbol graph"), transcript)
        assertTrue(transcript.contains("current node"), transcript)
        // Default display is simple name + bare file name; verbose FQCN paths are gated behind --verbose.
        assertTrue(transcript.contains(root.fqName.substringAfterLast('.')), transcript)
    }

    @Test
    fun `jumping to a reference resolves the target and counts as a hop`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val refTarget = symbol("app.Caller.useRoot", tempDir.resolve("Caller.kt"), kind = SymbolKind.FUNCTION)
        val refLocation = Location(
            filePath = refTarget.location.filePath,
            startOffset = 50,
            endOffset = 54,
            startLine = 3,
            startColumn = 1,
            preview = "Root()",
        )
        val graph = RecordingGraph(
            resolves = mapOf(
                root.location.asKey() to Result.success(root),
                refLocation.asKey() to Result.success(refTarget),
            ),
            references = mapOf(
                root.location.asKey() to Result.success(listOf(refLocation)),
                refTarget.location.asKey() to Result.success(emptyList()),
            ),
            callers = emptyMap(),
            callees = emptyMap(),
        )
        val io = ScriptedIO(inputs = listOf("r 1", "q"))
        val summary = runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        assertEquals(1, summary.hops)
        val transcript = io.emitted.joinToString("\n")
        // Default renders the simple name; verbose renders the FQCN.
        assertTrue(transcript.contains(refTarget.fqName.substringAfterLast('.')), transcript)
    }

    @Test
    fun `out-of-range jump emits walker error and does not increment hops`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ScriptedIO(inputs = listOf("r 9", "q"))
        val summary = runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        assertEquals(0, summary.hops)
        assertTrue(io.emitted.joinToString("\n").contains("out of range"))
    }

    @Test
    fun `cursor card uses tree prefixes for references callers and callees`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val refLoc = Location(
            filePath = tempDir.resolve("Caller.kt").toString(),
            startOffset = 10,
            endOffset = 14,
            startLine = 2,
            startColumn = 3,
            preview = "Root()",
        )
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(listOf(refLoc))),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ScriptedIO(inputs = listOf("q"))
        runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        val transcript = io.emitted.joinToString("\n")
        // Non-empty branch gets the tree elbow + token prefix.
        assertTrue(transcript.contains("├── [r 1]") || transcript.contains("└── [r 1]"), transcript)
        // Empty branches fall through to the terminal elbow.
        assertTrue(transcript.contains("└── no callers found at depth 1"), transcript)
        assertTrue(transcript.contains("└── no callees found at depth 1"), transcript)
    }

    @Test
    fun `choose result takes priority over prompt input`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        // If choose returned "q" the walker must exit without ever reading from prompt.
        val io = ChoosingIO(chooseReturns = listOf("q"), promptInputs = emptyList())
        runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        assertEquals(0, io.promptCalls)
        assertEquals(1, io.chooseCalls)
    }

    @Test
    fun `walker falls back to prompt when choose returns null`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ChoosingIO(chooseReturns = listOf<String?>(null), promptInputs = listOf("q"))
        runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        assertEquals(1, io.chooseCalls)
        assertEquals(1, io.promptCalls)
    }

    @Test
    fun `verbose display shows fully-qualified names and workspace-relative paths`() {
        val root = symbol("app.nested.Root", tempDir.resolve("nested/Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ScriptedIO(inputs = listOf("q"))
        runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                display = SymbolDisplay(workspaceRoot = tempDir, verbose = true),
                grepRunner = NoopGrepRunner,
            ).run(root)
        }
        val transcript = io.emitted.joinToString("\n")
        assertTrue(transcript.contains(root.fqName), transcript)
        assertTrue(transcript.contains("nested/Root.kt") || transcript.contains("nested\\Root.kt"), transcript)
    }

    @Test
    fun `grep comparison prints the canned grep lines and the disclaimer`() {
        val root = symbol("app.Root", tempDir.resolve("Root.kt"))
        val graph = RecordingGraph(
            resolves = mapOf(root.location.asKey() to Result.success(root)),
            references = mapOf(root.location.asKey() to Result.success(emptyList())),
            callers = mapOf(root.location.asKey() to Result.success(emptyList())),
            callees = mapOf(root.location.asKey() to Result.success(emptyList())),
        )
        val io = ScriptedIO(inputs = listOf("g 3", "q"))
        val grep = StaticGrepRunner(Result.success(listOf("A.kt:1: class Root", "B.kt:2: // Root")))
        runBlocking {
            SymbolWalker(
                workspaceRoot = tempDir,
                graph = graph,
                io = io,
                renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false),
                grepRunner = grep,
            ).run(root)
        }
        val transcript = io.emitted.joinToString("\n")
        assertTrue(transcript.contains("A.kt:1: class Root"), transcript)
        assertTrue(transcript.contains("grep has no way"), transcript)
    }

    // ---- test helpers ----

    private fun symbol(
        fqName: String,
        filePath: Path,
        kind: SymbolKind = SymbolKind.CLASS,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = Location(
            filePath = filePath.toString(),
            startOffset = 0,
            endOffset = 4,
            startLine = 1,
            startColumn = 1,
            preview = "class ${fqName.substringAfterLast('.')}",
        ),
        visibility = SymbolVisibility.PUBLIC,
        containingDeclaration = fqName.substringBeforeLast('.', ""),
    )

    private fun Location.asKey(): String = "$filePath@$startOffset"

    private class RecordingGraph(
        val resolves: Map<String, Result<Symbol>>,
        val references: Map<String, Result<List<Location>>>,
        val callers: Map<String, Result<List<Symbol>>>,
        val callees: Map<String, Result<List<Symbol>>>,
    ) : SymbolGraph {
        override suspend fun resolve(position: FilePosition): Result<Symbol> =
            resolves["${position.filePath}@${position.offset}"]
                ?: Result.failure(IllegalStateException("unexpected resolve at ${position.filePath}@${position.offset}"))

        override suspend fun references(position: FilePosition): Result<List<Location>> =
            references["${position.filePath}@${position.offset}"] ?: Result.success(emptyList())

        override suspend fun callers(position: FilePosition): Result<List<Symbol>> =
            callers["${position.filePath}@${position.offset}"] ?: Result.success(emptyList())

        override suspend fun callees(position: FilePosition): Result<List<Symbol>> =
            callees["${position.filePath}@${position.offset}"] ?: Result.success(emptyList())
    }

    private class ScriptedIO(inputs: List<String>) : WalkerIO {
        private val queue: ArrayDeque<String> = ArrayDeque(inputs)
        val emitted: MutableList<String> = mutableListOf()
        override fun emit(line: String) {
            emitted += line
        }

        override fun prompt(): String? = queue.removeFirstOrNull()
    }

    /**
     * Records invocations of [choose] vs [prompt] so we can prove the walker
     * prefers structured selection when available and only falls back to a
     * raw prompt read when the picker returns null.
     */
    private class ChoosingIO(
        chooseReturns: List<String?>,
        promptInputs: List<String>,
    ) : WalkerIO {
        private val chooseQueue: ArrayDeque<String?> = ArrayDeque(chooseReturns)
        private val promptQueue: ArrayDeque<String> = ArrayDeque(promptInputs)
        var chooseCalls: Int = 0
            private set
        var promptCalls: Int = 0
            private set

        override fun emit(line: String) { /* discard */ }
        override fun prompt(): String? {
            promptCalls += 1
            return promptQueue.removeFirstOrNull()
        }

        override fun choose(header: String, choices: List<WalkerMenuChoice>): String? {
            chooseCalls += 1
            return chooseQueue.removeFirstOrNull()
        }
    }

    private object NoopGrepRunner : GrepRunner {
        override fun grep(pattern: String, maxLines: Int): Result<List<String>> = Result.success(emptyList())
    }

    private class StaticGrepRunner(private val outcome: Result<List<String>>) : GrepRunner {
        override fun grep(pattern: String, maxLines: Int): Result<List<String>> = outcome
    }
}
