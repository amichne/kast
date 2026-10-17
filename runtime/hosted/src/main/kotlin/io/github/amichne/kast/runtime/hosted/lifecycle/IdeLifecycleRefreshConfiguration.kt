package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.runtime.hosted.HostedEndpointService

internal fun configureLifecycleRefresh(
    project: Project,
    target: IdeProjectTarget,
    rule: io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule,
): IdeLifecycleResult {
    if (!TrustedProjects.isProjectTrusted(project))
        return IdeLifecycleResult.Blocked(IdeLifecycleFailure.TRUST_REQUIRED)
    return when (
        val configured =
            project
                .getService(HostedEndpointService::class.java)
                .lifecycleRefresh(WorkspaceRefreshCommand.Configure(rule))
    ) {
        is WorkspaceRefreshResult.Configured -> IdeLifecycleResult.Configured(target, configured.rule)
        is WorkspaceRefreshResult.Rejected -> IdeLifecycleResult.Blocked(configured.reason.lifecycleFailure())
        else -> IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
    }
}
