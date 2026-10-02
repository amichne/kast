package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleContractTest {
    private val expectedShapes = resourceArray("lifecycle-expected-shapes.json")

    private fun resourceArray(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/management/$name")).readText()).jsonArray

    private val schemaDocument =
        checkNotNull(javaClass.getResource("/management/management-lifecycle.schema.json")).readText()

    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument)

    @Test
    fun `canonical examples remain valid lifecycle documents`() {
        val examples = Json.parseToJsonElement(schemaDocument).jsonObject.getValue("examples").jsonArray
        assertTrue(examples.isNotEmpty())
        examples.forEach { assertTrue(schema.validate(it.toString(), InputFormat.JSON).isEmpty(), it.toString()) }
    }

    @Test
    fun `all lifecycle result variants retain required fields in the canonical schema`() {
        val results =
            listOf(
                LifecycleOutcome.Stopped(LifecycleOperation.STOP, "/installation"),
                LifecycleOutcome.Stopped(LifecycleOperation.STOP_FORCE, "/installation"),
                LifecycleOutcome.Reinstalled(LifecycleOperation.REINSTALL, "/installation", "1.2.3"),
            ) +
                LifecycleFailure.entries.map {
                    LifecycleOutcome.Rejected(LifecycleOperation.STOP, LifecycleStage.ADMISSION, it)
                } +
                LifecycleStage.entries.map {
                    LifecycleOutcome.Pending(LifecycleOperation.REINSTALL, "1.2.3", it, LifecycleFailure.CHILD_REJECTED)
                }
        results.forEach { assertTrue(schema.validate(it.asJson(), InputFormat.JSON).isEmpty(), it.asJson()) }
        assertEquals(
            expectedShapes[0].toString(),
            results.first().asJson(),
        )
        assertEquals(
            expectedShapes[1].toString(),
            LifecycleOutcome.Pending(
                    LifecycleOperation.REINSTALL,
                    "1.2.3",
                    LifecycleStage.ACTIVATION,
                    LifecycleFailure.CHILD_REJECTED,
                )
                .asJson(),
        )
    }

    @Test
    fun `force reset results have independently required closed shapes`() {
        val completed =
            listOf(
                ForceResetOutcome.Removed("/kast"),
                ForceResetOutcome.Reinstalled(
                    "/kast/installation",
                    "1.2.3",
                    "/kast/bin/kast",
                    "12345678-1234-1234-1234-123456789abc",
                ),
                ForceResetOutcome.Pending(
                    "/kast/installation",
                    "1.2.3",
                    "/kast/bin/kast",
                    ForceResetFailure.READINESS_REJECTED,
                ),
                ForceResetOutcome.Retained(
                    ForceResetOperation.UNINSTALL,
                    ForceResetStage.DAEMONS,
                    ForceResetFailure.LAUNCH_JOB_REJECTED,
                    "/.kast-retiring-example/payload",
                ),
                ForceResetOutcome.RecoveryRequired(
                    ForceResetOperation.REINSTALL,
                    ForceResetStage.ACTIVATION,
                    ForceResetFailure.READINESS_REJECTED,
                    ForceResetFailure.PROCESS_RETIREMENT_REJECTED,
                    "/kast",
                ),
                ForceResetOutcome.Rejected(
                    ForceResetOperation.REINSTALL,
                    ForceResetStage.FENCE,
                    ForceResetFailure.RESET_BUSY,
                ),
            )
        val expected = resourceArray("force-reset-expected-shapes.json")
        completed.forEachIndexed { index, result ->
            assertTrue(schema.validate(result.asJson(), InputFormat.JSON).isEmpty(), result.asJson())
            assertEquals(expected[index], Json.parseToJsonElement(result.asJson()))
        }
    }

    @Test
    fun `finite reset operations stages and failures retain their identities`() {
        ForceResetOperation.entries.forEach { operation ->
            ForceResetStage.entries.forEach { stage ->
                ForceResetFailure.entries.forEach { failure ->
                    val result = ForceResetOutcome.Rejected(operation, stage, failure)
                    assertTrue(schema.validate(result.asJson(), InputFormat.JSON).isEmpty(), result.asJson())
                }
            }
        }
    }

    @Test
    fun `force completion and recovery reject missing unknown and stale wire facts`() {
        // Deliberately malformed wire documents exercise the trust boundary.
        resourceArray("force-reset-invalid-results.json").forEach {
            assertTrue(schema.validate(it.toString(), InputFormat.JSON).isNotEmpty(), it.toString())
        }
    }

    @Test
    fun `unknown omitted and incompatible lifecycle fields cannot acquire completion proof`() {
        // Deliberately malformed wire documents exercise the trust boundary.
        resourceArray("lifecycle-invalid-results.json").forEach {
            assertTrue(schema.validate(it.toString(), InputFormat.JSON).isNotEmpty(), it.toString())
        }
    }

    @Test
    fun `stage observations require failure data only on the rejected variant`() {
        val started: LifecycleObservation =
            LifecycleObservation.started(LifecycleOperation.STOP_FORCE, LifecycleStage.FENCE)
        val rejected: LifecycleObservation =
            LifecycleObservation.rejected(
                LifecycleOperation.STOP_FORCE,
                LifecycleStage.FENCE,
                LifecycleFailure.FENCE_REJECTED,
            )
        assertEquals(
            expectedShapes[2].toString(),
            managementJson.encodeToString(started),
        )
        assertEquals(
            expectedShapes[3].toString(),
            managementJson.encodeToString(rejected),
        )
    }
}
