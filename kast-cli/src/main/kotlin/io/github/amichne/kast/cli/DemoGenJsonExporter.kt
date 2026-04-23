package io.github.amichne.kast.cli

import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Serializes a [DemoGenScreen] to pretty-printed JSON via internal mirror DTOs
 * so that domain models in `:kast-demo` remain free of serialization concerns.
 */
internal object DemoGenJsonExporter {
    private val json = defaultCliJson()

    fun export(screen: DemoGenScreen): String {
        val dto = JsonScreen(
            activeIndex = screen.activeIndex,
            conversations = screen.conversations.map(::toDto),
        )
        return json.encodeToString(dto)
    }

    private fun toDto(conversation: DualPaneConversation): JsonConversation =
        JsonConversation(
            symbolFqn = conversation.symbolFqn,
            simpleName = conversation.simpleName,
            turns = conversation.turns.map(::toDto),
        )

    private fun toDto(turn: ConversationTurn): JsonTurn =
        JsonTurn(
            userPrompt = turn.userPrompt,
            leftResponse = turn.leftResponse.map(::toDto),
            rightResponse = turn.rightResponse.map(::toDto),
        )

    private fun toDto(line: ConversationLine): JsonLine =
        JsonLine(text = line.text, tone = line.tone.name)

    @Serializable
    private data class JsonScreen(
        val activeIndex: Int,
        val conversations: List<JsonConversation>,
    )

    @Serializable
    private data class JsonConversation(
        val symbolFqn: String,
        val simpleName: String,
        val turns: List<JsonTurn>,
    )

    @Serializable
    private data class JsonTurn(
        val userPrompt: String,
        val leftResponse: List<JsonLine>,
        val rightResponse: List<JsonLine>,
    )

    @Serializable
    private data class JsonLine(
        val text: String,
        val tone: String,
    )
}
