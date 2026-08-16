package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SqliteMutationRecoveryJournalTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `pre-write applied set and terminal recovery survive reopen with exact plan binding`() {
        val fixture = MutationRecoveryEvidenceFixture()
        val database = temporaryDirectory.resolve("recovery.db")
        val first = open(database)

        assertDurable(first.prepare(fixture.prepared), fixture.prepared)
        assertDurable(first.recordApplied(fixture.prepared, fixture.applied), fixture.applied)
        assertDurable(first.recordTerminal(fixture.applied, fixture.rolledBack), fixture.rolledBack)

        val loaded = assertInstanceOf(
            MutationRecoveryLoadResult.Found::class.java,
            open(database).load(fixture.binding),
        ).record
        assertInstanceOf(MutationRecoveryRecord.RolledBack::class.java, loaded)
        assertEquals(fixture.rolledBack.digest, loaded.digest)
        assertEquals(fixture.binding, loaded.binding)
    }

    @Test
    fun `crashes resolve to prior durable state or explicit recovery state`() {
        val beforePrepare = crashAt(MutationRecoveryFaultPoint.AFTER_PREPARE_WRITE)
        assertInstanceOf(MutationRecoveryLoadResult.Absent::class.java, beforePrepare.loaded)

        val afterPrepare = crashAt(MutationRecoveryFaultPoint.AFTER_PREPARE_COMMIT)
        assertInstanceOf(MutationRecoveryRecord.PreWriteDurable::class.java, afterPrepare.record())

        val beforeApplied = crashAt(MutationRecoveryFaultPoint.AFTER_APPLIED_WRITE)
        assertInstanceOf(MutationRecoveryRecord.PreWriteDurable::class.java, beforeApplied.record())

        val afterApplied = crashAt(MutationRecoveryFaultPoint.AFTER_APPLIED_COMMIT)
        assertInstanceOf(
            MutationRecoveryRecord.RecoveryRequired::class.java,
            afterApplied.record(),
        )

        val beforeTerminal = crashAt(MutationRecoveryFaultPoint.AFTER_TERMINAL_WRITE)
        assertInstanceOf(
            MutationRecoveryRecord.RecoveryRequired::class.java,
            beforeTerminal.record(),
        )

        val afterTerminal = crashAt(MutationRecoveryFaultPoint.AFTER_TERMINAL_COMMIT)
        assertInstanceOf(MutationRecoveryRecord.RolledBack::class.java, afterTerminal.record())
    }

    @Test
    fun `tampered durable record fails closed`() {
        val fixture = MutationRecoveryEvidenceFixture()
        val database = temporaryDirectory.resolve("tampered.db")
        assertDurable(open(database).prepare(fixture.prepared), fixture.prepared)
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "UPDATE mutation_recovery SET record_digest = ? WHERE plan_binding = ?",
            ).use { statement ->
                statement.setString(1, "0".repeat(64))
                statement.setString(2, fixture.binding.value)
                statement.executeUpdate()
            }
        }

        val loaded = open(database).load(fixture.binding)

        assertEquals(
            MutationRecoveryEvidenceFailure.CORRUPT_RECORD,
            assertInstanceOf(MutationRecoveryLoadResult.Rejected::class.java, loaded).failure,
        )
    }

    private fun crashAt(point: MutationRecoveryFaultPoint): CrashObservation {
        val fixture = MutationRecoveryEvidenceFixture(point.ordinal.toString(16).first())
        val database = temporaryDirectory.resolve("crash-${point.name}.db")
        val baseline = open(database)
        when (point) {
            MutationRecoveryFaultPoint.AFTER_PREPARE_WRITE,
            MutationRecoveryFaultPoint.AFTER_PREPARE_COMMIT,
                -> Unit
            MutationRecoveryFaultPoint.AFTER_APPLIED_WRITE,
            MutationRecoveryFaultPoint.AFTER_APPLIED_COMMIT,
                -> assertDurable(baseline.prepare(fixture.prepared), fixture.prepared)
            MutationRecoveryFaultPoint.AFTER_TERMINAL_WRITE,
            MutationRecoveryFaultPoint.AFTER_TERMINAL_COMMIT,
                -> {
                assertDurable(baseline.prepare(fixture.prepared), fixture.prepared)
                assertDurable(
                    baseline.recordApplied(fixture.prepared, fixture.applied),
                    fixture.applied,
                )
            }
        }
        val crashing = open(database) { observed ->
            if (observed == point) throw SimulatedCrash()
        }
        try {
            when (point) {
                MutationRecoveryFaultPoint.AFTER_PREPARE_WRITE,
                MutationRecoveryFaultPoint.AFTER_PREPARE_COMMIT,
                    -> crashing.prepare(fixture.prepared)
                MutationRecoveryFaultPoint.AFTER_APPLIED_WRITE,
                MutationRecoveryFaultPoint.AFTER_APPLIED_COMMIT,
                    -> crashing.recordApplied(fixture.prepared, fixture.applied)
                MutationRecoveryFaultPoint.AFTER_TERMINAL_WRITE,
                MutationRecoveryFaultPoint.AFTER_TERMINAL_COMMIT,
                    -> crashing.recordTerminal(fixture.applied, fixture.rolledBack)
            }
        } catch (_: SimulatedCrash) {
            // Simulates abrupt process loss at one exact adapter boundary.
        }
        val reopened = open(database)
        val loaded = reopened.load(fixture.binding)
        val resolved = when (loaded) {
            is MutationRecoveryLoadResult.Found -> when (val record = loaded.record) {
                is MutationRecoveryRecord.AppliedWritesDurable -> {
                    assertDurable(
                        reopened.recordTerminal(record, fixture.recoveryRequired),
                        fixture.recoveryRequired,
                    )
                    reopened.load(fixture.binding)
                }
                is MutationRecoveryRecord.PreWriteDurable,
                is MutationRecoveryRecord.RolledBack,
                is MutationRecoveryRecord.RecoveryRequired,
                    -> loaded
            }
            is MutationRecoveryLoadResult.Absent,
            is MutationRecoveryLoadResult.Rejected,
                -> loaded
        }
        return CrashObservation(resolved)
    }

    private fun open(
        database: Path,
        injector: MutationRecoveryFaultInjector = MutationRecoveryFaultInjector.Disabled,
    ): SqliteMutationRecoveryJournal = assertInstanceOf(
        SqliteMutationRecoveryJournalOpenResult.Opened::class.java,
        SqliteMutationRecoveryJournal.open(database, injector),
    ).journal

    private fun assertDurable(
        result: MutationRecoveryPersistResult<MutationRecoveryRecord>,
        expected: MutationRecoveryRecord,
    ) {
        assertEquals(
            expected.digest,
            assertInstanceOf(MutationRecoveryPersistResult.Durable::class.java, result).record.digest,
        )
    }

    private data class CrashObservation(val loaded: MutationRecoveryLoadResult) {
        fun record(): MutationRecoveryRecord =
            assertInstanceOf(MutationRecoveryLoadResult.Found::class.java, loaded).record
    }

    private class SimulatedCrash : Error()
}
