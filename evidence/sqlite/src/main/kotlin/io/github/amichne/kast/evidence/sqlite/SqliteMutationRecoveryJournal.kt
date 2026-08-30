package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.RecoveryRequirement
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

sealed interface SqliteMutationRecoveryJournalOpenResult {
    data class Opened(
        val journal: SqliteMutationRecoveryJournal,
    ) : SqliteMutationRecoveryJournalOpenResult

    data class Rejected(
        val failure: SqliteMutationRecoveryJournalOpenFailure,
    ) : SqliteMutationRecoveryJournalOpenResult
}

/** SQLite owner of atomic clean-slate mutation recovery evidence transitions. */
class SqliteMutationRecoveryJournal private constructor(
    private val connections: InitializedSqliteMutationRecoveryConnections,
    private val faults: MutationRecoveryFaultInjector,
) : MutationRecoveryEvidenceStore {
    override fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = storageResult {
        connections.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = connection.insertPreparation(record)
                if (!inserted) {
                    connection.rollback()
                    return@use existingPreparation(record)
                }
                faults.observe(MutationRecoveryFaultPoint.AFTER_PREPARE_WRITE)
                connection.commit()
                faults.observe(MutationRecoveryFaultPoint.AFTER_PREPARE_COMMIT)
                MutationRecoveryPersistResult.Durable(record)
            } catch (failure: Throwable) {
                rollbackBeforeRethrow(connection, failure)
            }
        }
    }

    override fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> = storageResult {
        if (record.binding != prior.binding || record.priorDigest != prior.digest) {
            return@storageResult rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
        }
        connections.use { connection ->
            connection.autoCommit = false
            try {
                when (val current = connection.loadMutationRecovery(prior.binding)) {
                    is MutationRecoveryLoadResult.Found -> when (current.record.digest) {
                        record.digest -> {
                            connection.rollback()
                            MutationRecoveryPersistResult.Durable(record)
                        }
                        prior.digest -> connection.persistApplied(prior, record)
                        else -> {
                            connection.rollback()
                            rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
                        }
                    }
                    is MutationRecoveryLoadResult.Absent -> {
                        connection.rollback()
                        rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
                    }
                    is MutationRecoveryLoadResult.Rejected -> {
                        connection.rollback()
                        rejected(current.failure)
                    }
                }
            } catch (failure: Throwable) {
                rollbackBeforeRethrow(connection, failure)
            }
        }
    }

    override fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> = storageResult {
        if (record.binding != prior.binding || record.priorDigest != prior.digest) {
            return@storageResult rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
        }
        connections.use { connection ->
            connection.autoCommit = false
            try {
                val current = connection.loadMutationRecovery(prior.binding)
                val result = when (current) {
                    is MutationRecoveryLoadResult.Found -> when (current.record.digest) {
                        record.digest -> MutationRecoveryPersistResult.Durable(record)
                        prior.digest -> connection.persistTerminal(prior, record)
                        else -> rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
                    }
                    is MutationRecoveryLoadResult.Absent ->
                        rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
                    is MutationRecoveryLoadResult.Rejected -> rejected(current.failure)
                }
                if (result is MutationRecoveryPersistResult.Rejected) {
                    connection.rollback()
                }
                result
            } catch (failure: Throwable) {
                rollbackBeforeRethrow(connection, failure)
            }
        }
    }

    override fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult = try {
        connections.use { connection -> connection.loadMutationRecovery(binding) }
    } catch (_: SQLException) {
        MutationRecoveryLoadResult.Rejected(MutationRecoveryEvidenceFailure.STORAGE_UNAVAILABLE)
    }

    private fun Connection.persistApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> {
        record.appliedWrites.sources.forEachIndexed { ordinal, source ->
            prepareStatement(
                """INSERT INTO mutation_recovery_applied_write(
                    plan_binding, ordinal, source_path
                ) VALUES (?, ?, ?)""",
            ).use { statement ->
                statement.setString(1, record.binding.value)
                statement.setInt(2, ordinal)
                statement.setString(3, source.value)
                statement.executeUpdate()
            }
        }
        val updated = prepareStatement(
            """UPDATE mutation_recovery
                SET stage = 'APPLIED_WRITES_DURABLE', state_version = 1, record_digest = ?
                WHERE plan_binding = ? AND stage = 'PRE_WRITE_DURABLE'
                    AND state_version = 0 AND record_digest = ?""",
        ).use { statement ->
            statement.setString(1, record.digest.value)
            statement.setString(2, record.binding.value)
            statement.setString(3, prior.digest.value)
            statement.executeUpdate()
        }
        if (updated != 1) {
            return rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
        }
        faults.observe(MutationRecoveryFaultPoint.AFTER_APPLIED_WRITE)
        commit()
        faults.observe(MutationRecoveryFaultPoint.AFTER_APPLIED_COMMIT)
        return MutationRecoveryPersistResult.Durable(record)
    }

    private fun <Record : MutationRecoveryRecord.Terminal> Connection.persistTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> {
        val requirement = when (record) {
            is MutationRecoveryRecord.RolledBack -> SqlTerminalRequirement.Absent
            is MutationRecoveryRecord.RecoveryRequired ->
                SqlTerminalRequirement.Present(record.requirement)
        }
        val updated = prepareStatement(
            """UPDATE mutation_recovery
                SET stage = ?, state_version = 2, recovery_requirement = ?, record_digest = ?
                WHERE plan_binding = ? AND stage = 'APPLIED_WRITES_DURABLE'
                    AND state_version = 1 AND record_digest = ?""",
        ).use { statement ->
            statement.setString(1, record.stage.name)
            when (requirement) {
                SqlTerminalRequirement.Absent -> statement.setNull(2, java.sql.Types.VARCHAR)
                is SqlTerminalRequirement.Present -> statement.setString(2, requirement.value.name)
            }
            statement.setString(3, record.digest.value)
            statement.setString(4, record.binding.value)
            statement.setString(5, prior.digest.value)
            statement.executeUpdate()
        }
        if (updated != 1) {
            return rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
        }
        faults.observe(MutationRecoveryFaultPoint.AFTER_TERMINAL_WRITE)
        commit()
        faults.observe(MutationRecoveryFaultPoint.AFTER_TERMINAL_COMMIT)
        return MutationRecoveryPersistResult.Durable(record)
    }

    private fun existingPreparation(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = when (
        val loaded = load(record.binding)
    ) {
        is MutationRecoveryLoadResult.Found -> if (loaded.record.digest == record.digest) {
            MutationRecoveryPersistResult.Durable(record)
        } else {
            rejected(MutationRecoveryEvidenceFailure.PLAN_BINDING_COLLISION)
        }
        is MutationRecoveryLoadResult.Absent ->
            rejected(MutationRecoveryEvidenceFailure.CORRUPT_RECORD)
        is MutationRecoveryLoadResult.Rejected -> rejected(loaded.failure)
    }

    companion object {
        /** Opens one exact-root durable location without exposing a raw path to composition. */
        fun open(location: MutationDatabaseLocation): SqliteMutationRecoveryJournalOpenResult {
            val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
                ?: return SqliteMutationRecoveryJournalOpenResult.Rejected(
                    SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE,
                )
            return open(path)
        }

        /**
         * Proof transition: `Path -> SqliteMutationRecoveryJournalOpenResult`.
         *
         * Establishes a canonical database capability with a durable WAL/FULL-synchronous recovery
         * schema. [SqliteMutationRecoveryJournalOpenFailure] is the closed expected failure. Raw
         * path and JDBC access remain inside this SQLite adapter.
         */
        fun open(path: Path): SqliteMutationRecoveryJournalOpenResult =
            open(path, MutationRecoveryFaultInjector.Disabled)

        /**
         * Proof transition: `(Path, MutationRecoveryFaultInjector) ->
         * SqliteMutationRecoveryJournalOpenResult`.
         *
         * Establishes the same database capability with one module-internal deterministic crash
         * injector. [SqliteMutationRecoveryJournalOpenFailure] is the closed expected failure.
         * This boundary exists only for adapter fault proofs; the public boundary installs no
         * callback.
         */
        internal fun open(
            path: Path,
            faults: MutationRecoveryFaultInjector,
        ): SqliteMutationRecoveryJournalOpenResult {
            val database = when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return SqliteMutationRecoveryJournalOpenResult.Rejected(
                    admitted.failure,
                )
            }
            return try {
                val connections = SqliteMutationRecoveryConnections(database).initialize()
                SqliteMutationRecoveryJournalOpenResult.Opened(
                    SqliteMutationRecoveryJournal(connections, faults),
                )
            } catch (_: Exception) {
                SqliteMutationRecoveryJournalOpenResult.Rejected(
                    SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE,
                )
            }
        }

        internal fun retain(
            connections: InitializedSqliteMutationRecoveryConnections,
        ): SqliteMutationRecoveryJournal = SqliteMutationRecoveryJournal(
            connections,
            MutationRecoveryFaultInjector.Disabled,
        )
    }
}

private sealed interface SqlTerminalRequirement {
    data object Absent : SqlTerminalRequirement
    data class Present(val value: RecoveryRequirement) : SqlTerminalRequirement
}

private fun <T> storageResult(
    operation: () -> MutationRecoveryPersistResult<T>,
): MutationRecoveryPersistResult<T> where T : MutationRecoveryRecord = try {
    operation()
} catch (_: SQLException) {
    rejected(MutationRecoveryEvidenceFailure.STORAGE_UNAVAILABLE)
}

private fun <T> rejected(
    failure: MutationRecoveryEvidenceFailure,
): MutationRecoveryPersistResult<T> where T : MutationRecoveryRecord =
    MutationRecoveryPersistResult.Rejected(failure)

private fun rollbackBeforeRethrow(
    connection: Connection,
    failure: Throwable,
): Nothing {
    runCatching { connection.rollback() }
    throw failure
}
