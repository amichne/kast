package io.github.amichne.kast.demo

/**
 * Visual emphasis for a single conversation line in a dual-pane comparison.
 *
 * The renderer maps each tone to a Kotter color so the same model can be
 * snapshot-tested without coupling tests to ANSI codes.
 */
enum class ConversationTone { USER_PROMPT, NORMAL, SUCCESS, WARNING, ERROR, DIM }

/**
 * One textual line inside a pane, paired with its display tone.
 */
data class ConversationLine(val text: String, val tone: ConversationTone = ConversationTone.NORMAL)

/**
 * One user prompt and the two parallel responses it produced.
 *
 * `leftResponse` is the baseline (no kast augmentation); `rightResponse`
 * is the kast-augmented variant. The lists are rendered side-by-side so
 * differences are obvious at a glance.
 */
data class ConversationTurn(
    val userPrompt: String,
    val leftResponse: List<ConversationLine>,
    val rightResponse: List<ConversationLine>,
)

/**
 * A single symbol's worth of dual-pane comparison: every turn shares the
 * same symbol context, captured here for header rendering.
 */
data class DualPaneConversation(
    val symbolFqn: String,
    val simpleName: String,
    val turns: List<ConversationTurn>,
)

/**
 * Top-level state for the `kast demo generate` interactive screen.
 *
 * `activeIndex` selects which conversation is currently displayed; the
 * renderer also uses it to compute the act-header position.
 */
data class DemoGenScreen(
    val conversations: List<DualPaneConversation>,
    val activeIndex: Int = 0,
) {
    val active: DualPaneConversation? get() = conversations.getOrNull(activeIndex)
}
