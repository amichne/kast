@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.ProtocolHomogeneousCollection
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedSchemaReuseTest {
    @Test
    fun `default schema generation preserves the full independent closed shape`() {
        val schema = generatedRequestSchema(RepeatedSchemaRoot.serializer())
        val properties = schema.getValue("properties").jsonObject
        assertEquals(properties.getValue("first"), properties.getValue("second"))
        assertEquals(expected("repeated-schema.json"), schema)
        val fresh = generatedRequestSchema(RepeatedSchemaRoot.serializer())
        assertNotSame(schema, fresh)
    }

    @Test
    fun `reuse preserves distinct property constraints nullability and generic descriptor semantics`() {
        val schema = generatedRequestSchema(ConstrainedSchemaRoot.serializer())
        assertEquals(expected("constrained-schema.json"), schema)
        val properties = schema.getValue("properties").jsonObject
        assertNotSame(properties.getValue("short"), properties.getValue("long"))
        assertNotSame(properties.getValue("text"), properties.getValue("number"))
    }

    @Test
    fun `only the actual canonical descriptor admits a reusable definition address`() {
        val references = CanonicalSchemaReferences.from(mapOf("leaf" to RepeatedSchemaLeaf.serializer()))
        assertEquals(
            expected("referenced-schema.json"),
            generatedOutputSchema(RepeatedSchemaRoot.serializer(), references),
        )
        val forged =
            buildClassSerialDescriptor(RepeatedSchemaLeaf.serializer().descriptor.serialName) {
                element<String>("value")
            }
        assertNull(references.name(forged))
    }

    @Test
    fun `property constraints prevent unconstrained references and generic facts remain distinct`() {
        val references = CanonicalSchemaReferences.from(mapOf("plainText" to String.serializer()))
        assertEquals(
            expected("constrained-referenced-schema.json"),
            generatedOutputSchema(ConstrainedSchemaRoot.serializer(), references),
        )
    }

    @Test
    fun `nullable canonical descendants retain their null alternative around the exact reference`() {
        val references = CanonicalSchemaReferences.from(mapOf("leaf" to RepeatedSchemaLeaf.serializer()))
        assertEquals(
            expected("nullable-referenced-schema.json"),
            generatedOutputSchema(NullableSchemaRoot.serializer(), references),
        )
    }

    @Test
    fun `homogeneous collection preserves variant alternatives before canonical substitution`() {
        val references = CanonicalSchemaReferences.from(mapOf("choice" to HomogeneousSchemaChoice.serializer()))
        val document = generatedOutputSchema(HomogeneousSchemaRoot.serializer(), references)
        assertEquals(expected("homogeneous-schema.json"), document)
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val admitted =
            HomogeneousSchemaRoot(listOf(HomogeneousSchemaChoice.One("one"), HomogeneousSchemaChoice.One("two")))
        val mixed = HomogeneousSchemaRoot(listOf(HomogeneousSchemaChoice.One("one"), HomogeneousSchemaChoice.Two(2)))
        assertTrue(schema.validate(Json.encodeToString(admitted), com.networknt.schema.InputFormat.JSON).isEmpty())
        assertFalse(schema.validate(Json.encodeToString(mixed), com.networknt.schema.InputFormat.JSON).isEmpty())
    }

    private fun expected(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/schemas/$name")).readText())
}

@Serializable private data class RepeatedSchemaRoot(val first: RepeatedSchemaLeaf, val second: RepeatedSchemaLeaf)

@Serializable
private data class RepeatedSchemaLeaf(@ProtocolStringConstraint(minimumLength = 1, maximumLength = 8) val value: String)

@Serializable
private data class ConstrainedSchemaRoot(
    @ProtocolStringConstraint(minimumLength = 2, maximumLength = 4, pattern = "^[a-z]+$") val short: String,
    @ProtocolStringConstraint(minimumLength = 1, maximumLength = 16) val long: String,
    @ProtocolStringConstraint(minimumLength = 3, maximumLength = 6) val optional: String?,
    val text: GenericSchemaLeaf<String>,
    val number: GenericSchemaLeaf<Int>,
)

@Serializable private data class GenericSchemaLeaf<T>(val value: T)

@Serializable private data class NullableSchemaRoot(val optional: RepeatedSchemaLeaf?)

@Serializable
private data class HomogeneousSchemaRoot(@ProtocolHomogeneousCollection val values: List<HomogeneousSchemaChoice>)

@Serializable
@JsonClassDiscriminator("type")
private sealed interface HomogeneousSchemaChoice {
    @Serializable @SerialName("ONE") data class One(val value: String) : HomogeneousSchemaChoice

    @Serializable @SerialName("TWO") data class Two(val value: Int) : HomogeneousSchemaChoice
}
