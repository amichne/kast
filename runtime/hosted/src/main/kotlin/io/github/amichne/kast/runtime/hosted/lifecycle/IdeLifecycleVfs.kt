package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.runtime.hosted.HostedVfsRefreshOutcome

/** Project VFS readiness projected into the finite lifecycle failure contract. */
internal suspend fun lifecycleVfsFailure(endpoint: HostedEndpointService): IdeLifecycleFailure? =
    when (endpoint.lifecycleVfsRefresh()) {
        HostedVfsRefreshOutcome.READY -> null
        HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS -> IdeLifecycleFailure.UNSAVED_DOCUMENTS
        HostedVfsRefreshOutcome.DEADLINE_EXCEEDED -> IdeLifecycleFailure.DEADLINE_EXCEEDED
        HostedVfsRefreshOutcome.PROJECT_DISPOSED -> IdeLifecycleFailure.DISPOSED
        HostedVfsRefreshOutcome.ROOT_UNAVAILABLE,
        HostedVfsRefreshOutcome.FAILED -> IdeLifecycleFailure.PLATFORM_UNAVAILABLE
    }
