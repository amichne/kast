package io.github.amichne.kast.cli

import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation

/**
 * Renders a [DemoGenScreen] as Markdown with one section per conversation
 * and a two-column GFM table per turn (baseline vs kast-augmented).
 */
internal object DemoGenMarkdownExporter {
    fun export(screen: DemoGenScreen): String {
        val builder = StringBuilder()
        for (conversation in screen.conversations) {
            appendConversation(builder, conversation)
        }
        if (builder.isEmpty() || builder.last() != '\n') {
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun appendConversation(builder: StringBuilder, conversation: DualPaneConversation) {
        builder.append("## ").append(conversation.symbolFqn).append('\n').append('\n')
        for (turn in conversation.turns) {
            appendTurn(builder, turn)
        }
    }

    private fun appendTurn(builder: StringBuilder, turn: ConversationTurn) {
        builder.append("### ").append(turn.userPrompt).append('\n').append('\n')
        builder.append("| Baseline LLM | Kast-Augmented LLM |\n")
        builder.append("|---|---|\n")
        val rowCount = maxOf(turn.leftResponse.size, turn.rightResponse.size)
        for (index in 0 until rowCount) {
            val left = cell(turn.leftResponse.getOrNull(index))
            val right = cell(turn.rightResponse.getOrNull(index))
            builder.append("| ").append(left).append(" | ").append(right).append(" |\n")
        }
        builder.append('\n')
    }

    private fun cell(line: ConversationLine?): String {
        val text = line?.text ?: ""
        return text.replace("|", "\\|").replace("\n", "<br>")
    }
}
