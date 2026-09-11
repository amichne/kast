package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject

/** Captures probes from one endpoint owner and the exact admitted project/epoch for a request. */
internal class HostedReadFreshnessOwner(
    private val project: Project,
    private val owner: Disposable,
    private val liveAuthorities: HostedLiveReadAuthoritySession,
) {
    fun capture(
        admitted: AdmittedIdeProject,
        epoch: ProjectReadEpoch<*>,
        authority: LiveSemanticReadAuthority,
    ): HostedReadFreshness =
        HostedReadFreshness(
            beforeWrite = { observeBeforeWrite(admitted, epoch, authority) },
            current = {
                readAction {
                    when (val saved = checkSavedDocuments(project)) {
                        is SavedDocuments.Rejected -> return@readAction Refinement.Rejected(saved.failure)
                        SavedDocuments.Clean -> Unit
                    }
                    when (val current = admitted.admitVfsPassiveRead(epoch)) {
                        is VfsPassiveReadAdmission.Admitted -> Refinement.Refined(Unit)
                        is VfsPassiveReadAdmission.Rejected ->
                            Refinement.Rejected(HostedQueryFailure.Freshness(current.failure))
                    }
                }
            },
        )

    private fun observeBeforeWrite(
        admitted: AdmittedIdeProject,
        epoch: ProjectReadEpoch<*>,
        authority: LiveSemanticReadAuthority,
    ): Refinement<Unit, HostedQueryFailure> {
        if (Disposer.isDisposed(owner) || project.isDisposed) {
            return Refinement.Rejected(HostedQueryFailure.RETIRED)
        }
        if (
            !ApplicationManager.getApplication().isDispatchThread ||
                !ApplicationManager.getApplication().isWriteAccessAllowed
        ) {
            return Refinement.Rejected(HostedQueryFailure.WRONG_THREAD)
        }
        when (val saved = checkSavedDocuments(project)) {
            is SavedDocuments.Rejected -> return Refinement.Rejected(saved.failure)
            SavedDocuments.Clean -> Unit
        }
        val current =
            when (val checked = admitted.admitPreWriteState(epoch)) {
                is VfsPassiveReadAdmission.Admitted -> checked.capability
                is VfsPassiveReadAdmission.Rejected ->
                    return Refinement.Rejected(HostedQueryFailure.Freshness(checked.failure))
            }
        return when (val observed = liveAuthorities.admit(current)) {
            is Refinement.Refined ->
                if (observed.value.reference == authority.reference) Refinement.Refined(Unit)
                else Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
            is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.LiveAuthority(observed.failure))
        }
    }
}
