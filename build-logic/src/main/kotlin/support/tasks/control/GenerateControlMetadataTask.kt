package support.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

@CacheableTask
abstract class GenerateControlMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationRegistryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configurationCatalogueFile: RegularFileProperty

    @get:Input
    abstract val productVersion: Property<String>
    @get:Input
    abstract val ideaBuild: Property<String>
    @get:Input
    abstract val kotlinPluginBuild: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("licenses").mkdirs()
        val archive = pluginArchive.get().asFile
        output.resolve("ide-host.json").writeText(controlMetadataJson.encodeToString(
            HostedPluginDocument.serializer(), HostedPluginDocument(
                schemaVersion = 1, productVersion = productVersion.get(), execution = "existing_ide",
                ideaBuild = ideaBuild.get(), kotlinPluginBuild = kotlinPluginBuild.get(),
                fileName = archive.name, sha256 = sha256(archive.readBytes()), bytes = archive.length(),
            ),
        ))
        operationRegistryFile.get().asFile.copyTo(output.resolve("operation-registry.json"))
        configurationCatalogueFile.get().asFile.copyTo(output.resolve("configuration-schema.json"))
        output.resolve("wire-schema.json").writeBytes(CanonicalWireSchema.encodedBytes())
        licenseFile.get().asFile.copyTo(output.resolve("licenses/LICENSE"))
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

}

@Serializable
internal data class HostedPluginDocument(
    val schemaVersion: Int, val productVersion: String, val execution: String, val ideaBuild: String,
    val kotlinPluginBuild: String, val fileName: String, val sha256: String, val bytes: Long,
)

@Serializable
internal data class WireSchemaDocument(
    val schemaVersion: Int,
    val wireSchemaId: String,
)

internal val controlMetadataJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

/** The sole admitted wire schema and its generated-serializer byte projection. */
internal object CanonicalWireSchema {
    const val identity = "kast-wire-v1"

    /**
     * Proof transition: `CanonicalWireSchema -> ByteArray` at the wire-schema boundary.
     *
     * Projects the sole supported schema through its dedicated generated serializer. Each call
     * returns fresh bytes for build-report or control-metadata adapters only.
     */
    fun encodedBytes(): ByteArray = controlMetadataJson.encodeToString(
        WireSchemaDocument.serializer(),
        WireSchemaDocument(schemaVersion = 1, wireSchemaId = identity),
    ).toByteArray(StandardCharsets.UTF_8)
}
