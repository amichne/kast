package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure

internal fun WorkspaceRefreshFailure.lifecycleFailure(): IdeLifecycleFailure =
    when (this) {
        WorkspaceRefreshFailure.MODEL_RELOAD_REQUIRED -> IdeLifecycleFailure.MODEL_RELOAD_REQUIRED
        WorkspaceRefreshFailure.UNSAVED_DOCUMENTS -> IdeLifecycleFailure.UNSAVED_DOCUMENTS
        WorkspaceRefreshFailure.UNLINKED_BUILD -> IdeLifecycleFailure.UNLINKED_BUILD
        WorkspaceRefreshFailure.DISPOSED -> IdeLifecycleFailure.DISPOSED
        WorkspaceRefreshFailure.CANCELLED -> IdeLifecycleFailure.CANCELLED
        WorkspaceRefreshFailure.DEADLINE_EXCEEDED -> IdeLifecycleFailure.DEADLINE_EXCEEDED
        WorkspaceRefreshFailure.CAPACITY -> IdeLifecycleFailure.CAPACITY
        WorkspaceRefreshFailure.REQUEST_CONFLICT -> IdeLifecycleFailure.REQUEST_CONFLICT
        WorkspaceRefreshFailure.UNKNOWN_REQUEST -> IdeLifecycleFailure.UNKNOWN_OPERATION
        WorkspaceRefreshFailure.INVALID_REQUEST -> IdeLifecycleFailure.INVALID_REQUEST
        WorkspaceRefreshFailure.ADMISSION_REJECTED,
        WorkspaceRefreshFailure.NEWER_CHANGE -> IdeLifecycleFailure.PROJECT_BUSY
        WorkspaceRefreshFailure.EFFECT_FAILED -> IdeLifecycleFailure.IMPORT_FAILED
    }
