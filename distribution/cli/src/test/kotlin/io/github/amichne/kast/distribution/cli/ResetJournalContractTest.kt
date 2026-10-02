package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResetJournalContractTest {
    private val schemaDocument =
        checkNotNull(javaClass.getResource("/management/installation-reset.schema.json")).readText()
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument)

    private fun resourceArray(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/management/$name")).readText()).jsonArray

    @Test
    fun `durable journal variants preserve independently required wire facts`() {
        val journals =
            listOf(
                InstallationResetRequest("/kast", InstallationResetStorage.Fenced),
                InstallationResetRequest("/kast", InstallationResetStorage.Retained("/.kast-retiring-example")),
                InstallationResetRequest("/kast", InstallationResetStorage.Erased),
                InstallationResetRequest("/kast", InstallationResetStorage.Activating),
            )
        val expected = resourceArray("reset-journal-expected-shapes.json")
        journals.forEachIndexed { index, journal ->
            val encoded = managementJson.encodeToString(journal)
            assertEquals(expected[index], Json.parseToJsonElement(encoded))
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
            assertEquals(journal, managementJson.decodeFromString<InstallationResetRequest>(expected[index].toString()))
        }
    }

    @Test
    fun `journal schema rejects unknown missing extra and incompatible state facts`() {
        // Deliberately malformed documents exercise the durable trust boundary.
        resourceArray("reset-journal-invalid-shapes.json").forEach {
            assertTrue(schema.validate(it.toString(), InputFormat.JSON).isNotEmpty(), it.toString())
        }
    }

    @Test
    fun `canonical journal examples satisfy their own contract`() {
        val examples = Json.parseToJsonElement(schemaDocument).jsonObject.getValue("examples").jsonArray
        assertEquals(4, examples.size)
        examples.forEach { assertTrue(schema.validate(it.toString(), InputFormat.JSON).isEmpty(), it.toString()) }
    }

    @Test
    fun `typed journal decoding rejects foreign root fields`() {
        val document = resourceArray("reset-journal-invalid-shapes.json")[4].toString()
        assertThrows(SerializationException::class.java) {
            managementJson.decodeFromString<InstallationResetRequest>(document)
        }
    }

    @Test
    fun `reset observations encode stage and failure only on the owning variant`() {
        val observations =
            listOf(
                ResetObservation.Started(ForceResetOperation.REINSTALL, ForceResetStage.DAEMONS),
                ResetObservation.Completed(ForceResetOperation.REINSTALL, ForceResetStage.DAEMONS),
                ResetObservation.Rejected(
                    ForceResetOperation.REINSTALL,
                    ForceResetStage.DAEMONS,
                    ForceResetFailure.PROCESS_RETIREMENT_REJECTED,
                ),
            )
        val expected = resourceArray("reset-observation-expected-shapes.json")
        observations.forEachIndexed { index, observation ->
            assertEquals(expected[index], Json.parseToJsonElement(observation.asJson()))
        }
    }
}
