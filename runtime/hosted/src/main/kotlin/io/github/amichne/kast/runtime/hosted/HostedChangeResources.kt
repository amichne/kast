package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangePlanStore
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangePlanStoreOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangeReceiptStore
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangeReceiptStoreOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path

internal data class HostedChangeResources(
    val location: HostedWorkspaceStateLocation,
    val plans: SqliteLiveChangePlanStore,
    val journal: SqliteMutationRecoveryJournal,
    val receipts: SqliteLiveChangeReceiptStore,
) {
    companion object {
        fun open(root: CanonicalWorkspaceRoot): Refinement<HostedChangeResources, HostedEndpointFailure> {
            val rejected = Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE)
            val state =
                when (
                    val parsed =
                        KastUserStateRoot.parse(Path.of(System.getProperty("user.home")).resolve(".kast").toString())
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return rejected
                }
            val location =
                when (val located = HostedWorkspaceStateLocation.locate(state, root)) {
                    is Refinement.Refined -> located.value
                    is Refinement.Rejected -> return rejected
                }
            val plans =
                when (val opened = SqliteLiveChangePlanStore.open(location.mutationDatabase)) {
                    is SqliteLiveChangePlanStoreOpenResult.Opened -> opened.store
                    is SqliteLiveChangePlanStoreOpenResult.Rejected -> return rejected
                }
            val journal =
                when (val opened = SqliteMutationRecoveryJournal.open(location.mutationDatabase)) {
                    is SqliteMutationRecoveryJournalOpenResult.Opened -> opened.journal
                    is SqliteMutationRecoveryJournalOpenResult.Rejected -> return rejected
                }
            val receipts =
                when (val opened = SqliteLiveChangeReceiptStore.open(location.mutationDatabase)) {
                    is SqliteLiveChangeReceiptStoreOpenResult.Opened -> opened.store
                    is SqliteLiveChangeReceiptStoreOpenResult.Rejected -> return rejected
                }
            return Refinement.Refined(HostedChangeResources(location, plans, journal, receipts))
        }
    }
}
