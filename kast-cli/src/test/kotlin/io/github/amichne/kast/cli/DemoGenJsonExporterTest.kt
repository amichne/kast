package io.github.amichne.kast.cli

import io.github.amichne.kast.demo.ConversationLine
import io.github.amichne.kast.demo.ConversationTone
import io.github.amichne.kast.demo.ConversationTurn
import io.github.amichne.kast.demo.DemoGenScreen
import io.github.amichne.kast.demo.DualPaneConversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoGenJsonExporterTest {

    private val sample = DemoGenScreen(
        conversations = listOf(
            DualPaneConversation(
                symbolFqn = "io.example.Foo",
                simpleName = "Foo",
                turns = listOf(
                    ConversationTurn(
                        userPrompt = "What does Foo do?",
                        leftResponse = listOf(ConversationLine("baseline", ConversationTone.NORMAL)),
                        rightResponse = listOf(ConversationLine("kast", ConversationTone.SUCCESS)),
                    ),
                ),
            ),
            DualPaneConversation(
                symbolFqn = "io.example.Bar",
                simpleName = "Bar",
                turns = emptyList(),
            ),
        ),
        activeIndex = 1,
    )

    @Test
    fun `produces parseable json containing expected fields`() {
        val out = DemoGenJsonExporter.export(sample)

        val root = Json.parseToJsonElement(out).jsonObject
        assertEquals(1, root["activeIndex"]!!.jsonPrimitive.content.toInt())
        val conversations = root["conversations"]!!.jsonArray
        assertEquals(2, conversations.size)

        val first = conversations[0].jsonObject
        assertEquals("io.example.Foo", first["symbolFqn"]!!.jsonPrimitive.content)
        assertEquals("Foo", first["simpleName"]!!.jsonPrimitive.content)

        val turn = first["turns"]!!.jsonArray[0].jsonObject
        assertEquals("What does Foo do?", turn["userPrompt"]!!.jsonPrimitive.content)
        val rightLine = turn["rightResponse"]!!.jsonArray[0].jsonObject
        assertEquals("kast", rightLine["text"]!!.jsonPrimitive.content)
        assertEquals("SUCCESS", rightLine["tone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `round-trips structurally`() {
        val out = DemoGenJsonExporter.export(sample)
        val root = Json.parseToJsonElement(out).jsonObject

        assertTrue(root.keys.containsAll(setOf("activeIndex", "conversations")))
        val conversations = root["conversations"]!!.jsonArray
        assertEquals(sample.conversations.size, conversations.size)
        conversations.zip(sample.conversations).forEach { (jsonElement, source) ->
            val obj = jsonElement.jsonObject
            assertEquals(source.symbolFqn, obj["symbolFqn"]!!.jsonPrimitive.content)
            assertEquals(source.turns.size, obj["turns"]!!.jsonArray.size)
        }
    }
}
