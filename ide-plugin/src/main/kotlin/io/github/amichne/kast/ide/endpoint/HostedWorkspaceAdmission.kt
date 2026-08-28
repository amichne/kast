package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import java.nio.file.Path

internal class HostedWorkspaceModelAdmission private constructor(
    val scope: WorkspaceSearchScopeModelCompilation.Compiled,
    val sourceRoots: List<SourceRoot>,
) {
    companion object {
        fun admit(
            scope: WorkspaceSearchScopeModelCompilation.Compiled,
            sourceRoots: List<SourceRoot>,
        ): HostedWorkspaceModelAdmission = HostedWorkspaceModelAdmission(scope, sourceRoots)
    }

    fun reconcile(sourceState: WorkspaceStateIdentity): HostedWorkspaceAdmissionResult =
        when (val reconciled = ReconciledWorkspace.admit(
                WorkspaceCandidate(scope.model.workspaceRoot, sourceState),
                WorkspaceEvidenceKind.entries.toSet(),
                sourceRoots,
            )) {
            is Refinement.Refined -> HostedWorkspaceAdmissionResult.Admitted(
                HostedWorkspaceAdmission(reconciled.value, scope),
            )
            is Refinement.Rejected -> rejected(HostedWorkspaceAdmissionFailure.WORKSPACE_REJECTED)
        }
}

internal class HostedWorkspaceAdmission(
    private val reconciled: ReconciledWorkspace,
    val scope: WorkspaceSearchScopeModelCompilation.Compiled,
) {
    val sourceState: WorkspaceStateIdentity
        get() = reconciled.candidate.sourceState

    fun publish(generation: EvidenceGeneration): PublishedWorkspace =
        PublishedWorkspace.publish(reconciled, generation)
}

internal enum class HostedWorkspaceAdmissionFailure {
    MODEL_REJECTED,
    SOURCE_ROOT_REJECTED,
    SOURCE_STATE_REJECTED,
    WORKSPACE_REJECTED,
}

internal sealed interface HostedWorkspaceAdmissionResult {
    data class Admitted(val admission: HostedWorkspaceAdmission) : HostedWorkspaceAdmissionResult
    data class Rejected(val failure: HostedWorkspaceAdmissionFailure) :
        HostedWorkspaceAdmissionResult
}

/** Startup-only refinement from the captured detached model to hosted workspace authority. */
internal sealed interface HostedWorkspaceModelAdmissionResult {
    data class Admitted(val admission: HostedWorkspaceModelAdmission) :
        HostedWorkspaceModelAdmissionResult
    data class Rejected(val failure: HostedWorkspaceAdmissionFailure) :
        HostedWorkspaceModelAdmissionResult
}

internal fun admitHostedWorkspaceModel(
    model: DetachedIdeWorkspaceModel,
): HostedWorkspaceModelAdmissionResult {
    val rootPath = Path.of(model.canonicalRoot.value)
    val boundaries = model.modules.flatMap { module ->
        module.sourceRoots.map { source ->
            WorkspaceSourceRootBoundary(
                ideaModuleName = module.name.value,
                linkedBuildRoot = rootPath.resolve(module.owner.buildRoot.value).normalize(),
                gradleProjectPath = module.owner.projectIdentity.value,
                sourceSetName = source.kind.sourceSet(),
                sourceRoot = rootPath.resolve(source.location.value).normalize(),
                sourceKind = source.kind.scopeKind(),
                provenance = source.provenance.scopeProvenance(),
            )
        }
    }
    val scope = when (val compiled = WorkspaceSearchScopeModel.compile(
        model.canonicalRoot,
        ImportedWorkspaceModelState.COMPLETE,
        boundaries,
    )) {
        is WorkspaceSearchScopeModelCompilation.Compiled -> compiled
        is WorkspaceSearchScopeModelCompilation.Rejected -> return rejectedModel(
            HostedWorkspaceAdmissionFailure.MODEL_REJECTED,
        )
    }
    val sourceRoots = scope.model.sourceRoots.map { source ->
        val relative = runCatching {
            rootPath.relativize(Path.of(source.sourceRoot.value)).normalize().toString()
        }.getOrNull() ?: return rejectedModel(
            HostedWorkspaceAdmissionFailure.SOURCE_ROOT_REJECTED,
        )
        when (val admitted = SourceRoot.admit(
            GradleSourceRootEvidence(
                source.module.value,
                source.project.buildRoot.value,
                source.project.projectPath.value,
                source.sourceSet.value,
                relative,
                when (source.provenance) {
                    WorkspaceSourceRootProvenance.AUTHORED -> SourceRootProvenance.Authored
                    WorkspaceSourceRootProvenance.GENERATED -> SourceRootProvenance.Generated
                    WorkspaceSourceRootProvenance.UNKNOWN -> return rejectedModel(
                        HostedWorkspaceAdmissionFailure.SOURCE_ROOT_REJECTED,
                    )
                },
            ),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejectedModel(
                HostedWorkspaceAdmissionFailure.SOURCE_ROOT_REJECTED,
            )
        }
    }
    return HostedWorkspaceModelAdmissionResult.Admitted(
        HostedWorkspaceModelAdmission.admit(scope, sourceRoots),
    )
}

private fun DetachedSourceRootKind.sourceSet() = when (this) {
    DetachedSourceRootKind.PRODUCTION,
    DetachedSourceRootKind.RESOURCE,
    -> "main"
    DetachedSourceRootKind.TEST,
    DetachedSourceRootKind.TEST_RESOURCE,
    -> "test"
}

private fun DetachedSourceRootKind.scopeKind() = when (this) {
    DetachedSourceRootKind.PRODUCTION,
    DetachedSourceRootKind.RESOURCE,
    -> WorkspaceSourceRootKind.PRODUCTION
    DetachedSourceRootKind.TEST,
    DetachedSourceRootKind.TEST_RESOURCE,
    -> WorkspaceSourceRootKind.TEST
}

private fun DetachedSourceRootProvenance.scopeProvenance() = when (this) {
    DetachedSourceRootProvenance.AUTHORED -> WorkspaceSourceRootProvenance.AUTHORED
    DetachedSourceRootProvenance.GENERATED -> WorkspaceSourceRootProvenance.GENERATED
}

private fun rejected(failure: HostedWorkspaceAdmissionFailure) =
    HostedWorkspaceAdmissionResult.Rejected(failure)

private fun rejectedModel(failure: HostedWorkspaceAdmissionFailure) =
    HostedWorkspaceModelAdmissionResult.Rejected(failure)
