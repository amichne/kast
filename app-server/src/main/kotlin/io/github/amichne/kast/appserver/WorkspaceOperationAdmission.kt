package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest

/** Restoring issued authority must never reload the model that granted it. */
enum class WorkspaceOperationAdmission {
    PREPARE,
    EXISTING_AUTHORITY,
}

internal fun DaemonOperationCall.workspaceAdmission(): WorkspaceOperationAdmission =
    when (this) {
        is DaemonOperationCall.Canonical -> WorkspaceOperationAdmission.EXISTING_AUTHORITY
        is DaemonOperationCall.Change -> WorkspaceOperationAdmission.EXISTING_AUTHORITY
        is DaemonOperationCall.PublicTool -> tool.canonical.workspaceAdmission()
    }

fun PublicToolCanonical.workspaceAdmission(): WorkspaceOperationAdmission =
    when (this) {
        is PublicToolCanonical.Source,
        is PublicToolCanonical.Change -> WorkspaceOperationAdmission.EXISTING_AUTHORITY
        is PublicToolCanonical.Diagnostics -> WorkspaceOperationAdmission.PREPARE
        is PublicToolCanonical.Query -> request.workspaceAdmission()
    }

private fun QueryRunRequest.workspaceAdmission(): WorkspaceOperationAdmission =
    when (this) {
        is QueryRunRequest.ReadResult,
        is QueryRunRequest.Resume -> WorkspaceOperationAdmission.EXISTING_AUTHORITY
        is QueryRunRequest.Run -> from.workspaceAdmission()
    }

private fun QueryFromDocument.workspaceAdmission(): WorkspaceOperationAdmission =
    when (this) {
        is QueryFromDocument.References,
        is QueryFromDocument.Result,
        is QueryFromDocument.Impact -> WorkspaceOperationAdmission.EXISTING_AUTHORITY
        is QueryFromDocument.Symbols,
        is QueryFromDocument.TextWord,
        is QueryFromDocument.Location -> WorkspaceOperationAdmission.PREPARE
    }
