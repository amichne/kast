package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.cli.CliService
import io.github.amichne.kast.cli.CliTextTheme
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

    /**
     * Offer the user a structured menu of [choices]. Return the selected
     * choice's [WalkerMenuChoice.token], or `null` to fall back to reading a
     * raw command line via [prompt]. Default: always fall back.
     */
    fun choose(header: String, choices: List<WalkerMenuChoice>): String? = null
}

/** One line offered to the walker's interactive picker. */
internal data class WalkerMenuChoice(
    /** The command string to feed into [WalkerCommand.parse], e.g. `"r 3"` or `"q"`. */
    val token: String,
    /** What the operator sees when selecting the choice. */
    val display: String,
)

internal class StreamWalkerIO(
    private val reader: BufferedReader,
    private val output: (String) -> Unit,
) : WalkerIO {
    override fun emit(line: String) = output(line)
    override fun prompt(): String? = reader.readLine()
}

/**
 * [WalkerIO] that delegates emit/prompt to [delegate] but offers an fzf-backed
 * [choose] when the `fzf` binary is available on `PATH`. Falls back cleanly
 * when fzf is missing, the terminal is non-interactive, or fzf exits without a
 * selection (e.g. the operator pressed Esc), letting the caller re-prompt.
 */
internal class FzfWalkerIO(
    private val delegate: WalkerIO,
    private val fzfPath: String,
) : WalkerIO by delegate {
    override fun choose(header: String, choices: List<WalkerMenuChoice>): String? {
        if (choices.isEmpty()) return null
        // Tab is safe: no walker token or choice display ever contains one, and unlike
        // NUL it is accepted in ProcessBuilder argv (JDK 17+ throws `IOException: Invalid
        // null character in command` when any arg contains U+0000).
        val separator = "\t"
        val process = ProcessBuilder(
            fzfPath,
            "--prompt", "walker› ",
            "--header", header,
            "--layout=reverse",
            "--height=~60%",
            "--no-mouse",
            "--ansi",
            "--with-nth=2..",
            "--delimiter=$separator",
            "--expect=esc,ctrl-c,ctrl-d",
        )
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
        process.outputStream.bufferedWriter().use { writer ->
            choices.forEach { choice ->
                writer.write(choice.token)
                writer.write(separator)
                writer.write(choice.display)
                writer.newLine()
            }
        }
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code == 130 || code == 1) return null // user cancelled or no match
        if (code != 0) return null
        val lines = output.lineSequence().filter { it.isNotEmpty() }.toList()
        // With --expect, fzf prints the key line first (empty when Enter was used).
        // If the first non-empty line is a cancel key the user dismissed the picker —
        // treat it as a cancel and do NOT execute the highlighted item.
        if (lines.firstOrNull() in CANCEL_KEYS) return null
        val payload = lines.firstOrNull { it !in CANCEL_KEYS } ?: return null
        return payload.substringBefore(separator).takeIf { it.isNotBlank() }
    }

    companion object {
        private val CANCEL_KEYS = setOf("esc", "ctrl-c", "ctrl-d")

        /** Resolve an `fzf` executable on `PATH`; null if not found. */
        fun locateFzf(): String? {
            val path = System.getenv("PATH") ?: return null
            val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            val exts = if (isWindows) listOf(".exe", ".bat", ".cmd") else listOf("")
            for (dir in path.split(java.io.File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                for (ext in exts) {
                    val candidate = java.io.File(dir, "fzf$ext")
                    if (candidate.canExecute()) return candidate.absolutePath
                }
            }
            return null
        }
    }
}

/** Runs the interactive symbol-graph walk and returns the number of successful hops made. */
internal class SymbolWalker(
    private val workspaceRoot: Path,
    private val graph: SymbolGraph,
    private val io: WalkerIO,
    private val ui: DemoTerminal,
    private val theme: CliTextTheme = CliTextTheme.detect(),
    private val display: SymbolDisplay = SymbolDisplay(workspaceRoot = workspaceRoot, verbose = false),
    private val grepRunner: GrepRunner = DefaultGrepRunner(workspaceRoot),
) {
    private val history: ArrayDeque<SymbolCursor> = ArrayDeque()

    private fun emit(widget: com.github.ajalt.mordant.rendering.Widget) {
        io.emit(ui.terminal.render(widget))
    }

    suspend fun run(initialSymbol: Symbol): WalkSummary {
        emit(ui.walkerIntro())
        var cursor = hydrate(initialSymbol).getOrElse {
            io.emit("Could not hydrate starting symbol: ${it.message}")
            return WalkSummary(hops = 0)
        }
        history.addLast(cursor)
        var hops = 0
        while (true) {
            emit(ui.walkerCursor(cursor, workspaceRoot))
            val raw = readCommand(cursor)
            when (val command = WalkerCommand.parse(raw)) {
                WalkerCommand.Help -> emit(ui.walkerHelp())
                WalkerCommand.Quit, WalkerCommand.EndOfInput -> return WalkSummary(hops = hops)
                WalkerCommand.Back -> {
                    if (history.size <= 1) {
                        emit(ui.walkerError("Already at the starting symbol — nothing to pop."))
                    } else {
                        history.removeLast()
                        cursor = history.last()
                    }
                }
                WalkerCommand.ShowDeclaration -> emit(
                    ui.walkerDeclaration(workspaceRoot, cursor, readDeclarationContext(cursor.symbol.location))
                )
                is WalkerCommand.GrepComparison -> emit(
                    ui.walkerGrep(cursor.simpleName, grepRunner.grep(cursor.simpleName, command.maxLines))
                )
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
                is WalkerCommand.Unknown -> emit(
                    ui.walkerError("Unknown command: ${command.raw}. Type `h` for help.")
                )
            }
        }
    }

    /**
     * Read the next walker command. Prefers [WalkerIO.choose] so fzf-style
     * transports can render a navigable menu; falls back to a plain `prompt`
     * read when choose returns null (Esc, missing fzf, EOF from a script,
     * etc.).
     */
    private fun readCommand(cursor: SymbolCursor): String? {
        val choices = buildMenuChoices(cursor)
        val picked = if (choices.isNotEmpty()) io.choose(menuHeader(cursor), choices) else null
        if (picked != null) return picked
        emit(ui.walkerPrompt())
        return io.prompt()
    }

    private fun menuHeader(cursor: SymbolCursor): String =
        "current: ${display.name(cursor.symbol)}  ·  " +
            "${cursor.references.size} refs · ${cursor.incomingCallers.size} callers · ${cursor.outgoingCallees.size} callees"

    /**
     * Build the fzf menu for the current cursor. Rows are grouped by action
     * (references → callers → callees → meta), each row carries a parser
     * token identical to what the operator would type. Kind is coloured so
     * the operator can eyeball function-vs-property-vs-class at a glance.
     */
    private fun buildMenuChoices(cursor: SymbolCursor): List<WalkerMenuChoice> {
        val choices = mutableListOf<WalkerMenuChoice>()
        cursor.references.take(WALK_PREVIEW).forEachIndexed { index, ref ->
            val n = index + 1
            val file = theme.fileHeader(display.locationLabel(ref))
            val preview = theme.muted(ref.preview.trim().take(PREVIEW_MAX))
            choices += WalkerMenuChoice(
                token = "r $n",
                display = "[r $n] reference   $file  $preview",
            )
        }
        cursor.incomingCallers.take(WALK_PREVIEW).forEachIndexed { index, sym ->
            val n = index + 1
            choices += WalkerMenuChoice(
                token = "c $n",
                display = "[c $n] caller      ${styledSymbolLabel(sym)}  ${theme.fileHeader(display.locationLabel(sym.location))}",
            )
        }
        cursor.outgoingCallees.take(WALK_PREVIEW).forEachIndexed { index, sym ->
            val n = index + 1
            choices += WalkerMenuChoice(
                token = "o $n",
                display = "[o $n] callee      ${styledSymbolLabel(sym)}  ${theme.fileHeader(display.locationLabel(sym.location))}",
            )
        }
        choices += WalkerMenuChoice("s", "[s]   show current declaration")
        choices += WalkerMenuChoice("g", "[g]   compare against grep (baseline)")
        if (history.size > 1) {
            choices += WalkerMenuChoice("b", "[b]   pop the last hop")
        }
        choices += WalkerMenuChoice("h", "[h]   help")
        choices += WalkerMenuChoice("q", "[q]   finish the walker")
        return choices
    }

    /** `<kind-coloured name> · <kind label>` — the small signature used inside menu rows. */
    private fun styledSymbolLabel(symbol: Symbol): String =
        "${theme.kind(symbol.kind, display.name(symbol))} ${theme.muted("·")} ${theme.kind(symbol.kind, display.kindLabel(symbol.kind))}"

    private suspend fun hopTo(
        current: SymbolCursor,
        target: FilePosition?,
    ): SymbolCursor? {
        if (target == null) {
            emit(ui.walkerError("That index is out of range for the current node."))
            return null
        }
        val resolved = graph.resolve(target)
        val symbol = resolved.getOrElse {
            emit(ui.walkerError("resolve failed: ${it.message ?: "no message"}"))
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
        /** Max characters of reference-line preview we carry into a row. */
        const val PREVIEW_MAX: Int = 60
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
