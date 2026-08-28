package support.hostedwriter

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class HostedWriterProgramTest {
    @Test
    fun `fixed program projection is deterministic schema valid and replayable`() {
        val first = HostedWriterProgram.encoded()
        val second = HostedWriterProgram.encoded()

        assertEquals(first, second)
        val replayed = assertInstanceOf(
            HostedWriterProgramReplay.Admitted::class.java,
            HostedWriterProgram.replay(first),
        ).document
        assertEquals(HostedWriterProgram.document, replayed)
        assertEquals(
            listOf(
                "PROGRAM",
                "SURFACE",
                "HOST-BOUNDARY",
                "PROJECT-PORTS",
                "DURABLE-STATE",
                "TOPOLOGY",
                "MUTATION",
                "ENDPOINT",
                "INSTALLED-PROOF",
            ),
            replayed.tasks.map { it.id.value },
        )
        assertEquals(
            setOf(ProofGateId("PROJECT-PORTS"), ProofGateId("DURABLE-STATE")),
            replayed.task(ProofGateId("TOPOLOGY")).dependencies,
        )
        assertEquals(
            setOf(ProofGateId("TOPOLOGY"), ProofGateId("MUTATION")),
            replayed.task(ProofGateId("ENDPOINT")).dependencies,
        )
        assertTrue(
            HostedWriterSchemaValidator.validate(
                schema("program.schema.json"),
                Json.parseToJsonElement(first),
            ) is HostedWriterSchemaValidation.Valid,
        )
    }

    @Test
    fun `all three artifact families have checked in schemas`() {
        listOf(
            "program.schema.json",
            "receipt.schema.json",
            "installed-acceptance.schema.json",
        ).forEach { name ->
            assertTrue(Files.isRegularFile(schemaPath(name)), "missing schema $name")
        }
    }

    private fun schema(name: String) = Json.parseToJsonElement(
        Files.readString(schemaPath(name)),
    )

    private fun schemaPath(name: String): Path = Path.of("..", "gradle", "hosted-writer", name)
}
