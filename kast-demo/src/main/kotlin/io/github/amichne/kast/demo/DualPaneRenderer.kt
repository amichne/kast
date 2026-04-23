package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid

/**
 * Renders a single dual-pane conversation: each turn becomes a full-width
 * "You: ..." prompt line followed by a two-column grid showing the
 * baseline and kast-augmented responses side-by-side.
 *
 * No I/O is performed; this function is pure given a [RenderScope].
 */
fun RenderScope.renderDualPaneConversation(
    conversation: DualPaneConversation,
    panelWidth: Int = 100,
) {
    val cellTextWidth = (panelWidth / 2 - 4).coerceAtLeast(1)

    for (turn in conversation.turns) {
        cyan(isBright = true) {
            textLine("You: ${truncate(turn.userPrompt, panelWidth - 2)}")
        }

        grid(
            Cols { star(); star() },
            characters = GridCharacters.BOX_THIN,
            targetWidth = panelWidth,
            paddingLeftRight = 1,
        ) {
            cell {
                white(isBright = true) { textLine("Baseline LLM") }
                textLine("─".repeat(cellTextWidth))
                for (line in turn.leftResponse) {
                    renderTonedLine(line, cellTextWidth)
                }
            }
            cell {
                white(isBright = true) { textLine("Kast-Augmented LLM") }
                textLine("─".repeat(cellTextWidth))
                for (line in turn.rightResponse) {
                    renderTonedLine(line, cellTextWidth)
                }
            }
        }
    }
}

/**
 * Renders the full demo-gen screen: act header for the active conversation,
 * the dual-pane comparison, and (when more than one conversation is loaded)
 * a dim footer hint summarizing keyboard controls.
 */
fun RenderScope.renderDemoGenScreen(
    screen: DemoGenScreen,
    panelWidth: Int = 100,
) {
    val active = screen.active ?: return
    renderActHeader(
        actNumber = screen.activeIndex + 1,
        totalActs = screen.conversations.size,
        title = "Symbol: ${active.simpleName}",
        subtitle = active.symbolFqn,
    )
    textLine()
    renderDualPaneConversation(active, panelWidth)
    if (screen.conversations.size > 1) {
        black(isBright = true) {
            textLine("[1/2/3] switch  [R] replay  [\u2191/\u2193] scroll  [Q/Esc] quit")
        }
    }
}

private fun RenderScope.renderTonedLine(line: ConversationLine, width: Int) {
    val text = truncate(line.text, width)
    when (line.tone) {
        ConversationTone.SUCCESS -> green { textLine(text) }
        ConversationTone.WARNING -> yellow { textLine(text) }
        ConversationTone.ERROR -> red { textLine(text) }
        ConversationTone.DIM -> black(isBright = true) { textLine(text) }
        ConversationTone.USER_PROMPT -> cyan { textLine(text) }
        ConversationTone.NORMAL -> textLine(text)
    }
}

private fun truncate(text: String, width: Int): String = when {
    width <= 0 -> ""
    text.length <= width -> text
    width == 1 -> "\u2026"
    else -> text.take(width - 1) + "\u2026"
}
