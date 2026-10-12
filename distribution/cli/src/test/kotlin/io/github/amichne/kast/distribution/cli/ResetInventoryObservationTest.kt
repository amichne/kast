package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResetInventoryObservationTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(javaClass.getResource("/management/reset-inventory-observation.schema.json")).readText()
            )

    @Test
    fun `successful and failed inventories emit bounded typed evidence`() {
        val observations = mutableListOf<ResetInventoryObservation>()
        val current = ProcessHandle.current()
        val result =
            NativeForceDaemonRuntime.collectSnapshots(
                listOf(current),
                current.info().user().orElseThrow(),
                observations::add,
            )
        assertTrue(result is ResetProcessTable.Observed)
        assertEquals(listOf(ResetInventoryObservation(ResetInventoryOutcome.Observed(1))), observations)
        observations.clear()
        assertEquals(
            ResetProcessTable.Unavailable,
            NativeForceDaemonRuntime.collectSnapshots(listOf(UnknownOwnerProcess()), "owner", observations::add),
        )
        assertEquals(
            listOf(
                ResetInventoryObservation(ResetInventoryOutcome.Rejected(7, ResetInventoryRejection.OWNER_UNAVAILABLE))
            ),
            observations,
        )
    }

    @Test
    fun `missing commands reject unless the OS proves that the process exited`() {
        for (state in ResetObservedProcessState.entries) {
            val observations = mutableListOf<ResetInventoryObservation>()
            val result =
                NativeForceDaemonRuntime.collectSnapshots(listOf(CommandMissingProcess()), "owner", observations::add) {
                    assertEquals(7, it.pid())
                    state
                }
            if (state == ResetObservedProcessState.EXITED) {
                assertEquals(ResetProcessTable.Observed(emptyList()), result)
                assertEquals(listOf(ResetInventoryObservation(ResetInventoryOutcome.Observed(0))), observations)
            } else {
                assertEquals(ResetProcessTable.Unavailable, result)
                assertEquals(
                    listOf(
                        ResetInventoryObservation(
                            ResetInventoryOutcome.Rejected(7, ResetInventoryRejection.COMMAND_UNAVAILABLE)
                        )
                    ),
                    observations,
                )
            }
        }
    }

    @Test
    fun `each inventory result matches an independent closed encoded shape`() {
        val outcomes =
            listOf(ResetInventoryOutcome.Observed(0)) +
                ResetInventoryRejection.entries.map { ResetInventoryOutcome.Rejected(7, it) }
        val expected =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/management/reset-inventory-expected-shapes.json")).readText()
                )
                .jsonArray
        outcomes.forEachIndexed { index, outcome ->
            val encoded = ResetInventoryObservation(outcome).asJson()
            assertEquals(expected[index], Json.parseToJsonElement(encoded))
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        }
        // Deliberately malformed data exercises external rejection.
        val invalid =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/management/reset-inventory-invalid-shapes.json")).readText()
                )
                .jsonArray
        invalid.forEach { assertTrue(schema.validate(it.toString(), InputFormat.JSON).isNotEmpty()) }
    }
}

private class CommandMissingProcess : ProcessHandle by UnknownOwnerProcess() {
    override fun info() =
        object : ProcessHandle.Info by UnknownOwnerProcess().info() {
            override fun user(): Optional<String> = Optional.of("owner")

            override fun command(): Optional<String> = Optional.empty()
        }
}

private class UnknownOwnerProcess : ProcessHandle {
    override fun pid() = 7L

    override fun isAlive() = true

    override fun info() =
        object : ProcessHandle.Info {
            override fun user(): Optional<String> = Optional.empty()

            override fun command(): Optional<String> = error("unexpected command read")

            override fun commandLine(): Optional<String> = error("unexpected command line read")

            override fun arguments(): Optional<Array<String>> = error("unexpected arguments read")

            override fun startInstant(): Optional<java.time.Instant> = error("unexpected start read")

            override fun totalCpuDuration(): Optional<java.time.Duration> = error("unexpected CPU read")
        }

    override fun parent(): Optional<ProcessHandle> = error("unexpected parent read")

    override fun children(): Stream<ProcessHandle> = error("unexpected child read")

    override fun descendants(): Stream<ProcessHandle> = error("unexpected descendant read")

    override fun onExit(): CompletableFuture<ProcessHandle> = error("unexpected exit wait")

    override fun supportsNormalTermination() = error("unexpected signal query")

    override fun destroy() = error("unexpected signal")

    override fun destroyForcibly() = error("unexpected signal")

    override fun compareTo(other: ProcessHandle) = error("unexpected comparison")
}
