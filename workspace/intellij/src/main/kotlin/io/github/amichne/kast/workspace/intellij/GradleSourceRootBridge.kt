package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.IExternalSystemSourceType
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.ProvenanceFailure
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootAdmissionFailure
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed result of converting one live Gradle model root to detached source-root proof. */
sealed interface GradleSourceRootAdmission {
    data class Admitted(
        val root: SourceRoot,
    ) : GradleSourceRootAdmission

    data class Rejected(
        val failures: Set<SourceRootAdmissionFailure>,
    ) : GradleSourceRootAdmission
}

/** Converts IntelliJ Gradle model entries to host-neutral source-root proof. */
object GradleSourceRootBridge {
    /**
     * Proof transition: `(CanonicalWorkspaceRoot, GradleSourceSetData,
     * ContentRootData.SourceRoot, IExternalSystemSourceType) -> GradleSourceRootAdmission`.
     *
     * Establishes detached exact source-set ownership and Authored, Generated, or qualified
     * Unknown provenance directly from Gradle model flags. [SourceRootAdmissionFailure] is the
     * closed expected failure. Live model objects and raw source paths are extracted only inside
     * this IntelliJ bridge and never retained by the returned [SourceRoot].
     */
    fun admit(
        workspaceRoot: CanonicalWorkspaceRoot,
        sourceSet: GradleSourceSetData,
        sourceRoot: ContentRootData.SourceRoot,
        sourceType: IExternalSystemSourceType,
    ): GradleSourceRootAdmission {
        val workspacePath = Path.of(workspaceRoot.value)
        val buildRoot = when (
            val refinement = refineModelPath(
                workspacePath,
                sourceSet.linkedExternalProjectPath,
                SourceRootAdmissionFailure.InvalidLinkedBuildRoot,
                SourceRootAdmissionFailure.LinkedBuildRootOutsideWorkspace,
            )
        ) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> return GradleSourceRootAdmission.Rejected(
                setOf(refinement.failure),
            )
        }
        val location = when (
            val refinement = refineModelPath(
                workspacePath,
                sourceRoot.path,
                SourceRootAdmissionFailure.InvalidSourceRoot,
                SourceRootAdmissionFailure.SourceRootOutsideWorkspace,
            )
        ) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> return GradleSourceRootAdmission.Rejected(
                setOf(refinement.failure),
            )
        }
        val sourceSetSeparator = sourceSet.externalName.lastIndexOf(':')
        val projectPath = sourceSet.externalName
            .take(sourceSetSeparator.coerceAtLeast(0))
            .ifEmpty { ":" }
        val sourceSetName = sourceSet.externalName.drop(sourceSetSeparator + 1)
        val provenance = when {
            sourceType.isExcluded -> SourceRootProvenance.Unknown(
                ProvenanceFailure.ExcludedFromSourceModel,
            )
            sourceType.isGenerated -> SourceRootProvenance.Generated
            else -> SourceRootProvenance.Authored
        }
        return when (
            val admission = SourceRoot.admit(
                GradleSourceRootEvidence(
                    ideaModuleName = sourceSet.internalName,
                    workspaceRelativeBuildRoot = buildRoot,
                    gradleProjectPath = projectPath,
                    sourceSetName = sourceSetName,
                    workspaceRelativeSourceRoot = location,
                    provenance = provenance,
                ),
            )
        ) {
            is Refinement.Refined -> GradleSourceRootAdmission.Admitted(admission.value)
            is Refinement.Rejected -> GradleSourceRootAdmission.Rejected(admission.failure)
        }
    }

    /**
     * Proof transition: `(Path, String) -> Refinement<String, SourceRootAdmissionFailure>`.
     *
     * Establishes an absolute normalized model path inside the canonical workspace, represented
     * as a detached workspace-relative string. [SourceRootAdmissionFailure] is the closed expected
     * failure. Raw [Path] values are extracted only inside [admit].
     */
    private fun refineModelPath(
        workspaceRoot: Path,
        raw: String,
        invalid: SourceRootAdmissionFailure,
        outside: SourceRootAdmissionFailure,
    ): Refinement<String, SourceRootAdmissionFailure> {
        val path = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            return Refinement.Rejected(invalid)
        }
        if (!path.isAbsolute || path.normalize() != path) return Refinement.Rejected(invalid)
        if (!path.startsWith(workspaceRoot)) return Refinement.Rejected(outside)
        val relative = workspaceRoot.relativize(path)
            .joinToString("/") { it.toString() }
            .ifEmpty { "." }
        return Refinement.Refined(relative)
    }
}
