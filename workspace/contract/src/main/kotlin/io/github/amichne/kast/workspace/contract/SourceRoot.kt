package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed Gradle-model reasons that prevent authored/generated provenance proof. */
sealed interface ProvenanceFailure {
    data object ExcludedFromSourceModel : ProvenanceFailure
}

/** Provenance established directly by the imported Gradle source-root model. */
sealed interface SourceRootProvenance {
    data object Authored : SourceRootProvenance

    data object Generated : SourceRootProvenance

    data class Unknown(
        val reason: ProvenanceFailure,
    ) : SourceRootProvenance
}

/** Raw detached values extracted from one Gradle source-set model entry. */
data class GradleSourceRootEvidence(
    val ideaModuleName: String,
    val workspaceRelativeBuildRoot: String,
    val gradleProjectPath: String,
    val sourceSetName: String,
    val workspaceRelativeSourceRoot: String,
    val provenance: SourceRootProvenance,
)

/** Finite failures for admitting detached Gradle source-root evidence. */
enum class SourceRootAdmissionFailure {
    InvalidIdeaModuleName,
    InvalidLinkedBuildRoot,
    LinkedBuildRootOutsideWorkspace,
    InvalidGradleProjectPath,
    InvalidSourceSetName,
    InvalidSourceRoot,
    SourceRootOutsideWorkspace,
}

@JvmInline
value class WorkspaceRelativeSourceRoot internal constructor(
    val value: String,
)

@ConsistentCopyVisibility
data class GradleSourceSetOwner internal constructor(
    val module: WorkspaceModuleIdentity,
    val project: GradleProjectIdentity,
    val sourceSet: WorkspaceSourceSetName,
)

/** Detached source-root proof carrying exact Gradle source-set ownership and model provenance. */
@ConsistentCopyVisibility
data class SourceRoot internal constructor(
    val owner: GradleSourceSetOwner,
    val location: WorkspaceRelativeSourceRoot,
    val provenance: SourceRootProvenance,
) {
    companion object {
        /**
         * Proof transition: `GradleSourceRootEvidence ->
         * Refinement<SourceRoot, Set<SourceRootAdmissionFailure>>`.
         *
         * Establishes normalized workspace-contained root identity, exact Gradle project and
         * source-set ownership, and provenance already supplied by the Gradle model. The closed
         * expected failure is [SourceRootAdmissionFailure]. Raw model strings may enter only from
         * the IntelliJ Gradle bridge; raw location extraction is permitted only at a physical
         * source-access boundary.
         */
        fun admit(
            evidence: GradleSourceRootEvidence,
        ): Refinement<SourceRoot, Set<SourceRootAdmissionFailure>> {
            val failures = linkedSetOf<SourceRootAdmissionFailure>()
            val moduleName = evidence.ideaModuleName.trim()
            val projectPath = evidence.gradleProjectPath.trim()
            val sourceSetName = evidence.sourceSetName.trim()
            val linkedBuildRoot = admitRelativePath(
                evidence.workspaceRelativeBuildRoot,
                SourceRootAdmissionFailure.InvalidLinkedBuildRoot,
            )
            val sourceRoot = admitRelativePath(
                evidence.workspaceRelativeSourceRoot,
                SourceRootAdmissionFailure.InvalidSourceRoot,
            )

            if (moduleName.isEmpty()) {
                failures += SourceRootAdmissionFailure.InvalidIdeaModuleName
            }
            if (!GRADLE_PROJECT_PATH.matches(projectPath)) {
                failures += SourceRootAdmissionFailure.InvalidGradleProjectPath
            }
            if (sourceSetName.isEmpty()) {
                failures += SourceRootAdmissionFailure.InvalidSourceSetName
            }
            when (linkedBuildRoot) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> failures += linkedBuildRoot.failure
            }
            when (sourceRoot) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> failures += sourceRoot.failure
            }
            if (failures.isNotEmpty()) return Refinement.Rejected(failures)

            val admittedBuildRoot = (linkedBuildRoot as Refinement.Refined).value
            val admittedSourceRoot = (sourceRoot as Refinement.Refined).value
            return Refinement.Refined(
                SourceRoot(
                    owner = GradleSourceSetOwner(
                        module = WorkspaceModuleIdentity(moduleName),
                        project = GradleProjectIdentity(
                            buildRoot = WorkspaceRelativeGradleBuildRoot(admittedBuildRoot),
                            projectPath = GradleProjectPath(projectPath),
                        ),
                        sourceSet = WorkspaceSourceSetName(sourceSetName),
                    ),
                    location = WorkspaceRelativeSourceRoot(admittedSourceRoot),
                    provenance = evidence.provenance,
                ),
            )
        }

        /**
         * Proof transition: `String -> Refinement<String, SourceRootAdmissionFailure>`.
         *
         * Establishes a normalized workspace-relative path that cannot traverse its workspace.
         * [SourceRootAdmissionFailure] is the closed expected failure; the raw string may be
         * consumed only inside [admit].
         */
        private fun admitRelativePath(
            raw: String,
            failure: SourceRootAdmissionFailure,
        ): Refinement<String, SourceRootAdmissionFailure> {
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(failure)
            }
            val segments = raw.split('/')
            return if (
                raw == "." ||
                raw.isNotEmpty() &&
                !path.isAbsolute &&
                segments.none { it.isEmpty() || it == "." || it == ".." }
            ) {
                Refinement.Refined(raw)
            } else {
                Refinement.Rejected(failure)
            }
        }

        private val GRADLE_PROJECT_PATH = Regex(
            pattern = ":(?:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*)?",
        )
    }
}
