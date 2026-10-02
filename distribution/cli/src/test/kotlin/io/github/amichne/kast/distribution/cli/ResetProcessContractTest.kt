package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResetProcessContractTest {
    private val schemaDocument =
        checkNotNull(javaClass.getResource("/management/reset-process-observation.schema.json")).readText()
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument)

    private fun resourceArray(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/management/$name")).readText()).jsonArray

    @Test
    fun `every process retirement result has an independent encoded shape`() {
        val observations =
            listOf(
                ResetProcessObservation(123, ResetProcessStage.VERIFY, ResetProcessResult.RETIRED),
                ResetProcessObservation(123, ResetProcessStage.REVALIDATE, ResetProcessResult.INCARNATION_CHANGED),
                ResetProcessObservation(123, ResetProcessStage.REVALIDATE, ResetProcessResult.OBSERVATION_REJECTED),
                ResetProcessObservation(123, ResetProcessStage.REVALIDATE, ResetProcessResult.IDENTITY_REJECTED),
                ResetProcessObservation(123, ResetProcessStage.SIGNAL, ResetProcessResult.SIGNAL_REJECTED),
                ResetProcessObservation(123, ResetProcessStage.WAIT, ResetProcessResult.DEADLINE_EXCEEDED),
                ResetProcessObservation(123, ResetProcessStage.WAIT, ResetProcessResult.WAIT_REJECTED),
                ResetProcessObservation(123, ResetProcessStage.WAIT, ResetProcessResult.INTERRUPTED),
                ResetProcessObservation(123, ResetProcessStage.REVALIDATE, ResetProcessResult.IDENTITY_VERIFIED),
            )
        assertEquals(ResetProcessResult.entries.toSet(), observations.map { it.outcome }.toSet())
        val expected = resourceArray("reset-process-expected-shapes.json")
        observations.forEachIndexed { index, observation ->
            val encoded = observation.asJson()
            assertEquals(expected[index], Json.parseToJsonElement(encoded))
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        }
    }

    @Test
    fun `all finite process retirement stages retain wire admission`() {
        ResetProcessStage.entries.forEach { stage ->
            val encoded = ResetProcessObservation(123, stage, ResetProcessResult.OBSERVATION_REJECTED).asJson()
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        }
    }

    @Test
    fun `process observations reject missing extra unknown and invalid scalar fields`() {
        // Deliberately malformed observations exercise the trust boundary.
        resourceArray("reset-process-invalid-shapes.json").forEach {
            assertTrue(schema.validate(it.toString(), InputFormat.JSON).isNotEmpty(), it.toString())
        }
    }

    @Test
    fun `canonical process observation examples satisfy the contract`() {
        val examples = Json.parseToJsonElement(schemaDocument).jsonObject.getValue("examples").jsonArray
        assertEquals(9, examples.size)
        examples.forEach { assertTrue(schema.validate(it.toString(), InputFormat.JSON).isEmpty(), it.toString()) }
    }
}
