package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement

enum class MutationRecoveryStage {
    PRE_WRITE_DURABLE,
    APPLIED_WRITES_DURABLE,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

@JvmInline
value class MutationRecoveryStateVersion internal constructor(
    val value: Int,
)

@JvmInline
value class MutationRecoveryRecordDigest internal constructor(
    val value: String,
)

enum class RecoveryRequirement {
    ROLLBACK_REJECTED,
}

enum class MutationRecoveryRecordFailure {
    APPLIED_WRITE_SET_MISMATCH,
}

/**
 * Closed durable recovery states bound transitively to exact plan and pre-write evidence.
 */
sealed interface MutationRecoveryRecord {
    val preparation: MutationRecoveryPreparation
    val stage: MutationRecoveryStage
    val version: MutationRecoveryStateVersion
    val digest: MutationRecoveryRecordDigest

    val binding: MutationPlanBinding
        get() = preparation.binding

    class PreWriteDurable internal constructor(
        override val preparation: MutationRecoveryPreparation,
        override val digest: MutationRecoveryRecordDigest,
    ) : MutationRecoveryRecord {
        override val stage: MutationRecoveryStage = MutationRecoveryStage.PRE_WRITE_DURABLE
        override val version: MutationRecoveryStateVersion = MutationRecoveryStateVersion(0)
    }

    class AppliedWritesDurable internal constructor(
        val priorDigest: MutationRecoveryRecordDigest,
        override val preparation: MutationRecoveryPreparation,
        val appliedWrites: AppliedRecoveryWriteSet,
        override val digest: MutationRecoveryRecordDigest,
    ) : MutationRecoveryRecord {
        override val stage: MutationRecoveryStage = MutationRecoveryStage.APPLIED_WRITES_DURABLE
        override val version: MutationRecoveryStateVersion = MutationRecoveryStateVersion(1)
    }

    sealed interface Terminal : MutationRecoveryRecord {
        val priorDigest: MutationRecoveryRecordDigest
        val appliedWrites: AppliedRecoveryWriteSet
    }

    class RolledBack internal constructor(
        override val priorDigest: MutationRecoveryRecordDigest,
        override val preparation: MutationRecoveryPreparation,
        override val appliedWrites: AppliedRecoveryWriteSet,
        override val digest: MutationRecoveryRecordDigest,
    ) : Terminal {
        override val stage: MutationRecoveryStage = MutationRecoveryStage.ROLLED_BACK
        override val version: MutationRecoveryStateVersion = MutationRecoveryStateVersion(2)
    }

    class RecoveryRequired internal constructor(
        override val priorDigest: MutationRecoveryRecordDigest,
        override val preparation: MutationRecoveryPreparation,
        override val appliedWrites: AppliedRecoveryWriteSet,
        val requirement: RecoveryRequirement,
        override val digest: MutationRecoveryRecordDigest,
    ) : Terminal {
        override val stage: MutationRecoveryStage = MutationRecoveryStage.RECOVERY_REQUIRED
        override val version: MutationRecoveryStateVersion = MutationRecoveryStateVersion(2)
    }

    companion object {
        /**
         * Proof transition: `MutationRecoveryPreparation -> PreWriteDurable`.
         *
         * Establishes version-zero state whose digest binds the exact plan and every byte-exact
         * preimage. There is no expected failure because the input is already admitted. Raw state
         * extraction is permitted only at the SQLite boundary.
         */
        fun prepare(preparation: MutationRecoveryPreparation): PreWriteDurable =
            PreWriteDurable(
                preparation,
                digest(preparation, MutationRecoveryStage.PRE_WRITE_DURABLE, emptyList()),
            )

        /**
         * Proof transition: `(PreWriteDurable, AppliedRecoveryWriteSet) -> Refinement<
         * AppliedWritesDurable, MutationRecoveryRecordFailure>`.
         *
         * Establishes a version-one applied set chained to exact durable pre-write evidence.
         * [MutationRecoveryRecordFailure] is the closed expected failure. Raw state extraction is
         * permitted only at the SQLite boundary.
         */
        fun recordApplied(
            prior: PreWriteDurable,
            appliedWrites: AppliedRecoveryWriteSet,
        ): Refinement<AppliedWritesDurable, MutationRecoveryRecordFailure> {
            if (appliedWrites.sources.any { source ->
                    prior.preparation.plannedWrites.none { write -> write.source == source }
                }
            ) {
                return Refinement.Rejected(
                    MutationRecoveryRecordFailure.APPLIED_WRITE_SET_MISMATCH,
                )
            }
            return Refinement.Refined(
                AppliedWritesDurable(
                    prior.digest,
                    prior.preparation,
                    appliedWrites,
                    digest(
                        prior.preparation,
                        MutationRecoveryStage.APPLIED_WRITES_DURABLE,
                        appliedWrites.sources,
                        prior.digest,
                    ),
                ),
            )
        }

        /**
         * Proof transition: `AppliedWritesDurable -> RolledBack`.
         *
         * Establishes a terminal version-two rollback record chained to the exact applied set.
         * There is no expected failure because physical rollback success is already carried by
         * the caller. Raw state extraction is permitted only at the SQLite boundary.
         */
        fun rolledBack(prior: AppliedWritesDurable): RolledBack = RolledBack(
            prior.digest,
            prior.preparation,
            prior.appliedWrites,
            digest(
                prior.preparation,
                MutationRecoveryStage.ROLLED_BACK,
                prior.appliedWrites.sources,
                prior.digest,
            ),
        )

        /**
         * Proof transition: `(AppliedWritesDurable, RecoveryRequirement) -> RecoveryRequired`.
         *
         * Establishes a terminal version-two unresolved recovery record chained to the exact
         * applied set and finite reason. There is no expected failure. Raw state extraction is
         * permitted only at the SQLite boundary.
         */
        fun recoveryRequired(
            prior: AppliedWritesDurable,
            requirement: RecoveryRequirement,
        ): RecoveryRequired = RecoveryRequired(
            prior.digest,
            prior.preparation,
            prior.appliedWrites,
            requirement,
            digest(
                prior.preparation,
                MutationRecoveryStage.RECOVERY_REQUIRED,
                prior.appliedWrites.sources,
                prior.digest,
                RecoveryDigestRequirement.Present(requirement),
            ),
        )

        private fun digest(
            preparation: MutationRecoveryPreparation,
            stage: MutationRecoveryStage,
            applied: List<RecoverySourcePath>,
            prior: MutationRecoveryRecordDigest = MutationRecoveryRecordDigest("0".repeat(64)),
            requirement: RecoveryDigestRequirement = RecoveryDigestRequirement.Absent,
        ): MutationRecoveryRecordDigest {
            val canonical = buildString {
                appendRecoveryField(preparation.binding.value)
                preparation.plannedWrites.forEach { write ->
                    appendRecoveryField(write.source.value)
                    appendRecoveryField(write.preimage.digest.value)
                    appendRecoveryField(write.preimage.encodedContent.value)
                }
                appendRecoveryField(stage.name)
                appendRecoveryField(prior.value)
                applied.forEach { source -> appendRecoveryField(source.value) }
                appendRecoveryField(
                    when (requirement) {
                        RecoveryDigestRequirement.Absent -> ""
                        is RecoveryDigestRequirement.Present -> requirement.value.name
                    },
                )
            }
            return MutationRecoveryRecordDigest(sha256(canonical.toByteArray()))
        }
    }
}

private sealed interface RecoveryDigestRequirement {
    data object Absent : RecoveryDigestRequirement
    data class Present(val value: RecoveryRequirement) : RecoveryDigestRequirement
}
