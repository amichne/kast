package io.github.amichne.kast.cli

import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTone
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoGenMarkdownExporterTest {

    @Test
    fun `emits header and table for single conversation`() {
        val screen = DemoGenScreen(
            conversations = listOf(
                DualPaneConversation(
                    symbolFqn = "io.example.Foo",
                    simpleName = "Foo",
                    turns = listOf(
                        ConversationTurn(
                            userPrompt = "What does Foo do?",
                            leftResponse = listOf(ConversationLine("baseline-a"), ConversationLine("baseline-b")),
                            rightResponse = listOf(ConversationLine("kast-a", ConversationTone.SUCCESS)),
                        ),
                    ),
                ),
            ),
        )

        val md = DemoGenMarkdownExporter.export(screen)

        assertTrue("## io.example.Foo" in md, "expected symbol header in:\n$md")
        assertTrue("### What does Foo do?" in md, "expected prompt header in:\n$md")
        assertTrue("| Baseline LLM | Kast-Augmented LLM |" in md)
        assertTrue("|---|---|" in md)
        // max(2,1) = 2 rows
        val rows = md.lines().count { it.startsWith("| ") && !it.startsWith("| Baseline") }
        assertEquals(2, rows, "expected 2 data rows in:\n$md")
        assertTrue("baseline-b" in md && md.contains("|  |"), "expected empty right cell row in:\n$md")
    }

    @Test
    fun `escapes pipes and newlines in cell content`() {
        val screen = DemoGenScreen(
            conversations = listOf(
                DualPaneConversation(
                    symbolFqn = "x.Y",
                    simpleName = "Y",
                    turns = listOf(
                        ConversationTurn(
                            userPrompt = "p",
                            leftResponse = listOf(ConversationLine("a|b\nc")),
                            rightResponse = listOf(ConversationLine("ok")),
                        ),
                    ),
                ),
            ),
        )

        val md = DemoGenMarkdownExporter.export(screen)

        assertTrue("a\\|b<br>c" in md, "expected escaped pipe/newline in:\n$md")
    }

    @Test
    fun `handles multiple conversations`() {
        val screen = DemoGenScreen(
            conversations = listOf(
                DualPaneConversation("a.A", "A", listOf(ConversationTurn("p1", emptyList(), emptyList()))),
                DualPaneConversation("b.B", "B", listOf(ConversationTurn("p2", emptyList(), emptyList()))),
            ),
        )

        val md = DemoGenMarkdownExporter.export(screen)

        assertTrue("## a.A" in md)
        assertTrue("## b.B" in md)
        assertTrue("### p1" in md)
        assertTrue("### p2" in md)
        assertTrue(md.endsWith("\n"))
    }
}
