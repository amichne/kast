package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.input.CharKey
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.render.RenderScope
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ── Picker result ──────────────────────────────────────────────────

internal sealed interface SymbolPickerResult {
    data class Selected(val symbol: Symbol) : SymbolPickerResult
    data object Cancelled : SymbolPickerResult
}

// ── Picker wiring ──────────────────────────────────────────────────

/**
 * Runs the interactive symbol picker phase. Renders chrome immediately,
 * warms the backend in background, then lets the user search and select.
 */
internal fun Session.runSymbolPicker(
    verbose: Boolean,
    minSearchChars: Int = SymbolPickerController.MIN_SEARCH_CHARS_DEFAULT,
    searchSymbols: suspend (WorkspaceSymbolQuery) -> WorkspaceSymbolResult,
    warmBackend: suspend () -> Unit,
): SymbolPickerResult {
    val controller = SymbolPickerController(verbose = verbose, minSearchChars = minSearchChars)
    var pickerState by liveVarOf(controller.state)
    var selectedSymbol: Symbol? = null
    var warmedUp by liveVarOf(false)
    var searchGeneration = 0
    var activeSearchJob: Job? = null

    section {
        renderSymbolPickerScreen(pickerState, warmedUp, width)
    }.runUntilSignal {
        // Warm backend in background
        section.coroutineScope.launch {
            warmBackend()
            warmedUp = true
            // Do an initial broad search to populate the symbol pool
            val result = searchSymbols(
                WorkspaceSymbolQuery(pattern = ".", maxResults = 500, regex = true),
            )
            controller.onSymbolsLoaded(result.symbols)
            pickerState = controller.state
        }

        onKeyPressed {
            when (key) {
                Keys.ESC -> {
                    selectedSymbol = null
                    signal()
                }
                Keys.UP -> {
                    controller.onUp()
                    pickerState = controller.state
                }
                Keys.DOWN -> {
                    controller.onDown()
                    pickerState = controller.state
                }
                Keys.ENTER -> {
                    controller.onEnter()?.let { sym ->
                        selectedSymbol = sym
                        signal()
                    }
                }
                Keys.TAB -> {
                    controller.onTab()
                    pickerState = controller.state
                }
                Keys.DELETE, Keys.BACKSPACE -> {
                    controller.onBackspace()
                    pickerState = controller.state
                    triggerSearch(controller, pickerState, searchGeneration++, activeSearchJob) { query, gen ->
                        activeSearchJob = section.coroutineScope.launch {
                            delay(SEARCH_DEBOUNCE_MS)
                            if (!isActive) return@launch
                            val result = searchSymbols(query)
                            if (gen == searchGeneration - 1) {
                                controller.onSymbolsLoaded(result.symbols)
                                pickerState = controller.state
                            }
                        }
                    }
                }
                is CharKey -> {
                    val ch = (key as CharKey).code
                    if (ch == ' ') {
                        controller.onSpace()
                    } else {
                        controller.onChar(ch)
                    }
                    pickerState = controller.state
                    triggerSearch(controller, pickerState, searchGeneration++, activeSearchJob) { query, gen ->
                        activeSearchJob = section.coroutineScope.launch {
                            delay(SEARCH_DEBOUNCE_MS)
                            if (!isActive) return@launch
                            val result = searchSymbols(query)
                            if (gen == searchGeneration - 1) {
                                controller.onSymbolsLoaded(result.symbols)
                                pickerState = controller.state
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    return selectedSymbol?.let { SymbolPickerResult.Selected(it) } ?: SymbolPickerResult.Cancelled
}

private inline fun triggerSearch(
    controller: SymbolPickerController,
    state: SymbolPickerState,
    generation: Int,
    activeJob: Job?,
    launcher: (WorkspaceSymbolQuery, Int) -> Unit,
) {
    if (state.searchText.length < controller.minSearchChars) return
    activeJob?.cancel()
    val query = WorkspaceSymbolQuery(
        pattern = state.searchText,
        maxResults = 500,
        regex = false,
    )
    launcher(query, generation)
}

private const val MAX_TERMINAL_WIDTH = 260

internal data class DemoLoadingStep(
    val label: String,
    val status: DemoLoadingStatus,
    val durationMs: Long? = null,
)

internal enum class DemoLoadingStatus {
    PENDING,
    ACTIVE,
    COMPLETE,
    FAILED,
}

internal fun Session.runLoadingPhase(
    symbolName: String,
    steps: List<String>,
    executeSteps: suspend (onStepComplete: (index: Int, durationMs: Long) -> Unit) -> Unit,
): Boolean {
    val loadingSteps = steps.mapIndexed { index, label ->
        DemoLoadingStep(
            label = label,
            status = if (index == 0) DemoLoadingStatus.ACTIVE else DemoLoadingStatus.PENDING,
        )
    }.toMutableList()
    var currentSteps by liveVarOf(loadingSteps.toList())
    var failed by liveVarOf(false)

    section {
        renderLoadingScreen(symbolName, currentSteps, width)
    }.runUntilSignal {
        section.coroutineScope.launch {
            try {
                executeSteps { index, durationMs ->
                    loadingSteps[index] = loadingSteps[index].copy(
                        status = DemoLoadingStatus.COMPLETE,
                        durationMs = durationMs,
                    )
                    if (index + 1 < loadingSteps.size) {
                        loadingSteps[index + 1] = loadingSteps[index + 1].copy(status = DemoLoadingStatus.ACTIVE)
                    }
                    currentSteps = loadingSteps.toList()
                }
                signal()
            } catch (e: Exception) {
                loadingSteps.forEachIndexed { i, step ->
                    if (step.status == DemoLoadingStatus.ACTIVE) {
                        loadingSteps[i] = step.copy(status = DemoLoadingStatus.FAILED)
                    }
                }
                currentSteps = loadingSteps.toList()
                failed = true
                delay(3000)
                signal()
            }
        }
    }

    return !failed
}

// ── Picker rendering ───────────────────────────────────────────────

private fun RenderScope.renderSymbolPickerScreen(
    state: SymbolPickerState,
    warmedUp: Boolean,
    terminalWidth: Int,
) {
    val termWidth = terminalWidth.coerceAtMost(MAX_TERMINAL_WIDTH)
    val panelWidth = (termWidth - 4).coerceAtLeast(40)

    structural { textLine("┌${"─".repeat(panelWidth + 2)}┐") }
    renderPickerTitleLine("kast demo — Symbol Search", panelWidth)
    structural { textLine("├${"─".repeat(panelWidth + 2)}┤") }

    // Search bar
    renderSearchBar(state, panelWidth, warmedUp)

    // Kind filter chips
    structural { textLine("├${"─".repeat(panelWidth + 2)}┤") }
    renderKindChips(state, panelWidth)

    // Results
    structural { textLine("├${"─".repeat(panelWidth + 2)}┤") }
    renderResultsSection(state, panelWidth)

    // Controls
    structural { textLine("├${"─".repeat(panelWidth + 2)}┤") }
    renderPickerControls(panelWidth)
    structural { textLine("└${"─".repeat(panelWidth + 2)}┘") }
}

private fun RenderScope.renderPickerTitleLine(title: String, width: Int) {
    val content = TextFit.truncate(title, width).padEnd(width)
    structural { text("│ ") }
    white(isBright = true) { text(content) }
    structural { textLine(" │") }
}

private fun RenderScope.renderSearchBar(state: SymbolPickerState, width: Int, warmedUp: Boolean) {
    structural { text("│ ") }
    if (!warmedUp) {
        yellow(isBright = true) { text("› ") }
        yellow { text("Warming workspace daemon") }
        val dots = ((System.currentTimeMillis() / 500) % 4).toInt()
        yellow { text(".".repeat(dots)) }
        val pad = width - 26 - dots
        if (pad > 0) text(" ".repeat(pad))
    } else {
        cyan(isBright = true) { text("› ") }
        text(state.searchText)
        cyan(isBright = true) { text("█") }
        val hint = if (state.needsMoreChars) {
            "  (case-sensitive, type ≥${SymbolPickerController.MIN_SEARCH_CHARS_DEFAULT} chars)"
        } else {
            "  (case-sensitive)"
        }
        black(isBright = true) { text(hint) }
        val used = 2 + state.searchText.length + 1 + hint.length
        val pad = width - used
        if (pad > 0) text(" ".repeat(pad))
    }
    structural { textLine(" │") }
}

private fun RenderScope.renderKindChips(state: SymbolPickerState, width: Int) {
    structural { text("│ ") }
    text("Kinds  ")
    var usedWidth = 7
    SymbolPickerController.FILTERABLE_KINDS.forEachIndexed { index, kind ->
        if (index > 0) {
            text(" ")
            usedWidth += 1
        }
        val enabled = state.kindFilters[kind] == true
        val focused = index == state.focusedKindIndex
        val label = "[${if (enabled) "✓" else " "} ${kind.name.lowercase()}]"
        usedWidth += label.length

        when {
            focused && enabled -> cyan(isBright = true) { text(label) }
            focused && !enabled -> yellow { text(label) }
            enabled -> text(label)
            else -> black(isBright = true) { text(label) }
        }
    }
    val pad = width - usedWidth
    if (pad > 0) text(" ".repeat(pad))
    structural { textLine(" │") }
}

private fun RenderScope.renderResultsSection(state: SymbolPickerState, width: Int) {
    if (state.needsMoreChars && state.searchText.isEmpty()) {
        renderPickerBodyLine("Start typing to search symbols...", width)
        return
    }
    if (state.needsMoreChars) {
        renderPickerBodyLine(
            "Type ${SymbolPickerController.MIN_SEARCH_CHARS_DEFAULT - state.searchText.length} more character(s)...",
            width,
        )
        return
    }
    if (state.displayRows.isEmpty()) {
        renderPickerBodyLine("No matches", width)
        return
    }

    // Results header
    val matchCount = state.filteredResults.size
    val showing = matchCount.coerceAtMost(SymbolPickerController.MAX_VISIBLE_RESULTS)
    val headerText = if (matchCount > showing) {
        "$matchCount matches (showing $showing)"
    } else {
        "$matchCount match${if (matchCount != 1) "es" else ""}"
    }
    renderPickerBodyLine(headerText, width)

    // Result rows
    val visibleRows = state.displayRows.take(SymbolPickerController.MAX_VISIBLE_RESULTS)
    visibleRows.forEachIndexed { index, row ->
        renderResultRow(row, index == state.selectedIndex, width)
    }
}

private fun RenderScope.renderResultRow(
    row: SymbolPickerDisplayRow,
    isSelected: Boolean,
    width: Int,
) {
    structural { text("│ ") }
    val marker = if (isSelected) "▸ " else "  "
    val kindBadge = row.kindLabel.padEnd(10)
    val context = if (row.contextHint.isNotEmpty()) "  ${row.contextHint}" else ""
    val rowText = "$marker${row.displayName.padEnd(30)} $kindBadge$context"
    val truncated = TextFit.truncate(rowText, width).padEnd(width)

    if (isSelected) {
        cyan(isBright = true) { text(truncated) }
    } else {
        text(truncated)
    }
    structural { textLine(" │") }
}

private fun RenderScope.renderPickerBodyLine(content: String, width: Int) {
    val padded = TextFit.truncate(content, width).padEnd(width)
    structural { text("│ ") }
    text(padded)
    structural { textLine(" │") }
}

private fun RenderScope.renderPickerControls(width: Int) {
    structural { text("│ ") }
    val line = "[↑/↓] Navigate  [Enter] Select  [Tab] Kind  [Space] Toggle  [Esc] Quit"
    val padded = TextFit.truncate(line, width).padEnd(width)
    black(isBright = true) { text(padded) }
    structural { textLine(" │") }
}

// ── Loading rendering ──────────────────────────────────────────────

private fun RenderScope.renderLoadingScreen(
    symbolName: String,
    steps: List<DemoLoadingStep>,
    terminalWidth: Int,
) {
    val termWidth = terminalWidth.coerceAtMost(MAX_TERMINAL_WIDTH)
    val panelWidth = (termWidth - 4).coerceAtLeast(40)

    structural { textLine("┌${"─".repeat(panelWidth + 2)}┐") }
    renderPickerTitleLine("Preparing Demo: $symbolName", panelWidth)
    structural { textLine("├${"─".repeat(panelWidth + 2)}┤") }
    steps.forEach { step ->
        renderLoadingStep(step, panelWidth)
    }
    structural { textLine("└${"─".repeat(panelWidth + 2)}┘") }
}

private fun RenderScope.renderLoadingStep(step: DemoLoadingStep, width: Int) {
    structural { text("│ ") }
    val timing = step.durationMs?.let { " (${formatMs(it)})" } ?: ""
    val content = when (step.status) {
        DemoLoadingStatus.PENDING -> "  ${step.label}"
        DemoLoadingStatus.ACTIVE -> "› ${step.label}..."
        DemoLoadingStatus.COMPLETE -> "✓ ${step.label}$timing"
        DemoLoadingStatus.FAILED -> "✕ ${step.label} — failed"
    }
    val padded = TextFit.truncate(content, width).padEnd(width)
    when (step.status) {
        DemoLoadingStatus.PENDING -> text(padded)
        DemoLoadingStatus.ACTIVE -> yellow(isBright = true) { text(padded) }
        DemoLoadingStatus.COMPLETE -> green(isBright = true) { text(padded) }
        DemoLoadingStatus.FAILED -> red(isBright = true) { text(padded) }
    }
    structural { textLine(" │") }
}

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> "%.2fs".format(ms / 1000.0)
}

// ── Structural helper ──────────────────────────────────────────────

private fun RenderScope.structural(block: RenderScope.() -> Unit) {
    black(isBright = true, scopedBlock = block)
}

private const val SEARCH_DEBOUNCE_MS = 300L
