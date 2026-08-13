package io.github.amichne.kast.change.recovery.service

import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationRecoveryPreparationRejection
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.recovery.contract.PreparedAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.PreparedAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.recovery.spi.AddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.spi.DurableAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.Refinement

sealed interface AddDeclarationRecoveryPreparationServiceFailure {
    data class Admission(
        val rejection: AddDeclarationRecoveryPreparationRejection,
    ) : AddDeclarationRecoveryPreparationServiceFailure

    data class Durable(
        val failure: DurableAddDeclarationRecoveryFailure,
    ) : AddDeclarationRecoveryPreparationServiceFailure

    data class Journal(
        val failure: AddDeclarationPlanJournalFailure,
    ) : AddDeclarationRecoveryPreparationServiceFailure

    data class InconsistentPreparedProof(
        val failure: PreparedAddDeclarationRecoveryFailure,
    ) : AddDeclarationRecoveryPreparationServiceFailure

    data class InconsistentJournalRecord(
        val failure: JournaledAddDeclarationRecoveryFailure,
    ) : AddDeclarationRecoveryPreparationServiceFailure
}

sealed interface PrepareApprovedAddDeclarationRecoveryResult {
    data class Prepared(
        val recovery: JournaledAddDeclarationRecovery,
    ) : PrepareApprovedAddDeclarationRecoveryResult

    data class Rejected(
        val failure: AddDeclarationRecoveryPreparationServiceFailure,
    ) : PrepareApprovedAddDeclarationRecoveryResult
}

class AddDeclarationRecoveryPreparationService(
    private val journal: AddDeclarationPlanJournal,
    private val preparer: AddDeclarationRecoveryPreparer,
) {
    /**
     * Proof transition:
     * approved persisted plan plus revalidated add-declaration to
     * `PrepareApprovedAddDeclarationRecoveryResult`.
     *
     * A prepared result establishes, in order, exact admission, a forced physical before image,
     * and an exact recovery-prepared journal CAS. Expected failures are closed by
     * `AddDeclarationRecoveryPreparationServiceFailure` and every rejection proves this service
     * began no source mutation. Raw effects remain confined to the injected adapters.
     */
    fun prepare(
        approved: PersistedAddDeclarationPlan.Approved,
        revalidated: RevalidatedAddDeclaration,
    ): PrepareApprovedAddDeclarationRecoveryResult {
        val command = when (val admission = PrepareAddDeclarationRecovery.admit(approved, revalidated)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return rejected(
                AddDeclarationRecoveryPreparationServiceFailure.Admission(admission.failure),
            )
        }
        val durable = when (val result = preparer.prepare(command.revalidated.recovery)) {
            is DurableAddDeclarationRecoveryResult.Prepared -> result.recovery
            is DurableAddDeclarationRecoveryResult.Rejected -> return rejected(
                AddDeclarationRecoveryPreparationServiceFailure.Durable(result.failure),
            )
        }
        val prepared = when (val result = PreparedAddDeclarationRecovery.admit(revalidated, durable)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejected(
                AddDeclarationRecoveryPreparationServiceFailure.InconsistentPreparedProof(
                    result.failure,
                ),
            )
        }
        val record = when (val result = journal.prepareRecovery(command)) {
            is PrepareAddDeclarationRecoveryResult.Prepared -> result.record
            is PrepareAddDeclarationRecoveryResult.Rejected -> return rejected(
                AddDeclarationRecoveryPreparationServiceFailure.Journal(result.failure),
            )
        }
        return when (val result = JournaledAddDeclarationRecovery.admit(prepared, record)) {
            is Refinement.Refined -> PrepareApprovedAddDeclarationRecoveryResult.Prepared(result.value)
            is Refinement.Rejected -> rejected(
                AddDeclarationRecoveryPreparationServiceFailure.InconsistentJournalRecord(
                    result.failure,
                ),
            )
        }
    }

    private fun rejected(
        failure: AddDeclarationRecoveryPreparationServiceFailure,
    ): PrepareApprovedAddDeclarationRecoveryResult.Rejected =
        PrepareApprovedAddDeclarationRecoveryResult.Rejected(failure)
}
