package io.github.amichne.kast.cli

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.InputReceiver
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.input.receiveKeyEvents
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import io.github.amichne.kast.indexstore.MetricsEngine
import java.nio.file.Path

internal class MetricsGraphPicker(
    private val workspaceRoot: Path,
    private val depth: Int,
    initialQuery: String = "",
    private val terminal: Terminal = Terminal(),
    private val engineFactory: () -> MetricsEngine = { MetricsEngine(workspaceRoot) },
    private val graphRunner: (io.github.amichne.kast.indexstore.MetricsGraph) -> Int = { graph ->
        MetricsGraphTerminal(graph).run()
    },
) {
    private val state = PickerState(query = initialQuery)

    fun run(): Int {
        engineFactory().use { engine ->
            refreshResults(engine)
            val animation = terminal.animation<PickerState> { snapshot -> render(snapshot) }
            terminal.cursor.hide(showOnExit = true)
            animation.update(state)

            var resolved: String? = null
            terminal.receiveKeyEvents { event ->
                if (event.isCtrlC) return@receiveKeyEvents InputReceiver.Status.Finished
                when (val action = event.toPickerAction()) {
                    PickerAction.Quit -> return@receiveKeyEvents InputReceiver.Status.Finished
                    PickerAction.Confirm -> {
                        val pick = state.results.getOrNull(state.selection)
                        if (pick != null) {
                            resolved = pick
                            return@receiveKeyEvents InputReceiver.Status.Finished
                        }
                    }

                    PickerAction.Up -> state.selection = (state.selection - 1).coerceAtLeast(0)
                    PickerAction.Down -> state.selection =
                        (state.selection + 1).coerceAtMost((state.results.size - 1).coerceAtLeast(0))

                    PickerAction.Backspace -> {
                        if (state.query.isNotEmpty()) {
                            state.query = state.query.dropLast(1)
                            refreshResults(engine)
                        }
                    }

                    is PickerAction.Type -> {
                        state.query += action.char
                        refreshResults(engine)
                    }

                    null -> Unit
                }
                animation.update(state)
                InputReceiver.Status.Continue
            }

            animation.clear()

            val pick = resolved ?: return 0
            val graph = engine.graph(fqName = pick, depth = depth)
            return graphRunner(graph)
        }
    }

    private fun refreshResults(engine: MetricsEngine) {
        state.results = engine.searchSymbols(state.query, limit = MAX_RESULTS)
        state.selection = state.selection.coerceIn(0, (state.results.size - 1).coerceAtLeast(0))
    }

    private fun render(snapshot: PickerState): Widget {
        val header = Panel(
            content = buildString {
                append(TextStyles.bold(TextColors.brightCyan("Pick a symbol")))
                append("  ")
                append(TextColors.gray("type to filter · ↑/↓ select · Enter open · Esc/Ctrl-C cancel"))
            },
            title = TextStyles.bold(TextColors.brightCyan("Kast graph picker")),
            expand = true,
            borderStyle = TextColors.brightCyan,
        )
        val prompt = Panel(
            content = buildString {
                append(TextColors.brightYellow("› "))
                append(snapshot.query)
                append(TextStyles.dim("▏"))
            },
            title = TextColors.brightBlue("Query"),
            expand = true,
            borderStyle = TextColors.brightBlue,
        )
        val resultsBody = if (snapshot.results.isEmpty()) {
            TextColors.gray(
                if (snapshot.query.isBlank()) {
                    "No indexed symbols. Run `kast metrics fan-in` once to populate the index."
                } else {
                    "No matches for \"${snapshot.query}\"."
                },
            )
        } else {
            snapshot.results.mapIndexed { index, fqName ->
                val displayName = simpleName(fqName)
                val qualifier = fqName.substringBeforeLast('.', missingDelimiterValue = "")
                val styled = if (index == snapshot.selection) {
                    val arrow = TextStyles.bold(TextColors.brightGreen("▶ "))
                    val name = TextStyles.bold(TextColors.brightWhite(displayName))
                    val tail = if (qualifier.isNotEmpty()) " " + TextColors.gray(qualifier) else ""
                    "$arrow$name$tail"
                } else {
                    val name = TextColors.white(displayName)
                    val tail = if (qualifier.isNotEmpty()) " " + TextColors.gray(qualifier) else ""
                    "  $name$tail"
                }
                styled
            }.joinToString("\n")
        }
        val results = Panel(
            content = resultsBody,
            title = TextColors.brightMagenta("Matches (${snapshot.results.size})"),
            expand = true,
            borderStyle = TextColors.brightMagenta,
        )
        return verticalLayout {
            spacing = 1
            cell(header)
            cell(prompt)
            cell(results)
        }
    }

    private fun simpleName(fqName: String): String =
        fqName.substringAfterLast('.').ifBlank { fqName }

    private data class PickerState(
        var query: String = "",
        var results: List<String> = emptyList(),
        var selection: Int = 0,
    )

    private sealed interface PickerAction {
        data object Up : PickerAction
        data object Down : PickerAction
        data object Confirm : PickerAction
        data object Quit : PickerAction
        data object Backspace : PickerAction
        data class Type(val char: Char) : PickerAction
    }

    private fun KeyboardEvent.toPickerAction(): PickerAction? {
        return when (key) {
            "ArrowUp" -> PickerAction.Up
            "ArrowDown" -> PickerAction.Down
            "Enter" -> PickerAction.Confirm
            "Escape" -> PickerAction.Quit
            "Backspace" -> PickerAction.Backspace
            else -> {
                if (key.length == 1) {
                    val ch = key[0]
                    if (ch.isLetterOrDigit() || ch in PRINTABLE_PICKER_CHARS) PickerAction.Type(ch) else null
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private const val MAX_RESULTS = 25
        private val PRINTABLE_PICKER_CHARS = setOf('.', '_', '-', '$', ':', '/', '<', '>')
    }
}
