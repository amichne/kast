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
        val separator = "\u0000" // NUL is safe: no choice display ever contains it.
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
        val payload = lines.dropWhile { it in CANCEL_KEYS }.firstOrNull() ?: return null
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
    private val renderer: DemoRenderer,
    private val theme: CliTextTheme = CliTextTheme.detect(),
    private val display: SymbolDisplay = SymbolDisplay(workspaceRoot = workspaceRoot, verbose = false),
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
            val raw = readCommand(cursor)
            when (val command = WalkerCommand.parse(raw)) {
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
        io.emit(renderer.render(promptLine()))
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

    /** Columns available inside a demo panel (`width - borders - padding`). */
    private fun panelContentWidth(): Int = renderer.panelContentWidth

    /**
     * Fit `<location>  <preview>` into [availableWidth] columns. The file
     * location is preserved in full whenever it fits; any remaining budget
     * goes to the preview, which is right-truncated with `…`. If even the
     * location doesn't fit, it's left-truncated so the file name stays
     * visible.
     */
    private fun fitLocationAndPreview(
        availableWidth: Int,
        location: String,
        preview: String,
    ): Pair<String, String> {
        if (availableWidth <= 0) return "" to ""
        val maxLocation = (availableWidth * 2 / 3).coerceAtLeast(12)
        val fittedLocation = renderer.truncateLeft(location, maxLocation)
        val remaining = (availableWidth - fittedLocation.length - 2).coerceAtLeast(0)
        val fittedPreview = if (remaining <= 0 || preview.isEmpty()) "" else renderer.truncate(preview, remaining)
        return fittedLocation to fittedPreview
    }

    /**
     * Fit `<name> · <kind>  <file>` into [availableWidth]. The identity
     * segment (`name · kind`) stays as-is whenever possible; the file
     * location is left-truncated to fit the remaining budget so the
     * filename stays on the right edge.
     */
    private fun fitSignatureAndLocation(
        availableWidth: Int,
        name: String,
        kindLabel: String,
        location: String,
    ): Pair<String, String> {
        if (availableWidth <= 0) return "" to ""
        val signature = "$name · $kindLabel"
        val fittedSignature = renderer.truncate(signature, (availableWidth * 2 / 3).coerceAtLeast(12))
        val remaining = (availableWidth - fittedSignature.length - 2).coerceAtLeast(0)
        val fittedLocation = if (remaining <= 0) "" else renderer.truncateLeft(location, remaining)
        return fittedSignature to fittedLocation
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
        val title = "current node · ${display.name(symbol)}"
        panel(title) {
            line("name     ${display.name(symbol)}", emphasis = LineEmphasis.STRONG)
            val kindSuffix = symbol.visibility?.let { " · $it" } ?: ""
            val kindPlain = "kind     ${display.kindLabel(symbol.kind)}$kindSuffix"
            styledLine(
                plain = kindPlain,
                rendered = "kind     ${theme.kind(symbol.kind, display.kindLabel(symbol.kind))}${theme.muted(kindSuffix)}",
            )
            val locationPlain = "file     ${display.locationLabel(symbol.location)}"
            styledLine(
                plain = locationPlain,
                rendered = "file     ${theme.fileHeader(display.locationLabel(symbol.location))}",
            )
            symbol.containingDeclaration?.takeIf { it.isNotBlank() }?.let { line("inside   $it") }
            blank()
            renderReferenceBranch(
                header = "references (${cursor.references.size})",
                references = cursor.references.take(WALK_PREVIEW),
                truncatedNote = (cursor.references.size - WALK_PREVIEW)
                    .takeIf { it > 0 }
                    ?.let { "... and $it more (use r <n> with larger n)" },
            )
            blank()
            renderSymbolCallBranch(
                header = "incoming callers (${cursor.incomingCallers.size})",
                tokenPrefix = "c",
                symbols = cursor.incomingCallers.take(WALK_PREVIEW),
                truncatedNote = (cursor.incomingCallers.size - WALK_PREVIEW)
                    .takeIf { it > 0 }
                    ?.let { "... and $it more" },
            )
            blank()
            renderSymbolCallBranch(
                header = "outgoing callees (${cursor.outgoingCallees.size})",
                tokenPrefix = "o",
                symbols = cursor.outgoingCallees.take(WALK_PREVIEW),
                truncatedNote = (cursor.outgoingCallees.size - WALK_PREVIEW)
                    .takeIf { it > 0 }
                    ?.let { "... and $it more" },
            )
        }
    }

    /** A reference row: `├── [r n]  <file:line>  <preview>`. Path is left-truncated, preview is right-truncated. */
    private fun PanelBuilder.renderReferenceBranch(
        header: String,
        references: List<Location>,
        truncatedNote: String?,
    ) {
        line(header, emphasis = LineEmphasis.STRONG)
        if (references.isEmpty()) {
            line("  └── no semantic references", emphasis = LineEmphasis.DIM)
            return
        }
        val contentWidth = panelContentWidth()
        val lastIndex = references.lastIndex
        val hasTail = truncatedNote != null
        references.forEachIndexed { i, ref ->
            val n = i + 1
            val elbow = if (i == lastIndex && !hasTail) "└──" else "├──"
            val prefix = "  $elbow [r $n]  "
            val locationRaw = display.locationLabel(ref)
            val previewRaw = ref.preview.trim().take(PREVIEW_MAX)
            val (locationPlain, previewPlain) = fitLocationAndPreview(
                availableWidth = (contentWidth - prefix.length).coerceAtLeast(8),
                location = locationRaw,
                preview = previewRaw,
            )
            val plain = "$prefix$locationPlain${if (previewPlain.isNotEmpty()) "  $previewPlain" else ""}"
            val rendered = "$prefix${theme.fileHeader(locationPlain)}" +
                if (previewPlain.isNotEmpty()) "  ${theme.muted(previewPlain)}" else ""
            styledLine(plain = plain, rendered = rendered)
        }
        truncatedNote?.let { line("  └── $it", emphasis = LineEmphasis.DIM) }
    }

    /** A caller / callee row: `├── [c n]  <name> · <kind>  <file:line>`. */
    private fun PanelBuilder.renderSymbolCallBranch(
        header: String,
        tokenPrefix: String,
        symbols: List<Symbol>,
        truncatedNote: String?,
    ) {
        line(header, emphasis = LineEmphasis.STRONG)
        if (symbols.isEmpty()) {
            line("  └── no ${if (tokenPrefix == "c") "callers" else "callees"} found at depth 1", emphasis = LineEmphasis.DIM)
            return
        }
        val contentWidth = panelContentWidth()
        val lastIndex = symbols.lastIndex
        val hasTail = truncatedNote != null
        symbols.forEachIndexed { i, sym ->
            val n = i + 1
            val elbow = if (i == lastIndex && !hasTail) "└──" else "├──"
            val prefix = "  $elbow [$tokenPrefix $n]  "
            val namePlain = display.name(sym)
            val kindLabel = display.kindLabel(sym.kind)
            val locationRaw = display.locationLabel(sym.location)
            val available = (contentWidth - prefix.length).coerceAtLeast(8)
            val (signaturePlain, locationPlain) = fitSignatureAndLocation(
                availableWidth = available,
                name = namePlain,
                kindLabel = kindLabel,
                location = locationRaw,
            )
            val plain = "$prefix$signaturePlain${if (locationPlain.isNotEmpty()) "  $locationPlain" else ""}"
            // Style each half of `name · kindLabel` by splitting on the separator once. Using
            // String.replace here would corrupt rendering when the name is a substring of the
            // kind label (e.g. `face` in `interface`).
            val renderedSignature = if (signaturePlain.contains(" · ")) {
                val styledName = theme.kind(sym.kind, signaturePlain.substringBefore(" · "))
                val styledKind = theme.kind(sym.kind, signaturePlain.substringAfter(" · "))
                "$styledName ${theme.muted("·")} $styledKind"
            } else {
                theme.kind(sym.kind, signaturePlain)
            }
            val rendered = "$prefix$renderedSignature" +
                if (locationPlain.isNotEmpty()) "  ${theme.fileHeader(locationPlain)}" else ""
            styledLine(plain = plain, rendered = rendered)
        }
        truncatedNote?.let { line("  └── $it", emphasis = LineEmphasis.DIM) }
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
        val location = cursor.symbol.location
        panel("declaration @ ${display.locationLabel(location)}") {
            val fileHeaderText = "file     ${display.locationLabel(location)}"
            styledLine(
                plain = fileHeaderText,
                rendered = "file     ${theme.fileHeader(display.locationLabel(location))}",
            )
            blank()
            val lines = readDeclarationContext(location)
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
