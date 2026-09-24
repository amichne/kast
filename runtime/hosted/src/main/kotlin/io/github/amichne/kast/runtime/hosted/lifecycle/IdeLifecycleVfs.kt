package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.runtime.hosted.HostedVfsRefreshOutcome
import io.github.amichne.kast.runtime.hosted.awaitHostedVfsRefresh
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Project VFS readiness projected into the finite lifecycle failure contract. */
internal suspend fun lifecycleVfsFailure(project: Project, root: CanonicalWorkspaceRoot): IdeLifecycleFailure? =
    when (awaitHostedVfsRefresh(project, root)) {
        HostedVfsRefreshOutcome.READY -> null
        HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS -> IdeLifecycleFailure.UNSAVED_DOCUMENTS
        HostedVfsRefreshOutcome.DEADLINE_EXCEEDED -> IdeLifecycleFailure.DEADLINE_EXCEEDED
        HostedVfsRefreshOutcome.PROJECT_DISPOSED -> IdeLifecycleFailure.DISPOSED
        HostedVfsRefreshOutcome.ROOT_UNAVAILABLE,
        HostedVfsRefreshOutcome.FAILED -> IdeLifecycleFailure.PLATFORM_UNAVAILABLE
    }
