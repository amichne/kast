package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.PreparedAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement

enum class JournaledAddDeclarationRecoveryFailure {
    JOURNAL_RECORD_MISMATCH,
}

@ConsistentCopyVisibility
data class JournaledAddDeclarationRecovery private constructor(
    val prepared: PreparedAddDeclarationRecovery,
    val record: RecoveryPreparedAddDeclaration,
) {
    val revalidated: RevalidatedAddDeclaration
        get() = prepared.revalidated

    val durableRecovery: DurableAddDeclarationRecovery
        get() = prepared.durableRecovery

    companion object {
        /**
         * Proof transition:
         * prepared durable recovery plus recovery-prepared journal record to
         * `Refinement<JournaledAddDeclarationRecovery,
         * JournaledAddDeclarationRecoveryFailure>`.
         *
         * Establishes that physical durability and the exact durable lifecycle record agree on
         * plan, recovery material, and mutation-not-begun state. The closed expected failure is
         * `JournaledAddDeclarationRecoveryFailure`; raw journal fields remain confined to its
         * adapter boundary.
         */
        fun admit(
            prepared: PreparedAddDeclarationRecovery,
            record: RecoveryPreparedAddDeclaration,
        ): Refinement<JournaledAddDeclarationRecovery, JournaledAddDeclarationRecoveryFailure> =
            if (record.plan != prepared.revalidated.plan ||
                record.recovery != prepared.durableRecovery.material
            ) {
                Refinement.Rejected(JournaledAddDeclarationRecoveryFailure.JOURNAL_RECORD_MISMATCH)
            } else {
                Refinement.Refined(JournaledAddDeclarationRecovery(prepared, record))
            }
    }
}
