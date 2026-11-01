package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryPositiveTest {
    @Test
    fun `selected value enters its callback with the complete suffix`() {
        val fixture = ImpactReviewedEntryFixture()
        assertTrue(fixture.producer.transfer.admitsEvidence(), "The preceding mapped return must remain admissible")
        assertTrue(fixture.source.admitsValueState(), "The receiver must preserve valid prior conditions")
        assertTrue(
            fixture.binding.admits(fixture.binding.enclosing),
            "The consumer callback authority must be admissible",
        )
        assertTrue(fixture.input.admitsEvidence())
        assertEquals(fixture.input.source.valuePath.values.drop(1), fixture.input.target.valuePath.values.drop(1))
        assertEquals(fixture.input.source.conditions, fixture.input.target.conditions)
    }

    @Test
    fun `metadata getters preserve separate slots and a qualified stable component`() {
        val fixture = ImpactReviewedEntryFixture()
        listOf(true, false).forEach { key ->
            val read = fixture.read(key)
            assertTrue(read.admitsEvidence())
            assertTrue(read.target.admitsValueState())
            assertEquals(read.source.valuePath.values.drop(1), read.target.valuePath.values)
            val authority = read.binding.authority as ImpactReviewedCallValueBindingDocument
            val artifact =
                Json.parseToJsonElement(Json.encodeToString<ImpactReviewedArtifactsDocument>(authority.artifacts))
                    .jsonObject
            assertEquals("commonMain/kotlin/Collections.kt", artifact.getValue("sourceEntry").jsonPrimitive.content)
            assertFalse("facadeSha256" in artifact)
            assertFalse("implementationSha256" in artifact)
            assertEquals(
                setOf(
                    "sourceEntry",
                    "binaryArtifactSha256",
                    "sourceArtifactSha256",
                    "sourceEntrySha256",
                    "declarationLines",
                    "metadata",
                ),
                artifact.keys,
            )
        }
    }
}
