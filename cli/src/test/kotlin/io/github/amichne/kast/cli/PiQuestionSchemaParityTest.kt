package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PiQuestionSchemaParityTest {
    @Test
    fun `partial evidence schemas stay identical to the canonical serializer owners`() {
        val fixtures = Path.of("../experiments/host-observation/pi-fixtures")
        assertEquals(
            generatedRequestSchema(QueryQuestionDocument.serializer()),
            Json.parseToJsonElement(Files.readString(fixtures.resolve("query-question.schema.json"))),
        )
        assertEquals(
            generatedRequestSchema(CanonicalQueryCliDocuments.qualificationSerializer),
            Json.parseToJsonElement(Files.readString(fixtures.resolve("query-qualification.schema.json"))),
        )
    }
}
