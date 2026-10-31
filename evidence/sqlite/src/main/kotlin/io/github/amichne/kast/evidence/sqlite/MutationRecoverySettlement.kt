package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.verify.HistoricalLiveAddDeclarationReceipt
import io.github.amichne.kast.change.verify.HistoricalLiveChangeReceipt
import io.github.amichne.kast.change.verify.HistoricalLiveReplaceBodyReceipt
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord

sealed interface MutationRecoverySettlement {
    data object Settled : MutationRecoverySettlement

    data object Unresolved : MutationRecoverySettlement

    data class Rejected(val failure: MutationRecoveryEvidenceFailure) : MutationRecoverySettlement
}

/** Both live Host settlement and offline cleanup use the same reconstructed receipt-chain rule. */
internal fun inspectMutationSettlement(
    record: MutationRecoveryRecord,
    receipt: LiveChangeReceiptLookup,
): MutationRecoverySettlement =
    when (record) {
        is MutationRecoveryRecord.PreWriteDurable,
        is MutationRecoveryRecord.RecoveryRequired -> MutationRecoverySettlement.Unresolved
        is MutationRecoveryRecord.RolledBack ->
            when (receipt) {
                LiveChangeReceiptLookup.Missing -> MutationRecoverySettlement.Settled
                is LiveChangeReceiptLookup.Found,
                is LiveChangeReceiptLookup.Rejected ->
                    MutationRecoverySettlement.Rejected(MutationRecoveryEvidenceFailure.CORRUPT_RECORD)
            }
        is MutationRecoveryRecord.AppliedWritesDurable ->
            when (receipt) {
                LiveChangeReceiptLookup.Missing -> MutationRecoverySettlement.Unresolved
                is LiveChangeReceiptLookup.Rejected ->
                    MutationRecoverySettlement.Rejected(MutationRecoveryEvidenceFailure.CORRUPT_RECORD)
                is LiveChangeReceiptLookup.Found -> inspectReceiptChain(record, receipt.receipt)
            }
    }

private fun inspectReceiptChain(
    record: MutationRecoveryRecord.AppliedWritesDurable,
    receipt: HistoricalLiveChangeReceipt,
): MutationRecoverySettlement {
    val recovery =
        when (receipt) {
            is HistoricalLiveAddDeclarationReceipt -> receipt.recovery
            is HistoricalLiveReplaceBodyReceipt -> receipt.recovery
        }
    return if (
        recovery.binding.value != record.binding.value ||
            recovery.preparedDigest.value != record.priorDigest.value ||
            recovery.appliedDigest.value != record.digest.value
    )
        MutationRecoverySettlement.Rejected(MutationRecoveryEvidenceFailure.CORRUPT_RECORD)
    else MutationRecoverySettlement.Settled
}
