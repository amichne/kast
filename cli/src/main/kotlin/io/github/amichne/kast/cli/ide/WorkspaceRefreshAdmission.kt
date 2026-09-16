package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult

internal fun WorkspaceRefreshCommand.admits(result: WorkspaceRefreshResult): Boolean =
    when (result) {
        is WorkspaceRefreshResult.Rejected -> true
        is WorkspaceRefreshResult.Configured -> this is WorkspaceRefreshCommand.Configure && rule == result.rule
        is WorkspaceRefreshResult.Pending -> admitsRequestId(result.requestId)
        is WorkspaceRefreshResult.Complete -> admitsRequestId(result.requestId)
        is WorkspaceRefreshResult.Failed -> admitsRequestId(result.requestId)
    }

private fun WorkspaceRefreshCommand.admitsRequestId(id: String): Boolean =
    when (this) {
        is WorkspaceRefreshCommand.Request -> requestId == id
        is WorkspaceRefreshCommand.Status -> requestId == id
        is WorkspaceRefreshCommand.Configure -> false
    }
