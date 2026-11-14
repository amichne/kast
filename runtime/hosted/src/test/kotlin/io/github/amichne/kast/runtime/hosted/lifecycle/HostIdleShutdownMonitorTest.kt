package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostIdleShutdownMonitorTest {
    @TempDir lateinit var state: Path

    private val identity =
        IdeHostRuntimeIdentity(
            "/idea",
            "/profile",
            42,
            100,
            "host",
            IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
        )

    private val limit = (HostIdleShutdown.parseSetting("5") as Refinement.Refined).value as HostIdleShutdown.After
    private val limitNanos = 5 * 60_000_000_000L

    /** Each settlement inspection consumes the next scripted observation; the last one repeats. */
    private inner class Case(vararg script: HostNativeSettlement) {
        private val natives = ArrayDeque(script.toList().ifEmpty { listOf(HostNativeSettlement.Settled) })

        fun native(vararg next: HostNativeSettlement) {
            natives.clear()
            natives.addAll(next)
        }

        private fun inspect(): HostNativeSettlement = if (natives.size > 1) natives.removeFirst() else natives.first()

        var now = 0L
        var nativeWork = false
        var exits = 0
        val observations = mutableListOf<HostIdleObservation>()
        val records = mutableListOf<HostExitRecordOutcome>()
        val admission = HostedApplicationAdmission { now }
        val monitor =
            HostIdleShutdownMonitor(
                limit,
                admission,
                HostedApplicationSettlement(admission, identity, ::inspect, { false }),
                "host",
                { nativeWork },
                HostExitRecords(state, "host", { false }) { records += it.outcome },
                { exits++ },
                { observations += it },
            )

        fun outcomes() = observations.map { it.outcome }

        fun recorded(): HostExitRecord? =
            state.resolve("host-exit.json").let {
                if (Files.exists(it)) Json.decodeFromString(HostExitRecord.serializer(), Files.readString(it)) else null
            }
    }

    @Test
    fun `an idle Host requests exit once, only after the full limit`() {
        val case = Case()
        case.now = limitNanos - 1
        case.monitor.tick()
        assertEquals(0, case.exits)
        assertEquals(null, case.recorded())
        case.now = limitNanos
        case.monitor.tick()
        assertEquals(1, case.exits)
        assertEquals(HostExitRecord(HostExitReason.IDLE, HostExitIndexState.SMART, "host"), case.recorded())
        assertEquals(listOf(HostIdleObservation(HostIdleOutcome.EXIT_REQUESTED, null, 5)), case.observations)
        assertEquals(HostedApplicationAdmission.Admission.Fenced, case.admission.enter())
    }

    @Test
    fun `observed import or indexing keeps the Host running and restarts the clock`() {
        val case = Case()
        case.now = limitNanos * 3
        case.nativeWork = true
        case.monitor.tick()
        case.nativeWork = false
        case.now = limitNanos * 4 - 1
        case.monitor.tick()
        assertEquals(0, case.exits)
        assertEquals(emptyList<HostIdleObservation>(), case.observations)
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
    }

    @Test
    fun `an admitted request blocks the decision until it completes`() {
        val case = Case()
        val lease = (case.admission.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        case.now = limitNanos * 10
        case.monitor.tick()
        assertEquals(0, case.exits)
        lease.close()
        case.monitor.tick()
        assertEquals(0, case.exits)
        case.now += limitNanos
        case.monitor.tick()
        assertEquals(1, case.exits)
    }

    @Test
    fun `busy native work found during settlement reopens admission without exiting`() {
        val case = Case(HostNativeSettlement.Settled, HostNativeSettlement.Busy)
        case.now = limitNanos
        case.monitor.tick()
        assertEquals(0, case.exits)
        assertEquals(listOf(HostIdleOutcome.BUSY), case.outcomes())
        assertEquals(null, case.recorded())
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
    }

    @Test
    fun `unresolved mutation evidence keeps the Host running and retries only after another full limit`() {
        val case = Case(HostNativeSettlement.UnresolvedMutation)
        case.now = limitNanos
        case.monitor.tick()
        case.now = limitNanos * 2 - 1
        case.monitor.tick()
        assertEquals(0, case.exits)
        assertEquals(
            listOf(
                HostIdleObservation(
                    HostIdleOutcome.MUTATION_RECOVERY_REQUIRED,
                    IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED,
                    5,
                )
            ),
            case.observations,
        )
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
    }

    @Test
    fun `unresolved mutation evidence found under the fence keeps it until recovery settles`() {
        val case = Case(HostNativeSettlement.Settled, HostNativeSettlement.UnresolvedMutation)
        case.now = limitNanos
        case.monitor.tick()
        case.monitor.tick()
        assertEquals(listOf(HostIdleOutcome.MUTATION_RECOVERY_REQUIRED), case.outcomes())
        assertEquals(HostedApplicationAdmission.Admission.Fenced, case.admission.enter())
        case.native(HostNativeSettlement.Settled)
        case.monitor.tick()
        assertEquals(0, case.exits)
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
    }

    @Test
    fun `native work that starts before the stop reopens admission at once`() {
        val case = Case(HostNativeSettlement.Settled, HostNativeSettlement.Settled, HostNativeSettlement.Busy)
        case.now = limitNanos
        case.monitor.tick()
        assertEquals(listOf(HostIdleOutcome.REJECTED), case.outcomes())
        assertEquals(0, case.exits)
        assertEquals(null, case.recorded())
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
    }

    @Test
    fun `a refused reopening is retried until the Host can exit after another full limit`() {
        val case =
            Case(
                HostNativeSettlement.Settled,
                HostNativeSettlement.Settled,
                HostNativeSettlement.UnresolvedMutation,
                HostNativeSettlement.UnresolvedMutation,
                HostNativeSettlement.Settled,
            )
        case.now = limitNanos
        case.monitor.tick()
        assertEquals(HostedApplicationAdmission.Admission.Fenced, case.admission.enter())
        case.now = limitNanos + 1
        case.monitor.tick()
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
            .lease
            .close()
        case.now = limitNanos * 2
        case.monitor.tick()
        assertEquals(0, case.exits)
        case.now = limitNanos * 2 + 1
        case.monitor.tick()
        assertEquals(1, case.exits)
        assertEquals(listOf(HostIdleOutcome.REJECTED, HostIdleOutcome.EXIT_REQUESTED), case.outcomes())
    }

    @Test
    fun `a vetoed exit reopens admission on the next tick and restarts the clock`() {
        val case = Case()
        case.now = limitNanos
        case.monitor.tick()
        case.now = limitNanos + 1
        case.monitor.tick()
        assertEquals(listOf(HostIdleOutcome.EXIT_REQUESTED, HostIdleOutcome.VETOED), case.outcomes())
        assertEquals(null, case.recorded())
        assertEquals(listOf(HostExitRecordOutcome.WRITTEN, HostExitRecordOutcome.REMOVED), case.records)
        assertInstanceOf(HostedApplicationAdmission.Admission.Admitted::class.java, case.admission.enter())
            .lease
            .close()
        case.now = limitNanos * 2
        case.monitor.tick()
        assertEquals(1, case.exits)
        case.now = limitNanos * 2 + 1
        case.monitor.tick()
        assertEquals(2, case.exits)
    }
}
