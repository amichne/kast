package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class ResetLaunchObservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `launch outcomes preserve command variant exit code and required discriminator`() {
        val label = ResetServiceLabel.selected(temporary.resolve("installation"))
        val observations =
            listOf(
                ResetLaunchObservation.from(
                    ResetLaunchCommand.Inspect(label, ResetLaunchVariant.CURRENT),
                    ResetCommandObservation.Exited(113),
                ),
                ResetLaunchObservation.from(
                    ResetLaunchCommand.Bootout(label, ResetLaunchVariant.LEGACY_LOGIN),
                    ResetCommandObservation.Exited(5),
                ),
                ResetLaunchObservation.from(
                    ResetLaunchCommand.Bootout(label, ResetLaunchVariant.CURRENT),
                    ResetCommandObservation.Unavailable,
                ),
            )
        val allObservations =
            observations +
                listOf(
                    ResetLaunchObservation(
                        ResetLaunchStage.WAIT,
                        ResetLaunchVariant.CURRENT,
                        ResetCommandObservation.DeadlineExceeded,
                    ),
                    ResetLaunchObservation(
                        ResetLaunchStage.WAIT,
                        ResetLaunchVariant.CURRENT,
                        ResetCommandObservation.Interrupted,
                    ),
                )
        val expected =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/management/reset-launch-expected-shapes.json")).readText()
                )
                .jsonArray
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    checkNotNull(javaClass.getResource("/management/reset-launch-observation.schema.json")).readText()
                )
        allObservations.forEachIndexed { index, observation ->
            val encoded = observation.asJson()
            assertEquals(expected[index], Json.parseToJsonElement(encoded))
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `native launch adapter reports absence and a rejected home without erasing the cause`() {
        val label = ResetServiceLabel.selected(temporary.resolve("installation"))
        val observations = mutableListOf<ResetLaunchObservation>()
        assertEquals(
            ResetCommandObservation.Exited(113),
            NativeForceDaemonRuntime.launchObserved(
                ResetLaunchCommand.Inspect(label, ResetLaunchVariant.CURRENT),
                temporary,
                observations::add,
            ),
        )
        assertEquals(
            ResetCommandObservation.Unavailable,
            NativeForceDaemonRuntime.launchObserved(
                ResetLaunchCommand.Bootout(label, ResetLaunchVariant.LEGACY_LOGIN),
                temporary.resolve("absent"),
                observations::add,
            ),
        )
        assertEquals(
            listOf(
                ResetLaunchObservation(
                    ResetLaunchStage.INSPECT,
                    ResetLaunchVariant.CURRENT,
                    ResetCommandObservation.Exited(113),
                ),
                ResetLaunchObservation(
                    ResetLaunchStage.BOOTOUT,
                    ResetLaunchVariant.LEGACY_LOGIN,
                    ResetCommandObservation.Unavailable,
                ),
            ),
            observations,
        )
    }
}
