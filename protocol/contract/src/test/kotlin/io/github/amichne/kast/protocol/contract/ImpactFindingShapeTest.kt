package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactFindingShapeTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
    private val fixture = ImpactFindingFixture()

    @Test
    fun `finding emits required original path identity current alternatives and ordered compact provenance`() {
        val item =
            ImpactWitnessItemDocument(
                ImpactWitnessSectionDocument.FINDINGS,
                fixture.count(2),
                ImpactWitnessDocument.Finding(fixture.finding()),
            )
        assertEquals(
            expected("impact-finding.expected.json"),
            json.encodeToJsonElement(ImpactWitnessItemDocument.serializer(), item),
        )
    }

    @Test
    fun `all compact terminal variants preserve independent finite shapes and original rejection work`() {
        assertEquals(
            expected("impact-finding-terminals.expected.json"),
            json.encodeToJsonElement(ListSerializer(ImpactFindingTerminalDocument.serializer()), fixture.terminals()),
        )
    }

    @Test
    fun `nonmodeled representation preserves its required closed discriminator`() {
        assertEquals(
            expected("impact-finding-not-modeled.expected.json"),
            json.encodeToJsonElement(
                ImpactFindingRepresentationDocument.serializer(),
                ImpactFindingRepresentationDocument.NotModeled,
            ),
        )
    }

    @Test
    fun `missing original identity unknown discriminator and unknown finite cause fail raw decoding`() {
        val encoded =
            json.encodeToString(
                ImpactWitnessItemDocument.serializer(),
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.FINDINGS,
                    fixture.count(2),
                    ImpactWitnessDocument.Finding(fixture.finding()),
                ),
            )
        val cases =
            listOf(
                encoded.replace("\"type\":\"FINDING\"", "\"type\":\"UNKNOWN\""),
                encoded.replace("\"pathOrdinal\":2,", ""),
                encoded.replace(",\"pathRowId\":\"${fixture.row(1).value}\"", ""),
                encoded.replace("\"MUTABLE_CONTROL_FLOW\"", "\"UNCLASSIFIED\""),
            )
        for (raw in cases) assertThrows(SerializationException::class.java) {
            json.decodeFromString(ImpactWitnessItemDocument.serializer(), raw)
        }
    }

    private fun expected(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name")).readText())
}
