package io.github.amichne.kast.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DualPaneRendererTest {

    private val sampleConversation = DualPaneConversation(
        symbolFqn = "io.github.amichne.kast.demo.DualPaneConversation",
        simpleName = "DualPaneConversation",
        turns = listOf(
            ConversationTurn(
                userPrompt = "What does DualPaneConversation model?",
                leftResponse = listOf(
                    ConversationLine("It probably stores chat text.", ConversationTone.WARNING),
                    ConversationLine("Cannot resolve panes without context.", ConversationTone.ERROR),
                ),
                rightResponse = listOf(
                    ConversationLine("Current model keeps symbol context and turns.", ConversationTone.SUCCESS),
                    ConversationLine("Renderer consumes DemoGenScreen.", ConversationTone.NORMAL),
                ),
            ),
            ConversationTurn(
                userPrompt = "Who calls it?",
                leftResponse = listOf(ConversationLine("Unknown.", ConversationTone.DIM)),
                rightResponse = listOf(ConversationLine("Called from DemoSession.run.", ConversationTone.SUCCESS)),
            ),
        ),
    )

    @Test
    fun `renders user prompt prefixed with You`() = testSession { terminal ->
        section { renderDualPaneConversation(sampleConversation) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { it.contains("You: What does DualPaneConversation model?") },
            "Should render first prompt with You: prefix. Got: $lines")
        assertTrue(lines.any { it.contains("You: Who calls it?") },
            "Should render second prompt with You: prefix. Got: $lines")
    }

    @Test
    fun `renders both pane headers`() = testSession { terminal ->
        section { renderDualPaneConversation(sampleConversation) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Baseline LLM" in it }, "Should render Baseline LLM header. Got: $lines")
        assertTrue(lines.any { "Kast-Augmented LLM" in it }, "Should render Kast-Augmented LLM header. Got: $lines")
    }

    @Test
    fun `renders all left and right lines`() = testSession { terminal ->
        section { renderDualPaneConversation(sampleConversation) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "It probably stores chat text." in it }, "Left WARNING line missing. Got: $lines")
        assertTrue(lines.any { "Cannot resolve panes without context." in it }, "Left ERROR line missing")
        assertTrue(lines.any { "Current model keeps symbol context and turns." in it }, "Right SUCCESS line missing")
        assertTrue(lines.any { "Renderer consumes DemoGenScreen." in it }, "Right NORMAL line missing")
        assertTrue(lines.any { "Unknown." in it }, "Second turn left DIM line missing")
        assertTrue(lines.any { "Called from DemoSession.run." in it }, "Second turn right SUCCESS line missing")
    }

    @Test
    fun `multiple turns produce multiple grids`() = testSession { terminal ->
        section { renderDualPaneConversation(sampleConversation) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        // Each turn renders its own Baseline LLM / Kast-Augmented LLM header pair.
        val baselineCount = lines.count { "Baseline LLM" in it }
        val augmentedCount = lines.count { "Kast-Augmented LLM" in it }
        assertTrue(baselineCount >= 2, "Expected >=2 Baseline LLM headers (one per turn). Got: $baselineCount")
        assertTrue(augmentedCount >= 2, "Expected >=2 Kast-Augmented LLM headers (one per turn). Got: $augmentedCount")
    }

    @Test
    fun `long lines are truncated to fit the panel`() = testSession { terminal ->
        val longText = "x".repeat(500)
        val convo = DualPaneConversation(
            symbolFqn = "pkg.Long",
            simpleName = "Long",
            turns = listOf(
                ConversationTurn(
                    userPrompt = "y".repeat(500),
                    leftResponse = listOf(ConversationLine(longText)),
                    rightResponse = listOf(ConversationLine(longText)),
                ),
            ),
        )
        val panelWidth = 80
        section { renderDualPaneConversation(convo, panelWidth = panelWidth) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        // Allow some slack for borders/padding; nothing should explode beyond a reasonable margin.
        val margin = 8
        for (line in lines) {
            assertTrue(line.length <= panelWidth + margin,
                "Line exceeded panelWidth+margin (${panelWidth + margin}): '$line' (len=${line.length})")
        }
    }

    @Test
    fun `renderDemoGenScreen renders act header and footer hints`() = testSession { terminal ->
        val secondConversation = DualPaneConversation(
            symbolFqn = "pkg.Other",
            simpleName = "Other",
            turns = listOf(
                ConversationTurn(
                    userPrompt = "ping",
                    leftResponse = listOf(ConversationLine("a")),
                    rightResponse = listOf(ConversationLine("b")),
                ),
            ),
        )
        val screen = DemoGenScreen(
            conversations = listOf(sampleConversation, secondConversation),
            activeIndex = 0,
        )
        section { renderDemoGenScreen(screen) }.run()
        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Act 1 of 2" in it }, "Should render act header. Got: $lines")
        assertTrue(lines.any { "Symbol: DualPaneConversation" in it }, "Should render symbol simple name in title")
        assertTrue(lines.any { sampleConversation.symbolFqn in it }, "Should render symbol FQN in subtitle")
        assertTrue(lines.any { "[1/2/3] switch" in it && "[Q/Esc] quit" in it },
            "Should render footer hints when multiple conversations exist. Got: $lines")
    }
}
