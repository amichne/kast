package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.evidence.sqlite.MutationRecoverySettlement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.hosted.HostedChangeResources

/** Every open project keeps its durable mutation evidence independent of process liveness. */
internal fun inspectHostNativeSettlement(hasPreparation: () -> Boolean): HostNativeSettlement {
    if (hasPreparation()) return HostNativeSettlement.Busy
    if (
        ManagedHeadlessHost.current() != null &&
            com.intellij.openapi.progress.ProgressManager.getInstance().hasUnsafeProgressIndicator()
    )
        return HostNativeSettlement.Busy
    for (project in ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }) {
        val root =
            when (val parsed = canonicalLifecycleRoot(project.basePath ?: return HostNativeSettlement.Busy)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return HostNativeSettlement.Busy
            }
        val resources =
            when (val opened = HostedChangeResources.open(root)) {
                is Refinement.Refined -> opened.value
                is Refinement.Rejected -> return HostNativeSettlement.UnresolvedMutation
            }
        when (resources.journal.settlement()) {
            MutationRecoverySettlement.Settled -> Unit
            MutationRecoverySettlement.Unresolved,
            is MutationRecoverySettlement.Rejected -> return HostNativeSettlement.UnresolvedMutation
        }
    }
    return HostNativeSettlement.Settled
}

/** Cheap idle-clock observation; settlement repeats the full native and mutation inspection under the fence. */
internal fun observeHostNativeWork(hasPreparation: () -> Boolean): Boolean =
    hasPreparation() ||
        com.intellij.openapi.progress.ProgressManager.getInstance().hasUnsafeProgressIndicator() ||
        observeHostIndexing()

/** Whether any open project is in dumb mode; exit evidence records this so a later reader knows how complete it was. */
internal fun observeHostIndexing(): Boolean =
    observeOpenHostProjects().any { com.intellij.openapi.project.DumbService.isDumb(it) }

/** The projects that are open now; a reader must expect any of them to close while it looks. */
internal fun observeOpenHostProjects(): List<com.intellij.openapi.project.Project> =
    ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
