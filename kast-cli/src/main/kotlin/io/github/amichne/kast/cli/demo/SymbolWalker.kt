package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.cli.CliService
import io.github.amichne.kast.cli.RuntimeCommandOptions
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path

/** Snapshot of a symbol the walker is focused on. */
internal data class SymbolCursor(
    val symbol: Symbol,
    val position: FilePosition,
    val references: List<Location>,
    val incomingCallers: List<Symbol>,
    val outgoingCallees: List<Symbol>,
) {
    val simpleName: String get() = symbol.fqName.substringAfterLast('.')
}

/** Functional seam over [CliService] so the walker can be tested without a live daemon. */
internal interface SymbolGraph {
    suspend fun resolve(position: FilePosition): Result<Symbol>
    suspend fun references(position: FilePosition): Result<List<Location>>
    suspend fun callers(position: FilePosition): Result<List<Symbol>>
    suspend fun callees(position: FilePosition): Result<List<Symbol>>
}

/** Default implementation backed by [CliService]. */
internal class CliServiceSymbolGraph(
    private val cliService: CliService,
    private val runtimeOptions: RuntimeCommandOptions,
) : SymbolGraph {
    override suspend fun resolve(position: FilePosition): Result<Symbol> = runCatching {
        cliService.resolveSymbol(runtimeOptions, SymbolQuery(position = position)).payload.symbol
    }

    override suspend fun references(position: FilePosition): Result<List<Location>> = runCatching {
        cliService.findReferences(
            runtimeOptions,
            ReferencesQuery(position = position, includeDeclaration = false),
        ).payload.references
    }

    override suspend fun callers(position: FilePosition): Result<List<Symbol>> = runCatching {
        val root = cliService.callHierarchy(
            runtimeOptions,
            CallHierarchyQuery(position = position, direction = CallDirection.INCOMING, depth = 1),
        ).payload.root
        directChildren(root)
    }

    override suspend fun callees(position: FilePosition): Result<List<Symbol>> = runCatching {
        val root = cliService.callHierarchy(
            runtimeOptions,
            CallHierarchyQuery(position = position, direction = CallDirection.OUTGOING, depth = 1),
        ).payload.root
        directChildren(root)
    }

    private fun directChildren(root: CallNode): List<Symbol> =
        root.children.map { it.symbol }
}

/**
 * Transport between the walker and the operator. [prompt] is called before
 * every read; [emit] is called for every rendered line. Kept minimal so the
 * walker can be driven by a real TTY or by a scripted test.
 */
internal interface WalkerIO {
    fun emit(line: String)
    fun prompt(): String?
}

internal class StreamWalkerIO(
    private val reader: BufferedReader,
    private val output: (String) -> Unit,
) : WalkerIO {
    override fun emit(line: String) = output(line)
    override fun prompt(): String? = reader.readLine()
}

/** Runs the interactive symbol-graph walk and returns the number of successful hops made. */
internal class SymbolWalker(
    private val workspaceRoot: Path,
    private val graph: SymbolGraph,
    private val io: WalkerIO,
    private val renderer: DemoRenderer,
    private val grepRunner: GrepRunner = DefaultGrepRunner(workspaceRoot),
) {
    private val history: ArrayDeque<SymbolCursor> = ArrayDeque()

    suspend fun run(initialSymbol: Symbol): WalkSummary {
        io.emit(renderer.render(intro()))
        var cursor = hydrate(initialSymbol).getOrElse {
            io.emit("Could not hydrate starting symbol: ${it.message}")
            return WalkSummary(hops = 0)
        }
        history.addLast(cursor)
        var hops = 0
        while (true) {
            io.emit(renderer.render(cursorCard(cursor)))
            io.emit(renderer.render(promptLine()))
            when (val command = WalkerCommand.parse(io.prompt())) {
                WalkerCommand.Help -> io.emit(renderer.render(helpCard()))
                WalkerCommand.Quit, WalkerCommand.EndOfInput -> return WalkSummary(hops = hops)
                WalkerCommand.Back -> {
                    if (history.size <= 1) {
                        io.emit(renderer.render(errorCard("Already at the starting symbol — nothing to pop.")))
                    } else {
                        history.removeLast()
                        cursor = history.last()
                    }
                }
                WalkerCommand.ShowDeclaration -> io.emit(renderer.render(declarationCard(cursor)))
                is WalkerCommand.GrepComparison -> io.emit(renderer.render(grepCard(cursor, command.maxLines)))
                is WalkerCommand.JumpReference -> {
                    val target = cursor.references.getOrNull(command.oneBasedIndex - 1)
                    cursor = hopTo(cursor, target?.asPosition())?.also { history.addLast(it); hops += 1 } ?: cursor
                }
                is WalkerCommand.JumpCaller -> {
                    val target = cursor.incomingCallers.getOrNull(command.oneBasedIndex - 1)
                    cursor = hopTo(cursor, target?.location?.asPosition())?.also { history.addLast(it); hops += 1 } ?: cursor
                }
                is WalkerCommand.JumpCallee -> {
                    val target = cursor.outgoingCallees.getOrNull(command.oneBasedIndex - 1)
                    cursor = hopTo(cursor, target?.location?.asPosition())?.also { history.addLast(it); hops += 1 } ?: cursor
                }
                is WalkerCommand.Unknown -> io.emit(
                    renderer.render(errorCard("Unknown command: ${command.raw}. Type `h` for help."))
                )
            }
        }
    }

    private suspend fun hopTo(
        current: SymbolCursor,
        target: FilePosition?,
    ): SymbolCursor? {
        if (target == null) {
            io.emit(renderer.render(errorCard("That index is out of range for the current node.")))
            return null
        }
        val resolved = graph.resolve(target)
        val symbol = resolved.getOrElse {
            io.emit(renderer.render(errorCard("resolve failed: ${it.message ?: "no message"}")))
            return null
        }
        return hydrateAt(symbol, target)
    }

    private suspend fun hydrate(symbol: Symbol): Result<SymbolCursor> = runCatching {
        hydrateAt(symbol, symbol.location.asPosition())
    }

    private suspend fun hydrateAt(symbol: Symbol, position: FilePosition): SymbolCursor {
        val refs = graph.references(position).getOrElse { emptyList() }
        val callers = graph.callers(position).getOrElse { emptyList() }
        val callees = graph.callees(position).getOrElse { emptyList() }
        return SymbolCursor(
            symbol = symbol,
            position = position,
            references = refs,
            incomingCallers = callers,
            outgoingCallees = callees,
        )
    }

    private fun intro(): DemoScript = demoScript {
        section("Act 3 · walk the symbol graph")
        panel("interactive walker") {
            line("Hop between references, callers, and callees — every move is anchored to symbol identity.", emphasis = LineEmphasis.STRONG)
            blank()
            line("r <n>  jump to reference #n        c <n>  jump to incoming caller #n")
            line("o <n>  jump to outgoing callee #n  g [n]  compare against grep (n lines)")
            line("s      show current declaration    b      pop the last hop")
            line("h      help                         q      finish the walker")
        }
        blank()
    }

    private fun cursorCard(cursor: SymbolCursor): DemoScript = demoScript {
        val symbol = cursor.symbol
        panel("current node") {
            line("fqName   ${symbol.fqName}", emphasis = LineEmphasis.STRONG)
            line("kind     ${symbol.kind}${symbol.visibility?.let { " · $it" } ?: ""}")
            line("location ${Paths.locationLine(workspaceRoot, symbol.location)}")
            symbol.containingDeclaration?.takeIf { it.isNotBlank() }?.let { line("inside   $it") }
        }
        step("references (${cursor.references.size})") {
            info()
            body {
                if (cursor.references.isEmpty()) {
                    line("no semantic references", emphasis = LineEmphasis.DIM)
                } else {
                    cursor.references.take(WALK_PREVIEW).forEachIndexed { index, ref ->
                        line("[r ${index + 1}]  ${Paths.locationLine(workspaceRoot, ref)}  ${ref.preview.trim().take(70)}")
                    }
                    if (cursor.references.size > WALK_PREVIEW) {
                        line("... and ${cursor.references.size - WALK_PREVIEW} more (use r <n> with larger n)", emphasis = LineEmphasis.DIM)
                    }
                }
            }
        }
        step("incoming callers (${cursor.incomingCallers.size})") {
            info()
            body {
                if (cursor.incomingCallers.isEmpty()) {
                    line("no callers found at depth 1", emphasis = LineEmphasis.DIM)
                } else {
                    cursor.incomingCallers.take(WALK_PREVIEW).forEachIndexed { index, sym ->
                        line("[c ${index + 1}]  ${sym.fqName.substringAfterLast('.')} (${sym.kind})  ${Paths.locationLine(workspaceRoot, sym.location)}")
                    }
                }
            }
        }
        step("outgoing callees (${cursor.outgoingCallees.size})") {
            info()
            body {
                if (cursor.outgoingCallees.isEmpty()) {
                    line("no callees found at depth 1", emphasis = LineEmphasis.DIM)
                } else {
                    cursor.outgoingCallees.take(WALK_PREVIEW).forEachIndexed { index, sym ->
                        line("[o ${index + 1}]  ${sym.fqName.substringAfterLast('.')} (${sym.kind})  ${Paths.locationLine(workspaceRoot, sym.location)}")
                    }
                }
            }
        }
    }

    private fun promptLine(): DemoScript = demoScript {
        step("walker›") { info() }
    }

    private fun helpCard(): DemoScript = demoScript {
        panel("walker commands") {
            line("r <n>  jump to reference #n")
            line("c <n>  jump to incoming caller #n")
            line("o <n>  jump to outgoing callee #n")
            line("g [n]  run grep on the current simple name and show n lines (default 6)")
            line("s      show the declaration line")
            line("b      pop the last hop")
            line("h ?    show this help")
            line("q      end the walker and continue the demo")
        }
    }

    private fun errorCard(message: String): DemoScript = demoScript {
        panel("walker error") {
            line(message, emphasis = LineEmphasis.ERROR)
        }
    }

    private fun declarationCard(cursor: SymbolCursor): DemoScript = demoScript {
        panel("declaration @ ${Paths.locationLine(workspaceRoot, cursor.symbol.location)}") {
            val lines = readDeclarationContext(cursor.symbol.location)
            if (lines.isEmpty()) {
                line("(file unreadable from walker)", emphasis = LineEmphasis.DIM)
            } else {
                lines.forEach { line(it) }
            }
        }
    }

    private fun grepCard(cursor: SymbolCursor, maxLines: Int): DemoScript = demoScript {
        panel("grep '${cursor.simpleName}' — the same question without identity") {
            val outcome = grepRunner.grep(cursor.simpleName, maxLines)
            outcome.onSuccess { lines ->
                if (lines.isEmpty()) {
                    line("(no text matches found)", emphasis = LineEmphasis.DIM)
                } else {
                    lines.forEach { line(it) }
                    blank()
                    line(
                        text = "grep has no way to tell you which of these is the current node, which are imports, and which are strings. The walker above already did.",
                        emphasis = LineEmphasis.DIM,
                    )
                }
            }
            outcome.onFailure {
                line("grep unavailable: ${it.message}", emphasis = LineEmphasis.ERROR)
            }
        }
    }

    private fun readDeclarationContext(location: Location): List<String> {
        val file = runCatching { Path.of(location.filePath) }.getOrNull() ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        val all = runCatching { Files.readAllLines(file) }.getOrElse { return emptyList() }
        val centre = (location.startLine - 1).coerceIn(0, all.lastIndex.coerceAtLeast(0))
        val start = (centre - DECLARATION_CONTEXT).coerceAtLeast(0)
        val end = (centre + DECLARATION_CONTEXT).coerceAtMost(all.lastIndex)
        return (start..end).map { index ->
            val marker = if (index == centre) "▶" else " "
            "$marker ${"%4d".format(index + 1)}  ${all[index]}"
        }
    }

    private fun Location.asPosition(): FilePosition = FilePosition(filePath = filePath, offset = startOffset)

    companion object {
        const val WALK_PREVIEW: Int = 8
        const val DECLARATION_CONTEXT: Int = 3
    }
}

internal data class WalkSummary(val hops: Int)

/** Abstract over grep invocations so tests can supply a canned result. */
internal interface GrepRunner {
    fun grep(pattern: String, maxLines: Int): Result<List<String>>
}

internal class DefaultGrepRunner(private val workspaceRoot: Path) : GrepRunner {
    override fun grep(pattern: String, maxLines: Int): Result<List<String>> = runCatching {
        val process = ProcessBuilder(
            "grep", "-rn", "--include=*.kt", "--color=never", "-F", pattern, "."
        ).directory(workspaceRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        lines.take(maxLines)
    }
}
