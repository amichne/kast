package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import java.nio.file.Path

internal object IdeaGradleWorkspaceModelIdentityResolver {
    fun resolve(model: IdeaGradleProjectLoadBridge.GradleWorkspaceModel): String = FileHashing.sha256(
        buildString {
            append("complete=").append(model.importedModelComplete()).append('\n')
            records("linked-build", model.linkedBuildRoots().map(::stablePath))
            records(
                "imported-module",
                model.importedModuleIdentities().map { identity ->
                    "${stablePath(identity.externalProjectPath())}|${identity.externalModuleId()}"
                },
            )
            records(
                "loaded-module",
                model.loadedModules().map { loaded ->
                    val identity = loaded.identity()
                    "${loaded.ideaModuleName()}|${stablePath(identity.externalProjectPath())}|" +
                        identity.externalModuleId()
                },
            )
            records("imported-source-root", model.importedSourceRoots().map(::stablePath))
            model.moduleAssociations()
                .sortedWith(
                    compareBy(
                        { association -> association.ideaModuleName() },
                        { association -> stablePath(association.linkedBuildRoot()) },
                        { association -> association.gradleProjectPath() },
                    ),
                ).forEach { association ->
                    append("association=")
                        .append(association.ideaModuleName()).append('|')
                        .append(stablePath(association.linkedBuildRoot())).append('|')
                        .append(stablePath(association.gradleProjectDirectory())).append('|')
                        .append(association.gradleProjectPath()).append('|')
                        .append(association.rootModule()).append('|')
                        .append(association.includedBuild()).append('\n')
                    association.sourceSets()
                        .sortedBy { sourceSet -> sourceSet.sourceSetName() }
                        .forEach { sourceSet ->
                            append("source-set=")
                                .append(sourceSet.sourceSetName()).append('|')
                                .append(sourceSet.sourceRoots().map(::stablePath).sorted().joinToString(","))
                                .append('\n')
                        }
                }
        },
    )

    private fun StringBuilder.records(label: String, values: Collection<String>) {
        values.sorted().forEach { value -> append(label).append('=').append(value).append('\n') }
    }

    private fun stablePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')
}
