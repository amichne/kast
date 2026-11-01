package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PiQuestionSchemaParityTest {
    @Test
    fun `partial evidence schemas stay identical to the canonical serializer owners`() {
        val fixtures = Path.of("../experiments/host-observation/pi-fixtures")
        assertSchema(fixtures.resolve("query-question.schema.json"), QueryQuestionDocument.serializer())
        assertSchema(
            fixtures.resolve("query-qualification.schema.json"),
            CanonicalQueryCliDocuments.qualificationSerializer,
        )
        assertEquals(
            installedSemanticResultSchema(CanonicalOperation.QUERY_RUN),
            Json.parseToJsonElement(Files.readString(fixtures.resolve("query-result.schema.json"))),
        )
    }

    private fun assertSchema(path: Path, serializer: KSerializer<*>) {
        val actual = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val expected = independentFactoredImpactSchema(serializer)
        assertEquals(expected.definitions.keys, actual.getValue("\$defs").jsonObject.keys)
        LocalSchemaAssertions(actual).assertEquivalent(expected)
    }
}
