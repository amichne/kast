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
    val sourceRoot: File
    val provenance: GradleSourceRootProducerProvenance
}

/** Immutable implementation returned across the Gradle Tooling API boundary. */
data class DefaultGradleSourceRootProducerModel(
    override val entries: List<GradleSourceRootProducerModelEntry>,
) : GradleSourceRootProducerModel, Serializable

/** Immutable producer entry returned across the Gradle Tooling API boundary. */
data class DefaultGradleSourceRootProducerModelEntry(
    override val sourceRoot: File,
    override val provenance: GradleSourceRootProducerProvenance,
) : GradleSourceRootProducerModelEntry, Serializable

/**
 * Gradle-side builder for exact source-directory producer evidence.
 *
 * Source roots and their producer dependencies come from [SourceSetContainer]. A root is
 * generated only when it is owned by an output of a task that Gradle records as a dependency of
 * the source-directory collection. The builder never scans the project's task container and
 * never inspects a path segment or directory name.
 */
class GradleSourceRootProducerModelBuilder : ModelBuilderService {
    override fun canBuild(modelName: String): Boolean =
        modelName == GradleSourceRootProducerModel::class.java.name

    /**
     * Proof transition: `(String, Project) -> GradleSourceRootProducerModel`.
     *
     * Establishes an exact absolute normalized source-root list whose generated classification is
     * backed by the source-directory collection's Gradle task dependencies and those producers'
     * declared outputs. Gradle-owned [File] values are normalized at this outer boundary; raw
     * source sets, producer tasks, outputs, and paths do not leave it.
     */
    override fun buildAll(
        _modelName: String,
        project: Project,
    ): GradleSourceRootProducerModel {
        val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
                         ?: return DefaultGradleSourceRootProducerModel(emptyList())
        val entries = mutableListOf<DefaultGradleSourceRootProducerModelEntry>()
        for (sourceSet in sourceSets) {
            val sourceDirectories = sourceSet.allSource.sourceDirectories
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
                    sourceRoot = sourceRoot.toFile(),
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
                    { it.sourceRoot.path },
                    { it.provenance.name },
                ),
            ),
        )
    }
}
