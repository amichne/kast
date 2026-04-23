package io.github.amichne.kast.cli

import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTone
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Lifecycle status written into the persisted artifact. */
internal enum class DemoGenArtifactStatus { IN_PROGRESS, PARTIAL, COMPLETED, FAILED }

/** One per-symbol failure recorded during progressive generation. */
internal data class SymbolFailure(val symbol: String, val reason: String)

/**
 * Full artifact model: screen content plus generation metadata.
 *
 * The [screen] field carries the renderable state at the time of writing. The
 * extra fields are a superset of the screen JSON, so existing consumers of
 * [DemoGenScreen.activeIndex] and [DemoGenScreen.conversations] are unaffected.
 */
internal data class DemoGenArtifact(
    val screen: DemoGenScreen,
    val generatedAt: String,
    val status: DemoGenArtifactStatus,
    val workspaceRoot: String,
    val repoUrl: String?,
    val failures: List<SymbolFailure> = emptyList(),
)

/**
 * Serializes a [DemoGenScreen] or full [DemoGenArtifact] to pretty-printed JSON
 * and deserializes the core screen from a stored artifact — all via internal mirror
 * DTOs so domain models in `:kast-demo` remain free of serialization concerns.
 */
internal object DemoGenJsonExporter {
    private val json = defaultCliJson()

    /** Serialize only the screen (legacy / backward-compat path for headless export). */
    fun export(screen: DemoGenScreen): String {
        val dto = JsonArtifact(
            activeIndex = screen.activeIndex,
            conversations = screen.conversations.map(::toDto),
        )
        return json.encodeToString(dto)
    }

    /** Serialize the full artifact including generation metadata. */
    fun exportArtifact(artifact: DemoGenArtifact): String {
        val dto = JsonArtifact(
            activeIndex = artifact.screen.activeIndex,
            conversations = artifact.screen.conversations.map(::toDto),
            generatedAt = artifact.generatedAt,
            status = artifact.status.name,
            workspaceRoot = artifact.workspaceRoot,
            repoUrl = artifact.repoUrl,
            failures = artifact.failures.map { JsonSymbolFailure(it.symbol, it.reason) },
        )
        return json.encodeToString(dto)
    }

    /**
     * Deserialize the core [DemoGenScreen] from a stored artifact JSON. Extra
     * metadata fields are ignored so the importer is forward-compatible.
     */
    fun importScreen(rawJson: String): DemoGenScreen {
        val dto = json.decodeFromString<JsonArtifact>(rawJson)
        return DemoGenScreen(
            activeIndex = dto.activeIndex,
            conversations = dto.conversations.map(::fromDto),
        )
    }

    // ── DTO → domain ──────────────────────────────────────────────────────────

    private fun fromDto(dto: JsonConversation): DualPaneConversation =
        DualPaneConversation(
            symbolFqn = dto.symbolFqn,
            simpleName = dto.simpleName,
            turns = dto.turns.map(::fromDto),
        )

    private fun fromDto(dto: JsonTurn): ConversationTurn =
        ConversationTurn(
            userPrompt = dto.userPrompt,
            leftResponse = dto.leftResponse.map(::fromDto),
            rightResponse = dto.rightResponse.map(::fromDto),
        )

    private fun fromDto(dto: JsonLine): ConversationLine =
        ConversationLine(
            text = dto.text,
            tone = ConversationTone.entries.firstOrNull { it.name == dto.tone }
                ?: ConversationTone.NORMAL,
        )

    // ── domain → DTO ──────────────────────────────────────────────────────────

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

    // ── Serialization DTOs ────────────────────────────────────────────────────

    /**
     * Top-level JSON shape. Optional fields are absent when generating a
     * screen-only export; the importer ignores any field it does not need.
     */
    @Serializable
    private data class JsonArtifact(
        val activeIndex: Int,
        val conversations: List<JsonConversation>,
        val generatedAt: String? = null,
        val status: String? = null,
        val workspaceRoot: String? = null,
        val repoUrl: String? = null,
        val failures: List<JsonSymbolFailure> = emptyList(),
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

    @Serializable
    private data class JsonSymbolFailure(
        val symbol: String,
        val reason: String,
    )
}
