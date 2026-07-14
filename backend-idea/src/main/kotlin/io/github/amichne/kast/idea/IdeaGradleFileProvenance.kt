package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import java.nio.file.Path

internal class IdeaGradleFileProvenance private constructor(
    private val modules: List<IdeaGradleModuleProvenance>,
) {
    fun applyTo(update: FileIndexUpdate): FileIndexUpdate {
        val file = Path.of(update.path).toAbsolutePath().normalize()
        val projects = modules
            .filter { module -> file.startsWith(module.projectDirectory) }
            .mapTo(linkedSetOf()) { module -> module.project }
        val sourceSets = modules
            .asSequence()
            .filter { module -> module.project in projects }
            .flatMap { module ->
                module.sourceSets.asSequence()
                    .filter { sourceSet -> sourceSet.sourceRoots.any(file::startsWith) }
                    .map { sourceSet -> BuildQualifiedGradleSourceSetIdentity(module.project, sourceSet.name) }
            }.toCollection(linkedSetOf())
        return update.copy(
            gradleProjects = projects,
            gradleSourceSets = sourceSets,
        )
    }

    companion object {
        fun fromProject(
            project: Project,
            workspaceIdentity: IdeaWorkspaceIdentity,
        ): IdeaGradleFileProvenance {
            val workspace = workspaceIdentity.workspaceIdentity
            val model = IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
            val modules = model.moduleAssociations().mapNotNull { association ->
                val linkedRoot = association.linkedBuildRoot().toAbsolutePath().normalize()
                val relativeRoot = workspace.relativizeIfContained(linkedRoot) ?: return@mapNotNull null
                val buildRoot = WorkspaceRelativeGradleBuildRoot.parse(
                    relativeRoot.toString().ifEmpty { "." },
                )
                val projectIdentity = runCatching {
                    BuildQualifiedGradleProjectIdentity(
                        buildRoot = buildRoot,
                        projectPath = GradleProjectPath.parse(association.gradleProjectPath()),
                    )
                }.getOrNull() ?: return@mapNotNull null
                IdeaGradleModuleProvenance(
                    project = projectIdentity,
                    projectDirectory = association.gradleProjectDirectory().toAbsolutePath().normalize(),
                    sourceSets = association.sourceSets().mapNotNull { sourceSet ->
                        val roots = sourceSet.sourceRoots()
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .filter(workspace::contains)
                            .toSet()
                        if (roots.isEmpty()) return@mapNotNull null
                        runCatching {
                            IdeaGradleSourceSetProvenance(
                                name = GradleSourceSetName.parse(sourceSet.sourceSetName()),
                                sourceRoots = roots,
                            )
                        }.getOrNull()
                    }.toSet(),
                )
            }
            return create(modules)
        }

        fun create(modules: Collection<IdeaGradleModuleProvenance>): IdeaGradleFileProvenance =
            IdeaGradleFileProvenance(
                modules = modules
                    .distinct()
                    .sortedWith(
                        compareBy(
                            { module -> module.project.buildRoot.value },
                            { module -> module.project.projectPath.value },
                            { module -> module.projectDirectory.toString() },
                        ),
                    ),
            )
    }
}
