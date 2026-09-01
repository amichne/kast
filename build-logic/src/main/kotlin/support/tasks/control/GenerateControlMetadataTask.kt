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
    abstract val runtimeArchive: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationRegistryFile: RegularFileProperty

    @get:Input
    abstract val productVersion: Property<String>
    @get:Input
    abstract val ideaBuild: Property<String>
    @get:Input
    abstract val kotlinPluginBuild: Property<String>
    @get:Input
    abstract val runtimeBaseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("licenses").mkdirs()
        val archive = runtimeArchive.get().asFile
        val archiveDigest = sha256(archive.readBytes())
        val pluginDigest = privateExtensionSha256(
            runtimeDirectory.get().asFile.toPath().resolve("private-plugins/kast-indexer"),
        )
        val wireSchemaId = CanonicalWireSchema.identity
        val identityMaterial = listOf(
            "macos",
            "aarch64",
            ideaBuild.get(),
            kotlinPluginBuild.get(),
            pluginDigest,
            wireSchemaId,
            archiveDigest,
        ).joinToString("\n")
        val runtimeId = sha256(identityMaterial.toByteArray(StandardCharsets.UTF_8))
        val baseUrl = runtimeBaseUrl.get().trimEnd('/')
        val manifest = SemanticRuntimeDocument(
            schemaVersion = 1,
            runtimeId = runtimeId,
            productVersion = productVersion.get(),
            platform = "macos",
            architecture = "aarch64",
            ideaBuild = ideaBuild.get(),
            kotlinPluginBuild = kotlinPluginBuild.get(),
            kastPluginSha256 = pluginDigest,
            wireSchemaId = wireSchemaId,
            archive = SemanticRuntimeArchiveDocument(
                fileName = archive.name,
                url = "$baseUrl/${archive.name}",
                sha256 = archiveDigest,
                bytes = archive.length(),
            ),
            layout = SemanticRuntimeLayoutDocument(
                executable = "kast-indexer",
                requiredEntries = listOf(
                    "kast-indexer",
                    "runtime-libs/",
                    "private-plugins/kast-indexer/",
                ),
                executableEntries = listOf("kast-indexer"),
            ),
        )
        output.resolve("semantic-runtime.json").writeText(
            controlMetadataJson.encodeToString(SemanticRuntimeDocument.serializer(), manifest),
        )
        operationRegistryFile.get().asFile.copyTo(output.resolve("operation-registry.json"))
        output.resolve("wire-schema.json").writeBytes(CanonicalWireSchema.encodedBytes())
        licenseFile.get().asFile.copyTo(output.resolve("licenses/LICENSE"))
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

    private fun privateExtensionSha256(extensionRoot: Path): String {
        check(Files.isDirectory(extensionRoot)) {
            "semantic runtime has no private Kast extension directory"
        }
        val payloadFiles = Files.walk(extensionRoot).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .map { path ->
                    extensionRoot.relativize(path)
                        .map(Path::toString)
                        .joinToString("/") to path
                }
                .sortedBy(Pair<String, Path>::first)
                .toList()
        }
        check(payloadFiles.isNotEmpty()) {
            "semantic runtime has no private Kast extension files"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        payloadFiles.forEach { (relativePath, payloadFile) ->
            val relativePathBytes = relativePath.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relativePathBytes.size).array())
            digest.update(relativePathBytes)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(payloadFile)).array())
            Files.newInputStream(payloadFile).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest())
    }
}

@Serializable
internal data class SemanticRuntimeDocument(
    val schemaVersion: Int,
    val runtimeId: String,
    val productVersion: String,
    val platform: String,
    val architecture: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginSha256: String,
    val wireSchemaId: String,
    val archive: SemanticRuntimeArchiveDocument,
    val layout: SemanticRuntimeLayoutDocument,
)

@Serializable
internal data class SemanticRuntimeArchiveDocument(
    val fileName: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable
internal data class SemanticRuntimeLayoutDocument(
    val executable: String,
    val requiredEntries: List<String>,
    val executableEntries: List<String>,
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
