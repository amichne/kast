package support.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates the installed metadata for the IDE-hosted control product. */
@CacheableTask
abstract class GenerateHostedControlMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationRegistryFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Proof transition: `License + OperationRegistry -> HostedControlMetadataDirectory`.
     *
     * Establishes the exact installed registry, generated wire schema, and license without a
     * semantic-runtime manifest or archive authority. Raw filesystem paths remain confined to
     * this build-output boundary.
     */
    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("licenses").mkdirs()
        operationRegistryFile.get().asFile.copyTo(output.resolve("operation-registry.json"))
        output.resolve("wire-schema.json").writeBytes(CanonicalWireSchema.encodedBytes())
        licenseFile.get().asFile.copyTo(output.resolve("licenses/LICENSE"))
    }
}
