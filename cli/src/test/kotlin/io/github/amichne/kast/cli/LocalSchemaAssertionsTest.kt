package io.github.amichne.kast.cli

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalSchemaAssertionsTest {
    @Test
    fun `shared references reject changed property type`() {
        rejectChangedLeaf(NumberLeaf.serializer())
    }

    @Test
    fun `shared references reject changed required property`() {
        rejectChangedLeaf(OptionalLeaf.serializer())
    }

    @Test
    fun `shared references reject changed discriminator value`() {
        val expected = generatedOutputSchema(Envelope.serializer(Choice.serializer()), CanonicalSchemaReferences.None)
        assertThrows(AssertionError::class.java) {
            LocalSchemaAssertions(sharedSchema(ChangedChoice.serializer())).assertEquivalent(expected)
        }
        assertThrows(AssertionError::class.java) {
            LocalSchemaAssertions(sharedSchema(ChangedChoice.serializer()))
                .assertEquivalent(independentGraph(Choice.serializer()))
        }
    }

    @Test
    fun `expanded and shared shapes stay equivalent`() {
        LocalSchemaAssertions(sharedSchema(TextLeaf.serializer()))
            .assertEquivalent(
                generatedOutputSchema(Envelope.serializer(TextLeaf.serializer()), CanonicalSchemaReferences.None)
            )
    }

    @Test
    fun `independent references compare different factoring`() {
        val expected = independentGraph(TextLeaf.serializer())
        LocalSchemaAssertions(sharedSchema(TextLeaf.serializer())).assertEquivalent(expected)
        LocalSchemaAssertions(
                generatedOutputSchema(Envelope.serializer(TextLeaf.serializer()), CanonicalSchemaReferences.None)
            )
            .assertEquivalent(expected)
    }

    private fun <T> independentGraph(serializer: KSerializer<T>): IndependentSchemaGraph {
        val references = CanonicalSchemaReferences.from(mapOf("expectedLeaf" to serializer))
        return IndependentSchemaGraph(
            generatedOutputSchema(Envelope.serializer(serializer), references),
            mapOf("expectedLeaf" to generatedOutputSchema(serializer, references)),
        )
    }

    private fun rejectChangedLeaf(serializer: KSerializer<*>) {
        val expected = generatedOutputSchema(Envelope.serializer(TextLeaf.serializer()), CanonicalSchemaReferences.None)
        assertThrows(AssertionError::class.java) {
            LocalSchemaAssertions(sharedSchema(serializer)).assertEquivalent(expected)
        }
        assertThrows(AssertionError::class.java) {
            LocalSchemaAssertions(sharedSchema(serializer)).assertEquivalent(independentGraph(TextLeaf.serializer()))
        }
    }

    private fun <T> sharedSchema(serializer: KSerializer<T>) =
        CanonicalSchemaReferences.from(mapOf("leaf" to serializer)).let { references ->
            val root = generatedOutputSchema(Envelope.serializer(serializer), references)
            val document = Json.decodeFromJsonElement<SchemaDocument>(root)
            Json.encodeToJsonElement(
                    document.copy(definitions = mapOf("leaf" to generatedOutputSchema(serializer, references)))
                )
                .jsonObject
        }

    @Serializable private data class Envelope<T>(val first: T, val second: T)

    @Serializable private data class TextLeaf(val value: String)

    @Serializable private data class NumberLeaf(val value: Int)

    @Serializable private data class OptionalLeaf(val value: String = "default")

    @Serializable
    private sealed interface Choice {
        @Serializable @SerialName("KEPT") data class Kept(val value: String) : Choice
    }

    @Serializable
    private sealed interface ChangedChoice {
        @Serializable @SerialName("CHANGED") data class Changed(val value: String) : ChangedChoice
    }

    @Serializable
    private data class SchemaDocument(
        val type: String,
        val properties: Map<String, JsonElement>,
        val required: List<String>,
        val additionalProperties: Boolean,
        @SerialName("\$defs") val definitions: Map<String, JsonElement> = emptyMap(),
    )
}
