package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure

class HostedDiagnosticPorts private constructor(
    val compiler: DiagnosticCompilerPort,
) {
    companion object {
        internal fun retained(compiler: DiagnosticCompilerPort): HostedDiagnosticPorts =
            HostedDiagnosticPorts(compiler)
    }
}

sealed interface HostedDiagnosticAdmission {
    data class Admitted(val ports: HostedDiagnosticPorts) : HostedDiagnosticAdmission
    data class Rejected(val failure: HostedProjectAdmissionFailure) : HostedDiagnosticAdmission
}

fun admitHostedIntellijDiagnosticPorts(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    workspaces: WorkspaceInspectionOperations,
): HostedDiagnosticAdmission {
    when (val validation = ExistingProjectValidation.validate(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        ExistingProjectValidation.Validated -> Unit
        is ExistingProjectValidation.Rejected -> return HostedDiagnosticAdmission.Rejected(
            HostedProjectAdmissionFailure.ProjectRejected(validation.failure),
        )
    }
    val adapter = IntellijDiagnosticCompilerAdapter()
    val compiler = DiagnosticCompilerPort { scope ->
        if (project.isDisposed) return@DiagnosticCompilerPort diagnosticUnavailable()
        val lease = (workspaces.inspect() as? WorkspaceRuntimeState.Ready)
            ?.workspace
            ?.readLease
            ?: return@DiagnosticCompilerPort diagnosticUnavailable()
        adapter.read(project, lease, scope)
    }
    return HostedDiagnosticAdmission.Admitted(HostedDiagnosticPorts.retained(compiler))
}

private fun diagnosticUnavailable(): DiagnosticCompilation = DiagnosticCompilation.Rejected(
    DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
)
