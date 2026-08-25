package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path

@CacheableTask
abstract class GenerateDeliveryProjectionsTask : DefaultTask() {
    @get:Input
    abstract val artifactContents: MapProperty<String, String>

    @get:OutputFiles
    abstract val artifactFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val generation = refineTaskGeneration(artifactContents.get())
        requireDeclaredFiles(artifactFiles, repositoryRoot.get().asFile.toPath(), generation)
        ProjectionArtifactId.entries.forEach { id ->
            writeTextAtomically(
                repositoryRoot.file(id.repositoryPath).get().asFile.toPath(),
                generation.content(id),
            )
        }
    }
}

@CacheableTask
abstract class VerifyDeliveryProjectionsTask : DefaultTask() {
    @get:Input
    abstract val firstGenerationContents: MapProperty<String, String>

    @get:Input
    abstract val secondGenerationContents: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verifyProjections() {
        val first = refineTaskGeneration(firstGenerationContents.get())
        val second = refineTaskGeneration(secondGenerationContents.get())
        val admitted = when (val result = admitDeterministicProgramProjection(first, second)) {
            is DeliveryProjectionAdmission.Admitted -> result.projection
            is DeliveryProjectionAdmission.Rejected -> throw GradleException(
                "delivery projection rejected: ${result.failure}",
            )
        }
        requireDeclaredFiles(
            artifactFiles,
            repositoryRoot.get().asFile.toPath(),
            admitted.generation,
        )
        ProjectionArtifactId.entries.forEach { id ->
            val observed = repositoryRoot.file(id.repositoryPath).get().asFile.readText()
            if (observed != admitted.generation.content(id)) {
                throw GradleException("checked-in ${id.repositoryPath} differs from Kotlin authority")
            }
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            encodeProjectionProof(
                DeliveryProjectionProofDocument(
                    artifactDigests = admitted.artifactDigests.entries.associate {
                        it.key.repositoryPath to it.value.value
                    },
                    byteIdentical = true,
                    generationCount = 2,
                    outcome = DeliveryProjectionOutcome.COMPLETE,
                    schemaValidArtifactCount = ProjectionArtifactId.entries.size,
                    schemaVersion = 1,
                    taskId = "KVP-005",
                ),
            ),
        )
    }
}

private fun DefaultTask.refineTaskGeneration(contents: Map<String, String>): ProjectionGeneration {
    val refined = refineProjectionGeneration(contents)
    return when (refined) {
        is ProjectionGenerationRefinement.Refined -> refined.generation
        is ProjectionGenerationRefinement.Rejected -> throw GradleException(
            "projection artifact set rejected: ${refined.failure}",
        )
    }
}

private fun requireDeclaredFiles(
    files: ConfigurableFileCollection,
    repositoryRoot: Path,
    generation: ProjectionGeneration,
) {
    val root = repositoryRoot.toAbsolutePath().normalize()
    val observed = files.files.mapTo(mutableSetOf()) { it.toPath().relativeToUnix(root) }
    val expected = ProjectionArtifactId.entries.mapTo(mutableSetOf()) { it.repositoryPath }
    if (observed != expected || generation.orderedContents().size != expected.size) {
        throw GradleException("projection output files do not match the five declared artifacts")
    }
}

private fun Path.relativeToUnix(root: Path): String =
    root.relativize(toAbsolutePath().normalize()).joinToString("/") { it.toString() }
