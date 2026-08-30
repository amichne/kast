package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure

class HostedRelationPorts private constructor(
    val compiler: RelationCompilerPort,
) {
    companion object {
        internal fun retained(compiler: RelationCompilerPort): HostedRelationPorts =
            HostedRelationPorts(compiler)
    }
}

sealed interface HostedRelationAdmission {
    data class Admitted(val ports: HostedRelationPorts) : HostedRelationAdmission
    data class Rejected(val failure: HostedProjectAdmissionFailure) : HostedRelationAdmission
}

fun admitHostedIntellijRelationPorts(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    workspaces: WorkspaceInspectionOperations,
    scopes: InstalledRelationScopeOperations,
): HostedRelationAdmission {
    when (val validation = ExistingProjectValidation.validate(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        ExistingProjectValidation.Validated -> Unit
        is ExistingProjectValidation.Rejected -> return HostedRelationAdmission.Rejected(
            HostedProjectAdmissionFailure.ProjectRejected(validation.failure),
        )
    }
    val adapter = IntellijRelationCompilerAdapter()
    val compiler = RelationCompilerPort { request ->
        if (project.isDisposed) return@RelationCompilerPort relationUnavailable()
        val lease = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)
            ?.workspace
            ?.readLease
            ?: return@RelationCompilerPort relationUnavailable()
        adapter.read(project, lease, request, scopes.compile(lease))
    }
    return HostedRelationAdmission.Admitted(HostedRelationPorts.retained(compiler))
}

private fun relationUnavailable(): RelationCompilation = RelationCompilation.Rejected(
    RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)
