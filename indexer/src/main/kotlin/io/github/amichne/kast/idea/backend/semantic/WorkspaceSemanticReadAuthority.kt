package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission

internal interface WorkspaceSemanticReadAuthority {
    fun status(): IdeaIndexSemanticAdmission.Status

    fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken

    fun isReadCurrent(token: IdeaIndexSemanticAdmission.WorkspaceReadToken): Boolean

    fun isReconciliationCurrent(token: IdeaIndexSemanticAdmission.ReconciliationToken): Boolean
}
