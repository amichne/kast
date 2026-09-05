package io.github.amichne.kast.workspace.intellij.provenance

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.File
import java.io.Serializable
import java.nio.file.Path

/** Typed Gradle Tooling model carrying complete source-root producer evidence for one project. */
interface GradleSourceRootProducerModel {
    val entries: List<GradleSourceRootProducerModelEntry>
}

/** One exact source directory classified by Gradle task-output ownership. */
interface GradleSourceRootProducerModelEntry {
    val projectDirectory: File
    val projectPath: String
    val sourceSetName: String
    val sourceRoot: File
    val role: GradleSourceRootProducerRole
    val provenance: GradleSourceRootProducerProvenance
}

/** Immutable implementation returned across the Gradle Tooling API boundary. */
data class DefaultGradleSourceRootProducerModel(
    override val entries: List<GradleSourceRootProducerModelEntry>,
) : GradleSourceRootProducerModel, Serializable

/** Immutable producer entry returned across the Gradle Tooling API boundary. */
data class DefaultGradleSourceRootProducerModelEntry(
    override val projectDirectory: File,
    override val projectPath: String,
    override val sourceSetName: String,
    override val sourceRoot: File,
    override val role: GradleSourceRootProducerRole,
    override val provenance: GradleSourceRootProducerProvenance,
) : GradleSourceRootProducerModelEntry, Serializable

/**
 * Gradle-side builder for exact source-directory producer evidence.
 *
 * Source roots, roles, and producer dependencies come from [SourceSetContainer]. A root is
 * generated only when it is owned by an output of a task that Gradle records as a dependency of
 * the source-directory collection. Resource membership is retained as a closed role so it cannot
 * authorize a code root. The builder never scans the project's task container and never inspects
 * a path segment or directory name.
 */
class GradleSourceRootProducerModelBuilder : ModelBuilderService {
    override fun canBuild(modelName: String): Boolean =
        modelName == GradleSourceRootProducerModel::class.java.name

    /**
     * Proof transition: `(String, Project) -> GradleSourceRootProducerModel`.
     *
     * Establishes an exact absolute normalized project directory and source roots with Gradle
     * project, source-set, code/resource role, and producer-backed generated classification. The
     * output retains the owner required for exact installed lookup. Gradle-owned [File] values are
     * normalized at this outer boundary; raw source sets, producer tasks, outputs, and paths do
     * not leave it.
     */
    override fun buildAll(
        _modelName: String,
        project: Project,
    ): GradleSourceRootProducerModel {
        val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
                         ?: return DefaultGradleSourceRootProducerModel(emptyList())
        val entries = mutableListOf<DefaultGradleSourceRootProducerModelEntry>()
        val projectDirectory = project.projectDir.toPath().toAbsolutePath().normalize()
        for (sourceSet in sourceSets) {
            val sourceDirectories = sourceSet.allSource.sourceDirectories
            val resourceRoots = sourceSet.resources.sourceDirectories.files
                .asSequence()
                .map { resource -> resource.toPath().toAbsolutePath().normalize() }
                .toSet()
            val producerOutputRoots = sourceDirectories.buildDependencies
                .getDependencies(null)
                .asSequence()
                .flatMap { producer -> producer.outputs.files.files.asSequence() }
                .map { output -> output.toPath().toAbsolutePath().normalize() }
                .distinct()
                .toList()
            for (sourceDirectory in sourceDirectories.files) {
                val sourceRoot = sourceDirectory.toPath().toAbsolutePath().normalize()
                entries += DefaultGradleSourceRootProducerModelEntry(
                    projectDirectory = projectDirectory.toFile(),
                    projectPath = project.path,
                    sourceSetName = sourceSet.name,
                    sourceRoot = sourceRoot.toFile(),
                    role = if (sourceRoot in resourceRoots) {
                        GradleSourceRootProducerRole.RESOURCE
                    } else {
                        GradleSourceRootProducerRole.CODE
                    },
                    provenance = if (
                        producerOutputRoots.any { output ->
                            sourceRoot == output || sourceRoot.startsWith(output)
                        }
                    ) {
                        GradleSourceRootProducerProvenance.GENERATED
                    } else {
                        GradleSourceRootProducerProvenance.AUTHORED
                    },
                )
            }
        }
        return DefaultGradleSourceRootProducerModel(
            entries.distinct().sortedWith(
                compareBy(
                    { it.projectDirectory.path },
                    { it.projectPath },
                    { it.sourceSetName },
                    { it.sourceRoot.path },
                    { it.role.name },
                    { it.provenance.name },
                ),
            ),
        )
    }
}
