package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.sqlite.SqliteHostedChangeStores
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangePlanStore
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangeReceiptStore
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
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
        fun open(
            root: CanonicalWorkspaceRoot,
            observer: HostedChangeStorageObserver = HostedChangeStorageObserver.Logger,
        ): Refinement<HostedChangeResources, HostedChangeFailure> =
            openResources(root).observed(HostedChangeStorageStage.OPEN, observer)

        private fun openResources(
            root: CanonicalWorkspaceRoot
        ): Refinement<HostedChangeResources, HostedChangeFailure> {
            val state =
                when (
                    val result =
                        KastUserStateRoot.parse(Path.of(System.getProperty("user.home")).resolve(".kast").toString())
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedChangeFailure.StateRoot(result.failure))
                }
            val location =
                when (val result = HostedWorkspaceStateLocation.locate(state, root)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedChangeFailure.Location(result.failure))
                }
            val stores =
                when (val result = SqliteHostedChangeStores.open(location.mutationDatabase)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(result.failure.hosted())
                }
            return Refinement.Refined(HostedChangeResources(location, stores.plans, stores.journal, stores.receipts))
        }
    }
}
