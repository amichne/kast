package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.jetbrains.plugins.gradle.model.ExternalProject

/** Cached Gradle project ownership, before any IDE source/resource folder can be observed. */
internal class GradleBuildProjectIndex
private constructor(
    val root: CanonicalWorkspaceRoot,
    val selected: GradleBuildIdentity,
    private val projects: Map<GradleProjectDirectory, List<CachedGradleProject>>,
    private val limits: ReadLimits,
) {
    fun owner(rawProjectPath: String?): Refinement<CachedGradleProject, GradleModuleOwnershipFailure> {
        val directory =
            when (val parsed = GradleProjectDirectory.observe(rawProjectPath, limits)) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        val owner =
            projects[directory]?.singleOrNull()
                ?: return Refinement.Rejected(GradleModuleOwnershipFailure.CACHED_PROJECT_UNAVAILABLE)
        return Refinement.Refined(owner)
    }

    companion object {
        fun capture(
            root: CanonicalWorkspaceRoot,
            imported: ExternalProject,
            observation: IntellijReadObservation = IntellijReadObservation.None,
            limits: ReadLimits = ReadLimits.Default,
        ): Refinement<GradleBuildProjectIndex, NamedGradleSourceScopeFailure> {
            val selected = GradleBuildIdentity.selected(root)
            val indexed = LinkedHashMap<GradleProjectDirectory, MutableList<CachedGradleProject>>()
            val pending = ArrayDeque<PendingGradleProject>().apply { add(PendingGradleProject(imported, selected)) }
            var count = 0
            while (pending.isNotEmpty()) {
                ProgressManager.checkCanceled()
                if (++count > limits[ReadLimitParameter.MODEL_MODULES].value) {
                    observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
                    return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                }
                val next = pending.removeFirst()
                val project = next.project
                val owner =
                    when (val admitted = cachedOwner(next, limits)) {
                        is Refinement.Rejected -> return admitted
                        is Refinement.Refined -> admitted.value
                    }
                if (project === imported && (project.path != ":" || owner.build != selected)) {
                    return Refinement.Rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                }
                indexed.getOrPut(owner.directory) { mutableListOf() }.add(owner)
                observation.count(IntellijReadCounter.IMPORTED_PROJECTS)
                if (
                    count + pending.size + project.childProjects.size > limits[ReadLimitParameter.MODEL_MODULES].value
                ) {
                    observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
                    return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                }
                pending.addAll(project.childProjects.values.map { PendingGradleProject(it, owner.build) })
            }
            return Refinement.Refined(
                GradleBuildProjectIndex(
                    root = root,
                    selected = selected,
                    projects = indexed.mapValues { it.value.toList() },
                    limits = limits,
                )
            )
        }

        private fun cachedOwner(
            next: PendingGradleProject,
            limits: ReadLimits,
        ): Refinement<CachedGradleProject, NamedGradleSourceScopeFailure> {
            val project = next.project
            val directory =
                when (val parsed = GradleProjectDirectory.observe(project.projectDir.path, limits)) {
                    is Refinement.Rejected ->
                        return Refinement.Rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                    is Refinement.Refined -> parsed.value
                }
            // ExternalProject.path is relative to its own Gradle build: ':' starts a new authority.
            // IntelliJ may attach included root projects below the outer import root in this cached tree.
            val build =
                if (project.path == ":") {
                    when (val parsed = GradleBuildIdentity.observe(project.projectDir.path, limits)) {
                        is Refinement.Rejected ->
                            return Refinement.Rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                        is Refinement.Refined -> parsed.value
                    }
                } else next.build
            return Refinement.Refined(CachedGradleProject(project, directory, build))
        }
    }
}

internal data class CachedGradleProject(
    val project: ExternalProject,
    val directory: GradleProjectDirectory,
    val build: GradleBuildIdentity,
)

private data class PendingGradleProject(val project: ExternalProject, val build: GradleBuildIdentity)

/** One Gradle project directory; never interchangeable with a Gradle build root. */
internal class GradleProjectDirectory private constructor(private val path: Path) {
    override fun equals(other: Any?): Boolean = other is GradleProjectDirectory && path == other.path

    override fun hashCode(): Int = path.hashCode()

    companion object {
        fun observe(
            raw: String?,
            limits: ReadLimits,
        ): Refinement<GradleProjectDirectory, GradleModuleOwnershipFailure> {
            if (raw.isNullOrBlank()) return Refinement.Rejected(GradleModuleOwnershipFailure.PROJECT_PATH_UNAVAILABLE)
            if (raw.length > limits[ReadLimitParameter.EPOCH_PATH_CHARACTERS].value)
                return Refinement.Rejected(GradleModuleOwnershipFailure.PROJECT_PATH_MALFORMED)
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return Refinement.Rejected(GradleModuleOwnershipFailure.PROJECT_PATH_MALFORMED)
                }
            if (!path.isAbsolute) return Refinement.Rejected(GradleModuleOwnershipFailure.PROJECT_PATH_MALFORMED)
            return Refinement.Refined(GradleProjectDirectory(path.normalize()))
        }
    }
}
