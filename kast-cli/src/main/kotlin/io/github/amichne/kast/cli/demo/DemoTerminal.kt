package io.github.amichne.kast.cli.demo

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.widgets.HorizontalRule
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text
import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.cli.DemoReport
import io.github.amichne.kast.cli.DemoTextMatchCategory
import io.github.amichne.kast.cli.DemoTextSearchSummary
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Mordant-backed rendering surface for `kast demo`. Every byte of
 * user-visible terminal output for the demo flows through this class.
 *
 * Two emit modes:
 *   - **interactive** (no [sink]): widgets go straight to [terminal].
 *   - **captured** ([sink] supplied): widgets are rendered to a string and
 *     handed to [sink], so tests and the streaming orchestrator can route
 *     them anywhere.
 */
internal class DemoTerminal(
    val terminal: Terminal = Terminal(),
    private val sink: ((String) -> Unit)? = null,
) {
    fun emit(widget: Widget) {
        if (sink != null) sink(terminal.render(widget)) else terminal.println(widget)
    }

    fun emit(text: String) {
        if (sink != null) sink(text) else terminal.println(text)
    }

    fun blankLine() {
        if (sink != null) sink("") else terminal.println()
    }

    val width: Int get() = terminal.info.width.coerceAtLeast(80)

    /** True when this terminal writes directly to a real [Terminal] (no captured sink). */
    val isInteractive: Boolean get() = sink == null

    // --- Spec-driven act renderers -----------------------------------------

    fun openingBanner(workspaceRoot: Path): Widget = Panel(
        content = Text(
            buildString {
                append(STYLE_TITLE("kast demo"))
                appendLine()
                append(STYLE_DIM("semantic analysis vs grep — three acts, one symbol"))
                appendLine()
                appendLine()
                append("Workspace  ").append(STYLE_HEAD(workspaceRoot.toString()))
            },
            whitespace = Whitespace.PRE,
        ),
        title = Text(STYLE_HEAD("Act 0 of 3 — Setup")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    fun targetPanel(workspaceRoot: Path, symbol: Symbol): Widget {
        val rel = relativise(workspaceRoot, symbol.location.filePath)
        val body = buildString {
            append("Symbol  ").appendLine(STYLE_HEAD(symbol.fqName))
            append("Kind    ").appendLine(symbol.kind.name.lowercase())
            symbol.visibility?.let { append("Vis     ").appendLine(it.toString().lowercase()) }
            append("File    ").appendLine("$rel:${symbol.location.startLine}")
            appendLine()
            append(STYLE_DIM("Find semantic references — preview a safe rename — trace incoming callers."))
        }
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("demo target")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    fun progress(message: String): Widget = Text(
        "${STYLE_PROG("›")} $message",
        whitespace = Whitespace.PRE,
    )

    fun stepOutcome(message: String, success: Boolean, elapsed: Duration?): Widget {
        val icon = if (success) STYLE_OK("✓") else STYLE_FAIL("✕")
        val tail = elapsed?.let { " ${STYLE_DIM("(${formatDuration(it)})")}" }.orEmpty()
        return Text("$icon $message$tail", whitespace = Whitespace.PRE)
    }

    fun stepFailureBody(message: String): Widget =
        Text("  ${STYLE_FAIL(message)}", whitespace = Whitespace.PRE)

    // --- Act 1 -------------------------------------------------------------

    fun act1TextSearchBaseline(
        workspaceRoot: Path,
        symbolName: String,
        summary: DemoTextSearchSummary,
        includeHeader: Boolean = true,
    ): Widget = Text(
        buildString {
            if (includeHeader) {
                appendLine(framedHeader("Act 1 of 3 — Text Search", "grep -rn \"$symbolName\" --include=\"*.kt\""))
                appendLine()
            }
            appendLine("  Scanning... ${progressBar(summary.totalMatches)}  ${summary.totalMatches} hits")
            appendLine()
            append(renderTextTable(
                indent = "  ",
                headers = listOf("Category", "Count", "Example"),
                widths = listOf(16, 5, 28),
                rows = listOf(
                    listOf("String literals", countOf(summary, DemoTextMatchCategory.STRING).toString(), sampleExample(summary, DemoTextMatchCategory.STRING)),
                    listOf("Comments", countOf(summary, DemoTextMatchCategory.COMMENT).toString(), sampleExample(summary, DemoTextMatchCategory.COMMENT)),
                    listOf("Unrelated scope", unrelatedScopeCount(summary).toString(), unrelatedScopeExample(summary)),
                    listOf("Possible matches", summary.likelyCorrect.toString(), sampleExample(summary, DemoTextMatchCategory.LIKELY_CORRECT)),
                ),
                alignments = listOf(CellAlignment.LEFT, CellAlignment.RIGHT, CellAlignment.LEFT),
            ))
            appendLine()
            appendLine()
            append("  ").append(STYLE_FAIL("${summary.totalMatches} grep hits. No type information. No scope. Just noise."))
        },
        whitespace = Whitespace.PRE,
    )

    /**
     * Drives a Mordant streaming animation to convey the volume of grep
     * hits arriving live. The caller is responsible for gating invocation
     * (e.g. only when a real terminal reader is available). After the
     * animation completes, the caller should emit [act1TextSearchBaseline]
     * with `includeHeader = false` to avoid a duplicate header.
     */
    suspend fun act1StreamingAnimation(
        symbolName: String,
        estimatedTotal: Int,
        onComplete: () -> Unit,
    ) {
        val header = framedHeader("Act 1 of 3 — Text Search", "grep -rn \"$symbolName\" --include=\"*.kt\"")
        if (estimatedTotal <= 0) {
            emit(header)
            emit("")
            onComplete()
            return
        }
        emit(header)
        emit("")
        if (sink != null) {
            // Captured / test mode — no real terminal for animation, just
            // emit the final state so the header is still printed once.
            onComplete()
            return
        }
        val animation = terminal.animation<Int> { hits ->
            val ratio = (hits.toDouble() / estimatedTotal).coerceIn(0.0, 1.0)
            val filled = (ratio * STREAM_BAR_WIDTH).toInt().coerceIn(0, STREAM_BAR_WIDTH)
            val bar = "█".repeat(filled) + "░".repeat(STREAM_BAR_WIDTH - filled)
            Text("  Scanning... $bar  $hits hits", whitespace = Whitespace.PRE)
        }
        try {
            for (hits in 0..estimatedTotal) {
                animation.update(hits)
                kotlinx.coroutines.delay(STREAM_TICK_MS)
            }
            kotlinx.coroutines.delay(STREAM_HOLD_MS)
        } finally {
            animation.clear()
            onComplete()
        }
    }

    private fun countOf(summary: DemoTextSearchSummary, category: DemoTextMatchCategory): Int =
        summary.categoryCounts[category] ?: 0

    /** Combined count for "Unrelated scope" (imports + substring collisions). */
    private fun unrelatedScopeCount(summary: DemoTextSearchSummary): Int =
        countOf(summary, DemoTextMatchCategory.IMPORT) + countOf(summary, DemoTextMatchCategory.SUBSTRING)

    /** Pick an example from whichever unrelated-scope category has one. */
    private fun unrelatedScopeExample(summary: DemoTextSearchSummary): String {
        val importExample = sampleExample(summary, DemoTextMatchCategory.IMPORT)
        if (importExample.isNotEmpty()) return importExample
        return sampleExample(summary, DemoTextMatchCategory.SUBSTRING)
    }

    // --- Act 2 -------------------------------------------------------------

    fun act2Semantic(
        workspaceRoot: Path,
        textSearch: DemoTextSearchSummary,
        resolvedSymbol: Symbol,
        references: ReferencesResult,
        rippleEnabled: Boolean = true,
    ): Widget = Text(
        buildString {
            val simpleName = resolvedSymbol.fqName.substringAfterLast('.')
            appendLine(framedHeader("Act 2 of 3 — Symbol Resolution", "kast resolve \"$simpleName\" → ${resolvedHeadline(resolvedSymbol)}"))
            appendLine()
            appendLine("  Declared in: ${relativise(workspaceRoot, resolvedSymbol.location.filePath)}:${resolvedSymbol.location.startLine}")
            appendLine("  Type:        ${resolvedSymbol.location.preview.trim()}")
            appendLine()
            val resolvedTypeLabel = resolvedTypeLabel(resolvedSymbol)
            append(renderTextTable(
                indent = "  ",
                headers = listOf("File", "Line", "Kind", "Resolved Type", "Module"),
                widths = listOf(30, 4, 5, 18, 14),
                rows = references.references.map { ref ->
                    listOf(
                        displayPath(workspaceRoot, ref.filePath),
                        ref.startLine.toString(),
                        referenceKind(ref.preview),
                        resolvedTypeLabel,
                        ":${inferModule(relativise(workspaceRoot, ref.filePath))}",
                    )
                },
                alignments = listOf(
                    CellAlignment.LEFT,
                    CellAlignment.RIGHT,
                    CellAlignment.LEFT,
                    CellAlignment.LEFT,
                    CellAlignment.LEFT,
                ),
            ))
            appendLine()
            appendLine()
            appendLine("  ${"─".repeat(66)}")
            appendLine("  ${textMatchSummary(textSearch.totalMatches, references.references.size, resolvedSymbol)}")
            appendLine("  Noise eliminated: ${STYLE_OK("${noiseEliminatedPercent(textSearch.totalMatches, references.references.size)}%")}")
            append("  ${"─".repeat(66)}")
            if (rippleEnabled) {
                appendLine()
                append("  ").append(STYLE_DIM("[Enter] → explore caller graph"))
            }
        },
        whitespace = Whitespace.PRE,
    )

    private fun act2ReferenceTable(
        workspaceRoot: Path,
        references: ReferencesResult,
    ): Widget = table {
        borderStyle = TextStyles.dim.style
        borderType = com.github.ajalt.mordant.rendering.BorderType.ROUNDED
        header {
            style = STYLE_HEAD
            row("File", "Line", "Module", "Preview")
        }
        body {
            references.references.take(REF_LIMIT).forEach { ref ->
                val rel = relativise(workspaceRoot, ref.filePath)
                val module = inferModule(rel)
                row(
                    rel,
                    ref.startLine.toString(),
                    moduleColor(module)("[$module]"),
                    TextFit.truncate(ref.preview.trim(), 60),
                )
            }
        }
        if (references.references.size > REF_LIMIT) {
            footer {
                style = TextStyles.dim.style
                row {
                    cell("… and ${references.references.size - REF_LIMIT} more references") {
                        columnSpan = 4
                    }
                }
            }
        }
    }

    private fun act2RenamePanel(
        workspaceRoot: Path,
        resolvedSymbol: Symbol,
        rename: RenameResult,
    ): Widget {
        val simple = resolvedSymbol.fqName.substringAfterLast('.')
        val newName = "${simple}Renamed"
        val body = buildString {
            append(STYLE_OK("${rename.edits.size} edits")).append(" across ")
            append(STYLE_OK("${rename.affectedFiles.size} files")).append(" — ")
            append(STYLE_DIM("${rename.fileHashes.size} pre-image hashes captured."))
            appendLine()
            rename.affectedFiles.take(FILE_PREVIEW).forEach { path ->
                appendLine("  ${relativise(workspaceRoot, path)}")
            }
            if (rename.affectedFiles.size > FILE_PREVIEW) {
                append(STYLE_DIM("  … and ${rename.affectedFiles.size - FILE_PREVIEW} more"))
            }
        }.trimEnd()
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("rename --dry-run  ($simple → $newName)")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    private fun act2CallSummary(callHierarchy: CallHierarchyResult): Widget {
        val stats = callHierarchy.stats
        val truncated = stats.timeoutReached || stats.maxTotalCallsReached
        val body = buildString {
            append("incoming callers: ").appendLine(STYLE_OK(stats.totalNodes.toString()))
            append("max depth:        ").appendLine(stats.maxDepthReached.toString())
            append("files visited:    ").appendLine(stats.filesVisited.toString())
            if (truncated) append(STYLE_WARN("⚠ results truncated"))
        }.trimEnd()
        return Text(body, whitespace = Whitespace.PRE)
    }

    // --- Act 3 -------------------------------------------------------------

    fun act3CallerTree(
        workspaceRoot: Path,
        callHierarchy: CallHierarchyResult,
        depth: Int,
    ): Widget = Text(
        buildString {
            appendLine(framedHeader("Act 3 of 3 — Caller Graph (depth $depth)"))
            appendLine()
            val lines = renderCallTreeLines(workspaceRoot, callHierarchy.root)
            if (lines.isEmpty()) {
                appendLine("  No callers found — this is a root entry point.")
            } else {
                lines.forEach { appendLine("  $it") }
            }
            appendLine()
            val moduleCount = moduleCount(workspaceRoot, callHierarchy.root)
            val totalNodes = callHierarchy.stats.totalNodes
            appendLine(
                "  ${STYLE_OK("$moduleCount modules")}. " +
                    "${STYLE_OK("$totalNodes symbols")} reachable in ${STYLE_OK("$depth hops")}."
            )
            appendLine("  ${STYLE_DIM("Every edge is a compiler-verified call site.")}")
            append(
                "  ${STYLE_DIM("kast demo --symbol ${callHierarchy.root.symbol.fqName} --depth ${depth + 1}")}"
            )
        },
        whitespace = Whitespace.PRE,
    )

    fun walkerSkippedPanel(): Widget = Panel(
        content = Text(
            STYLE_DIM("pass --walk=true with a real terminal to hop between references, callers, and callees."),
            whitespace = Whitespace.PRE,
        ),
        title = Text(STYLE_HEAD("Act 3 — interactive walker · skipped")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    private fun renderCallTreeLines(workspaceRoot: Path, root: CallNode): List<String> {
        val lines = mutableListOf<String>()
        val targetWidth = this@DemoTerminal.width - 2 // subtract the 2-space indent prepended by callers
        fun walk(node: CallNode, prefix: String, isLast: Boolean, depth: Int) {
            val sym = node.symbol
            val branch = if (depth == 0) "" else if (isLast) "└── " else "├── "
            val rawLabel = when {
                depth == 0 -> resolvedHeadline(sym)
                sym.kind == SymbolKind.FUNCTION -> "${resolvedHeadline(sym)}()"
                else -> resolvedHeadline(sym)
            }
            val styledLabel = when (depth) {
                0 -> TextColors.brightCyan(rawLabel)
                1 -> TextColors.brightYellow(rawLabel)
                else -> rawLabel
            }
            val rel = relativise(workspaceRoot, sym.location.filePath)
            val module = inferModule(rel)
            val moduleTag = "[:$module]"
            val styledModuleTag = moduleColor(module)(moduleTag)
            // Width math uses *visible* lengths, not the styled (ANSI-wrapped) strings.
            val padding = (targetWidth - prefix.length - branch.length - rawLabel.length - moduleTag.length)
                .coerceAtLeast(1)
            lines += "$prefix$branch$styledLabel${" ".repeat(padding)}$styledModuleTag"
            val nextPrefix = prefix + if (depth == 0) "" else if (isLast) "    " else "│   "
            node.children.forEachIndexed { idx, child ->
                walk(child, nextPrefix, idx == node.children.lastIndex, depth + 1)
            }
        }

        walk(root, "", isLast = true, depth = 0)
        return lines
    }

    // --- Comparison + closing ---------------------------------------------

    fun comparisonSummary(report: DemoReport): Widget = SequenceWidget(buildList {
        add(sectionHeading("Side-by-side summary"))
        add(comparisonTable(report))
        add(Text(""))
        add(deltaPanel(report))
    })

    private fun comparisonTable(report: DemoReport): Widget = table {
        borderStyle = TextStyles.dim.style
        borderType = com.github.ajalt.mordant.rendering.BorderType.ROUNDED
        header {
            style = STYLE_HEAD
            row("Metric", "grep + sed", "kast")
        }
        body {
            row(
                "Matches",
                "${report.textSearch.totalMatches} total / ${report.textSearch.likelyCorrect} likely true",
                STYLE_OK("${report.references.references.size} semantic references"),
            )
            row("Symbol identity", STYLE_FAIL("text only"), STYLE_OK("exact identity"))
            row("Kind awareness", STYLE_FAIL("none"), STYLE_OK(report.resolvedSymbol.kind.name.lowercase()))
            row(
                "Call graph",
                STYLE_FAIL("none"),
                STYLE_OK("${report.callHierarchy.stats.totalNodes} incoming callers"),
            )
            row(
                "Rename plan",
                STYLE_FAIL("blind sed across ${report.textSearch.filesTouched} files"),
                STYLE_OK("${report.rename.edits.size} edits / ${report.rename.affectedFiles.size} files"),
            )
            row(
                "Coverage signal",
                STYLE_FAIL("none"),
                report.references.searchScope?.let {
                    STYLE_OK("exhaustive=${it.exhaustive} (${it.searchedFileCount}/${it.candidateFileCount})")
                } ?: STYLE_DIM("scope unavailable"),
            )
            row(
                "Conflict detection",
                STYLE_FAIL("none"),
                STYLE_OK("${report.rename.fileHashes.size} pre-image hashes"),
            )
            row(
                "Post-edit checks",
                STYLE_FAIL("manual"),
                STYLE_OK("kast diagnostics"),
            )
        }
    }

    private fun deltaPanel(report: DemoReport): Widget {
        val grep = report.textSearch.totalMatches
        val semantic = report.references.references.size
        val noisePct = if (grep > 0) ((grep - semantic).coerceAtLeast(0).toDouble() / grep * 100.0).toInt() else 0
        val body = buildString {
            append(STYLE_HEAD("$grep text matches")).append("  →  ")
            append(STYLE_OK("$semantic semantic references"))
            appendLine()
            append("Noise eliminated: ").append(STYLE_OK("$noisePct%"))
        }
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("delta")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    fun closingPanel(): Widget = Panel(
        content = Text(
            buildString {
                appendLine(STYLE_HEAD("kast resolves identity. grep matches text."))
                appendLine()
                appendLine("• Semantic references avoid imports, comments, strings, and substrings.")
                appendLine("• Rename previews carry SHA-256 file hashes for safe edits.")
                appendLine("• The caller graph is a compiler-verified BFS — every edge is a real call.")
                appendLine()
                append(STYLE_DIM("Docs  https://amichne.github.io/kast/"))
                appendLine()
                append(STYLE_DIM("Repo  https://github.com/amichne/kast"))
            }.trimEnd(),
            whitespace = Whitespace.PRE,
        ),
        title = Text(STYLE_HEAD("why the semantic pass wins")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    // --- Walker views ------------------------------------------------------

    fun walkerIntro(): Widget = Panel(
        content = Text(
            buildString {
                appendLine(STYLE_HEAD("interactive walker"))
                appendLine()
                appendLine("Hop between references, callers, and callees — every move is anchored to symbol identity.")
                appendLine()
                append("r <n>  jump to reference #n        c <n>  jump to incoming caller #n").appendLine()
                append("o <n>  jump to outgoing callee #n  g [n]  compare against grep (n lines)").appendLine()
                append("s      show current declaration    b      pop the last hop").appendLine()
                append("h      help                         q      finish the walker")
            },
            whitespace = Whitespace.PRE,
        ),
        title = Text(STYLE_HEAD("Act 3 · walk the symbol graph")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    fun walkerCursor(cursor: SymbolCursor, workspaceRoot: Path): Widget {
        val sym = cursor.symbol
        val body = buildString {
            append("name     ").appendLine(STYLE_HEAD(sym.fqName.substringAfterLast('.')))
            append("kind     ").appendLine(sym.kind.name.lowercase())
            sym.visibility?.let { append("vis      ").appendLine(it.toString().lowercase()) }
            val rel = relativise(workspaceRoot, sym.location.filePath)
            append("file     ").appendLine("$rel:${sym.location.startLine}")
            sym.containingDeclaration?.takeIf { it.isNotBlank() }?.let {
                append("inside   ").appendLine(it)
            }
            appendLine()
            appendLine(STYLE_HEAD("references (${cursor.references.size})"))
            renderRefBranch(this, workspaceRoot, cursor.references)
            appendLine()
            appendLine(STYLE_HEAD("incoming callers (${cursor.incomingCallers.size})"))
            renderSymBranch(this, workspaceRoot, "c", cursor.incomingCallers)
            appendLine()
            appendLine(STYLE_HEAD("outgoing callees (${cursor.outgoingCallees.size})"))
            renderSymBranch(this, workspaceRoot, "o", cursor.outgoingCallees)
        }.trimEnd()
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("current node · ${sym.fqName}")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    private fun renderRefBranch(
        out: StringBuilder,
        workspaceRoot: Path,
        refs: List<Location>,
    ) {
        if (refs.isEmpty()) {
            out.appendLine("  └── ${STYLE_DIM("no semantic references")}")
            return
        }
        val shown = refs.take(WALK_PREVIEW)
        shown.forEachIndexed { i, ref ->
            val n = i + 1
            val isLast = i == shown.lastIndex && refs.size <= WALK_PREVIEW
            val elbow = if (isLast) "└──" else "├──"
            val rel = relativise(workspaceRoot, ref.filePath)
            val loc = "$rel:${ref.startLine}"
            val preview = TextFit.truncate(ref.preview.trim(), 50)
            out.appendLine("  $elbow [r $n]  ${STYLE_FILE(loc)}  ${STYLE_DIM(preview)}")
        }
        if (refs.size > WALK_PREVIEW) {
            out.appendLine("  └── ${STYLE_DIM("... and ${refs.size - WALK_PREVIEW} more")}")
        }
    }

    private fun renderSymBranch(
        out: StringBuilder,
        workspaceRoot: Path,
        token: String,
        symbols: List<Symbol>,
    ) {
        if (symbols.isEmpty()) {
            val label = if (token == "c") "callers" else "callees"
            out.appendLine("  └── ${STYLE_DIM("no $label found at depth 1")}")
            return
        }
        val shown = symbols.take(WALK_PREVIEW)
        shown.forEachIndexed { i, sym ->
            val n = i + 1
            val isLast = i == shown.lastIndex && symbols.size <= WALK_PREVIEW
            val elbow = if (isLast) "└──" else "├──"
            val name = sym.fqName.substringAfterLast('.')
            val kind = sym.kind.name.lowercase()
            val rel = relativise(workspaceRoot, sym.location.filePath)
            val loc = "$rel:${sym.location.startLine}"
            out.appendLine(
                "  $elbow [$token $n]  ${STYLE_HEAD(name)} ${STYLE_DIM("·")} $kind  ${STYLE_FILE(loc)}"
            )
        }
        if (symbols.size > WALK_PREVIEW) {
            out.appendLine("  └── ${STYLE_DIM("... and ${symbols.size - WALK_PREVIEW} more")}")
        }
    }

    fun walkerHelp(): Widget = Panel(
        content = Text(
            buildString {
                appendLine("r <n>  jump to reference #n")
                appendLine("c <n>  jump to incoming caller #n")
                appendLine("o <n>  jump to outgoing callee #n")
                appendLine("g [n]  run grep on the current simple name and show n lines (default 6)")
                appendLine("s      show the declaration line")
                appendLine("b      pop the last hop")
                appendLine("h ?    show this help")
                append("q      end the walker and continue the demo")
            },
            whitespace = Whitespace.PRE,
        ),
        title = Text(STYLE_HEAD("walker commands")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    fun walkerError(message: String): Widget = Panel(
        content = Text(STYLE_FAIL(message), whitespace = Whitespace.PRE),
        title = Text(STYLE_FAIL("walker error")),
        titleAlign = TextAlign.LEFT,
        borderStyle = STYLE_BORDER,
    )

    fun walkerDeclaration(workspaceRoot: Path, cursor: SymbolCursor, lines: List<String>): Widget {
        val location = cursor.symbol.location
        val rel = relativise(workspaceRoot, location.filePath)
        val body = buildString {
            append("file     ").appendLine(STYLE_FILE("$rel:${location.startLine}"))
            appendLine()
            if (lines.isEmpty()) {
                append(STYLE_DIM("(file unreadable from walker)"))
            } else {
                lines.forEach { appendLine(it) }
            }
        }.trimEnd()
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("declaration @ $rel:${location.startLine}")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    fun walkerGrep(simpleName: String, outcome: Result<List<String>>): Widget {
        val body = outcome.fold(
            onSuccess = { lines ->
                buildString {
                    if (lines.isEmpty()) {
                        append(STYLE_DIM("(no text matches found)"))
                    } else {
                        lines.forEach { appendLine(it) }
                        appendLine()
                        append(STYLE_DIM(
                            "grep cannot tell which row is the current node, which are imports, " +
                                "and which are strings. The walker above already did."
                        ))
                    }
                }.trimEnd()
            },
            onFailure = { STYLE_FAIL("grep unavailable: ${it.message}") },
        )
        return Panel(
            content = Text(body, whitespace = Whitespace.PRE),
            title = Text(STYLE_HEAD("grep '$simpleName' — the same question without identity")),
            titleAlign = TextAlign.LEFT,
            borderStyle = STYLE_BORDER,
        )
    }

    fun walkerPrompt(): Widget = Text("${STYLE_PROG("walker›")} ", whitespace = Whitespace.PRE)

    // --- helpers -----------------------------------------------------------

    private fun sectionHeading(title: String): Widget =
        HorizontalRule(title = Text(STYLE_HEAD(title)), ruleStyle = STYLE_BORDER)

    private fun DemoTextMatchCategory.label(): String = when (this) {
        DemoTextMatchCategory.LIKELY_CORRECT -> "likely correct"
        DemoTextMatchCategory.IMPORT -> "import"
        DemoTextMatchCategory.COMMENT -> "comment"
        DemoTextMatchCategory.STRING -> "string"
        DemoTextMatchCategory.SUBSTRING -> "substring"
    }

    private fun relativise(workspaceRoot: Path, filePath: String): String {
        val absolute = Path.of(filePath).toAbsolutePath().normalize()
        val root = workspaceRoot.toAbsolutePath().normalize()
        return if (absolute.startsWith(root)) root.relativize(absolute).toString() else absolute.toString()
    }

    /** Best-effort module label from a relative path (`foo/bar/Baz.kt` → `foo`). */
    private fun inferModule(relPath: String): String {
        val first = relPath.substringBefore('/', "")
        return if (first.isBlank() || first.endsWith(".kt")) "root" else first
    }

    private fun framedHeader(title: String, subtitle: String? = null): String = buildString {
        appendLine("┌${"─".repeat(HEADER_WIDTH)}┐")
        appendLine("│${padHeaderLine("  $title")}│")
        subtitle?.let { appendLine("│${padHeaderLine("  $it")}│") }
        append("└${"─".repeat(HEADER_WIDTH)}┘")
    }

    private fun padHeaderLine(text: String): String = text.padEnd(HEADER_WIDTH)

    private fun progressBar(totalMatches: Int): String {
        val filled = if (totalMatches == 0) 0 else minOf(20, maxOf(1, (totalMatches / 2.0).toInt() + 1))
        return "█".repeat(filled) + "░".repeat(24 - filled)
    }

    private fun sampleExample(summary: DemoTextSearchSummary, category: DemoTextMatchCategory): String =
        summary.sampleMatches.firstOrNull { it.category == category }?.preview?.let { clip(it, 28) }.orEmpty()

    private fun displayPath(workspaceRoot: Path, filePath: String): String {
        val rel = relativise(workspaceRoot, filePath)
        val module = inferModule(rel)
        return "$module/${Path.of(rel).fileName}"
    }

    private fun resolvedHeadline(symbol: Symbol): String {
        val owner = symbol.containingDeclaration?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        val simple = symbol.fqName.substringAfterLast('.')
        return if (owner == null) simple else "$owner.$simple"
    }

    /**
     * Containing-class/type label rendered in the Act 2 reference table.
     * Falls back to the symbol's simple name when no owner is known.
     */
    private fun resolvedTypeLabel(symbol: Symbol): String =
        symbol.containingDeclaration
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: symbol.fqName.substringAfterLast('.')

    private fun referenceKind(preview: String): String =
        if (preview.contains("(") && preview.contains(")")) "call" else "ref"

    private fun textMatchSummary(totalMatches: Int, referenceCount: Int, resolvedSymbol: Symbol): String {
        return "${totalMatches} text matches  →  $referenceCount actual references to ${resolvedHeadline(resolvedSymbol)}"
    }

    private fun noiseEliminatedPercent(totalMatches: Int, referenceCount: Int): Int {
        return if (totalMatches == 0) 0 else (((totalMatches - referenceCount).toDouble() / totalMatches) * 100.0).toInt()
    }

    private fun moduleCount(workspaceRoot: Path, root: CallNode): Int = buildSet {
        fun visit(node: CallNode) {
            add(inferModule(relativise(workspaceRoot, node.symbol.location.filePath)))
            node.children.forEach(::visit)
        }
        visit(root)
    }.size

    private fun renderTextTable(
        indent: String,
        headers: List<String>,
        widths: List<Int>,
        rows: List<List<String>>,
        alignments: List<CellAlignment>,
    ): String {
        fun border(left: String, middle: String, right: String): String =
            buildString {
                append(indent).append(left)
                widths.forEachIndexed { index, width ->
                    append("─".repeat(width + 2))
                    append(if (index == widths.lastIndex) right else middle)
                }
            }

        fun row(cells: List<String>): String = buildString {
            append(indent).append("│")
            cells.forEachIndexed { index, cell ->
                append(" ")
                append(padCell(clip(cell, widths[index]), widths[index], alignments[index]))
                append(" │")
            }
        }

        return buildString {
            appendLine(border("┌", "┬", "┐"))
            appendLine(row(headers))
            appendLine(border("├", "┼", "┤"))
            rows.forEachIndexed { index, values ->
                append(row(values))
                if (index != rows.lastIndex) appendLine()
            }
            appendLine()
            append(border("└", "┴", "┘"))
        }
    }

    private fun padCell(text: String, width: Int, alignment: CellAlignment): String = when (alignment) {
        CellAlignment.LEFT -> text.padEnd(width)
        CellAlignment.RIGHT -> text.padStart(width)
    }

    private fun clip(text: String, width: Int): String = if (text.length <= width) text else text.take(width)

    private enum class CellAlignment {
        LEFT,
        RIGHT,
    }

    private fun moduleColor(module: String): TextStyle {
        val palette = listOf(
            TextColors.brightBlue,
            TextColors.brightMagenta,
            TextColors.brightCyan,
            TextColors.brightYellow,
            TextColors.brightGreen,
            TextColors.cyan,
        )
        val idx = (module.hashCode() and Int.MAX_VALUE) % palette.size
        return palette[idx]
    }

    private fun formatDuration(duration: Duration): String {
        val ms = duration.toDouble(DurationUnit.MILLISECONDS)
        return if (ms < 1_000) "${ms.toInt()}ms" else "%.2fs".format(ms / 1_000)
    }

    companion object {
        const val SAMPLE_LIMIT = 6
        const val REF_LIMIT = 8
        const val FILE_PREVIEW = 6
        const val CALL_TREE_LIMIT = 12
        const val WALK_PREVIEW = 8
        const val HEADER_WIDTH = 53
        const val TREE_LABEL_WIDTH = 44
        const val STREAM_BAR_WIDTH = 24
        const val STREAM_TICK_MS = 50L
        const val STREAM_HOLD_MS = 1_200L

        private val STYLE_HEAD = TextColors.brightCyan + TextStyles.bold
        private val STYLE_TITLE = TextColors.brightCyan + TextStyles.bold
        private val STYLE_DIM: TextStyle = TextStyles.dim.style
        private val STYLE_OK = TextColors.brightGreen + TextStyles.bold
        private val STYLE_FAIL = TextColors.brightRed + TextStyles.bold
        private val STYLE_WARN: TextStyle = TextColors.brightYellow
        private val STYLE_PROG = TextColors.brightBlue + TextStyles.bold
        private val STYLE_BORDER: TextStyle = TextColors.brightCyan
        private val STYLE_FILE: TextStyle = TextColors.cyan

        /** Build a non-interactive [DemoTerminal] suitable for tests / capture. */
        fun captured(sink: (String) -> Unit, width: Int = 100): DemoTerminal =
            DemoTerminal(
                terminal = Terminal(
                    ansiLevel = AnsiLevel.NONE,
                    width = width,
                    interactive = false,
                    hyperlinks = false,
                ),
                sink = sink,
            )
    }
}

/** Minimal sequence widget that prints child widgets in order. */
internal class SequenceWidget(private val children: List<Widget>) : Widget {
    override fun measure(t: Terminal, width: Int): com.github.ajalt.mordant.rendering.WidthRange =
        children.map { it.measure(t, width) }.fold(
            com.github.ajalt.mordant.rendering.WidthRange(0, 0)
        ) { acc, w -> com.github.ajalt.mordant.rendering.WidthRange(maxOf(acc.min, w.min), maxOf(acc.max, w.max)) }

    override fun render(t: Terminal, width: Int): com.github.ajalt.mordant.rendering.Lines {
        val lines = children.flatMap { it.render(t, width).lines }
        return com.github.ajalt.mordant.rendering.Lines(lines)
    }
}
