package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.ProjectManager
import io.github.amichne.kast.evidence.sqlite.MutationRecoverySettlement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.hosted.HostedChangeResources

/** Every open project keeps its durable mutation evidence independent of process liveness. */
internal fun inspectHostNativeSettlement(hasPreparation: () -> Boolean): HostNativeSettlement {
    if (hasPreparation()) return HostNativeSettlement.Busy
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
