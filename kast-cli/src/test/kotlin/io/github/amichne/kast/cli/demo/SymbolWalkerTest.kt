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
        assertTrue(transcript.contains(root.fqName), transcript)
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
        assertTrue(transcript.contains(refTarget.fqName), transcript)
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

    private object NoopGrepRunner : GrepRunner {
        override fun grep(pattern: String, maxLines: Int): Result<List<String>> = Result.success(emptyList())
    }

    private class StaticGrepRunner(private val outcome: Result<List<String>>) : GrepRunner {
        override fun grep(pattern: String, maxLines: Int): Result<List<String>> = outcome
    }
}
