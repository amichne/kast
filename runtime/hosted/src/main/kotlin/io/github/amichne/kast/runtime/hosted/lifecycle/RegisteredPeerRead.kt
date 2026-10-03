package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.util.UUID

/** Native registry lookup only. The supplied root remains syntax until it matches the actual canonical project root. */
internal suspend fun <Value> readRegisteredPeer(
    projects: Map<UUID, Project>,
    expected: ImpactSemanticBasisDocument.Live,
    canonicalRoot: (String) -> Refinement<CanonicalWorkspaceRoot, IdeLifecycleFailure>,
    action: suspend (Project, CanonicalWorkspaceRoot) -> HostedSemanticReadResult<Value>,
): HostedSemanticReadResult<Value> {
    val host =
        try {
            UUID.fromString(expected.host.value)
        } catch (_: IllegalArgumentException) {
            return peerRejected(HostedQueryFailure.WRONG_PROJECT)
        }
    if (host.toString() != expected.host.value) return peerRejected(HostedQueryFailure.WRONG_PROJECT)
    val project = projects[host] ?: return peerRejected(HostedQueryFailure.PROJECT_UNAVAILABLE)
    if (project.isDisposed) return peerRejected(HostedQueryFailure.PROJECT_UNAVAILABLE)
    val path = project.basePath ?: return peerRejected(HostedQueryFailure.PROJECT_UNAVAILABLE)
    val root =
        when (val actual = canonicalRoot(path)) {
            is Refinement.Refined -> actual.value
            is Refinement.Rejected -> return peerRejected(HostedQueryFailure.PROJECT_UNAVAILABLE)
        }
    if (
        root.value != expected.root.value ||
            project.getService(HostedQueryService::class.java).hostLifetime.value != host
    )
        return peerRejected(HostedQueryFailure.WRONG_PROJECT)
    return action(project, root)
}

private fun peerRejected(failure: HostedQueryFailure) =
    HostedSemanticReadResult.Rejected(failure, HostedQueryStage.REQUEST_ADMISSION)
