package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.AppliedRecoveryWriteSet
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPreparation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.MutationRecoveryStage
import io.github.amichne.kast.evidence.contract.PlannedRecoveryWrite
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoveryRequirement
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import java.sql.Connection

internal fun Connection.insertPreparation(
    record: MutationRecoveryRecord.PreWriteDurable,
): Boolean {
    val inserted = prepareStatement(
        """INSERT OR IGNORE INTO mutation_recovery(
            plan_binding, stage, state_version, recovery_requirement, record_digest
        ) VALUES (?, 'PRE_WRITE_DURABLE', 0, NULL, ?)""",
    ).use { statement ->
        statement.setString(1, record.binding.value)
        statement.setString(2, record.digest.value)
        statement.executeUpdate() == 1
    }
    if (!inserted) return false
    record.preparation.plannedWrites.forEachIndexed { ordinal, write ->
        prepareStatement(
            """INSERT INTO mutation_recovery_planned_write(
                plan_binding, ordinal, source_path, preimage_sha256, preimage_base64
            ) VALUES (?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.setString(1, record.binding.value)
            statement.setInt(2, ordinal)
            statement.setString(3, write.source.value)
            statement.setString(4, write.preimage.digest.value)
            statement.setString(5, write.preimage.encodedContent.value)
            statement.executeUpdate()
        }
    }
    return true
}

/**
 * Proof transition: `MutationPlanBinding + SQLite rows -> MutationRecoveryLoadResult`.
 *
 * Reconstructs every state transition from typed planned and applied write evidence, then accepts
 * only the exact stored digest. Corruption is closed by [MutationRecoveryEvidenceFailure]. Raw
 * columns are extracted only inside this SQLite decoder.
 */
internal fun Connection.loadMutationRecovery(
    binding: MutationPlanBinding,
): MutationRecoveryLoadResult {
    val raw = prepareStatement(
        """SELECT stage, state_version, recovery_requirement, record_digest
            FROM mutation_recovery WHERE plan_binding = ?""",
    ).use { statement ->
        statement.setString(1, binding.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return MutationRecoveryLoadResult.Absent(binding)
            RawRecoveryRow(
                stage = rows.getString("stage"),
                version = rows.getInt("state_version"),
                requirement = rows.getString("recovery_requirement").toSqlRequirement(),
                digest = rows.getString("record_digest"),
            )
        }
    }
    val preparation = when (val loaded = loadPreparation(binding)) {
        is Refinement.Refined -> loaded.value
        is Refinement.Rejected -> return corrupt()
    }
    val prepared = MutationRecoveryRecord.prepare(preparation)
    val reconstructed = when (raw.stage) {
        MutationRecoveryStage.PRE_WRITE_DURABLE.name -> when {
            raw.version != 0 || raw.requirement !is SqlRecoveryRequirement.Absent -> return corrupt()
            else -> prepared
        }
        MutationRecoveryStage.APPLIED_WRITES_DURABLE.name -> {
            if (raw.version != 1 || raw.requirement !is SqlRecoveryRequirement.Absent) {
                return corrupt()
            }
            when (val applied = reconstructApplied(prepared)) {
                is Refinement.Refined -> applied.value
                is Refinement.Rejected -> return corrupt()
            }
        }
        MutationRecoveryStage.ROLLED_BACK.name -> {
            if (raw.version != 2 || raw.requirement !is SqlRecoveryRequirement.Absent) {
                return corrupt()
            }
            val applied = when (val reconstructed = reconstructApplied(prepared)) {
                is Refinement.Refined -> reconstructed.value
                is Refinement.Rejected -> return corrupt()
            }
            MutationRecoveryRecord.rolledBack(applied)
        }
        MutationRecoveryStage.RECOVERY_REQUIRED.name -> {
            if (raw.version != 2) return corrupt()
            val requirement = when (val stored = raw.requirement) {
                is SqlRecoveryRequirement.Present -> stored.value
                SqlRecoveryRequirement.Absent,
                SqlRecoveryRequirement.Invalid,
                    -> return corrupt()
            }
            MutationRecoveryRecord.recoveryRequired(
                when (val reconstructed = reconstructApplied(prepared)) {
                    is Refinement.Refined -> reconstructed.value
                    is Refinement.Rejected -> return corrupt()
                },
                requirement,
            )
        }
        else -> return corrupt()
    }
    return if (reconstructed.digest.value == raw.digest) {
        MutationRecoveryLoadResult.Found(reconstructed)
    } else {
        corrupt()
    }
}

/**
 * Proof transition: stored planned-write rows -> `Refinement<MutationRecoveryPreparation,
 * SqlMutationRecoveryDecodeFailure>`.
 *
 * Establishes contiguous ordering, canonical paths, byte-exact preimages, and one matching plan
 * binding. [SqlMutationRecoveryDecodeFailure] is the closed expected failure. Raw columns remain
 * inside this SQLite decoder.
 */
private fun Connection.loadPreparation(
    binding: MutationPlanBinding,
): Refinement<MutationRecoveryPreparation, SqlMutationRecoveryDecodeFailure> {
    val writes = prepareStatement(
        """SELECT ordinal, source_path, preimage_sha256, preimage_base64
            FROM mutation_recovery_planned_write
            WHERE plan_binding = ? ORDER BY ordinal""",
    ).use { statement ->
        statement.setString(1, binding.value)
        statement.executeQuery().use { rows ->
            val collected = mutableListOf<PlannedRecoveryWrite>()
            var expectedOrdinal = 0
            while (rows.next()) {
                if (rows.getInt("ordinal") != expectedOrdinal) return decodeRejected()
                val source = when (val parsed = RecoverySourcePath.parse(rows.getString("source_path"))) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return decodeRejected()
                }
                val preimage = when (val restored = RecoveryPreimage.restore(
                    rows.getString("preimage_sha256"),
                    rows.getString("preimage_base64"),
                )) {
                    is Refinement.Refined -> restored.value
                    is Refinement.Rejected -> return decodeRejected()
                }
                collected += PlannedRecoveryWrite(source, preimage)
                expectedOrdinal += 1
            }
            collected
        }
    }
    return when (val admitted = MutationRecoveryPreparation.admit(binding, writes)) {
        is Refinement.Refined -> Refinement.Refined(admitted.value)
        is Refinement.Rejected -> decodeRejected()
    }
}

/**
 * Proof transition: stored applied-write rows plus `PreWriteDurable` -> `Refinement<
 * AppliedWritesDurable, SqlMutationRecoveryDecodeFailure>`.
 *
 * Establishes a contiguous deterministic subset of the planned writes and re-derives its digest
 * chain. [SqlMutationRecoveryDecodeFailure] is the closed expected failure. Raw columns remain
 * inside this SQLite decoder.
 */
private fun Connection.reconstructApplied(
    prepared: MutationRecoveryRecord.PreWriteDurable,
): Refinement<MutationRecoveryRecord.AppliedWritesDurable, SqlMutationRecoveryDecodeFailure> {
    val sources = prepareStatement(
        """SELECT ordinal, source_path FROM mutation_recovery_applied_write
            WHERE plan_binding = ? ORDER BY ordinal""",
    ).use { statement ->
        statement.setString(1, prepared.binding.value)
        statement.executeQuery().use { rows ->
            val collected = mutableListOf<RecoverySourcePath>()
            var expectedOrdinal = 0
            while (rows.next()) {
                if (rows.getInt("ordinal") != expectedOrdinal) return decodeRejected()
                when (val parsed = RecoverySourcePath.parse(rows.getString("source_path"))) {
                    is Refinement.Refined -> collected += parsed.value
                    is Refinement.Rejected -> return decodeRejected()
                }
                expectedOrdinal += 1
            }
            collected
        }
    }
    val writeSet = when (val admitted = AppliedRecoveryWriteSet.admit(
        prepared.preparation.plannedWrites,
        sources,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return decodeRejected()
    }
    return when (val record = MutationRecoveryRecord.recordApplied(prepared, writeSet)) {
        is Refinement.Refined -> Refinement.Refined(record.value)
        is Refinement.Rejected -> decodeRejected()
    }
}

private enum class SqlMutationRecoveryDecodeFailure {
    CORRUPT,
}

private fun <T> decodeRejected(): Refinement<T, SqlMutationRecoveryDecodeFailure> =
    Refinement.Rejected(SqlMutationRecoveryDecodeFailure.CORRUPT)

private data class RawRecoveryRow(
    val stage: String,
    val version: Int,
    val requirement: SqlRecoveryRequirement,
    val digest: String,
)

private sealed interface SqlRecoveryRequirement {
    data object Absent : SqlRecoveryRequirement
    data class Present(val value: RecoveryRequirement) : SqlRecoveryRequirement
    data object Invalid : SqlRecoveryRequirement
}

/** Converts the nullable JDBC column directly into a closed raw-row state. */
private fun String?.toSqlRequirement(): SqlRecoveryRequirement = when (this) {
    null -> SqlRecoveryRequirement.Absent
    RecoveryRequirement.ROLLBACK_REJECTED.name ->
        SqlRecoveryRequirement.Present(RecoveryRequirement.ROLLBACK_REJECTED)
    else -> SqlRecoveryRequirement.Invalid
}

private fun corrupt(): MutationRecoveryLoadResult.Rejected = MutationRecoveryLoadResult.Rejected(
    MutationRecoveryEvidenceFailure.CORRUPT_RECORD,
)
