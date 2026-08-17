package support.tasks

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
import java.nio.charset.StandardCharsets
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

    @get:Input abstract val productVersion: Property<String>
    @get:Input abstract val ideaBuild: Property<String>
    @get:Input abstract val kotlinPluginBuild: Property<String>
    @get:Input abstract val runtimeBaseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("licenses").mkdirs()
        val archive = runtimeArchive.get().asFile
        val archiveDigest = sha256(archive.readBytes())
        val pluginJar = runtimeDirectory.get().asFile
            .resolve("idea-home/plugins/kast-indexer/lib")
            .listFiles()
            ?.singleOrNull { it.name.startsWith("indexer-") && it.name.endsWith("-plugin.jar") }
            ?: error("semantic runtime has no exact private Kast plugin jar")
        val pluginDigest = sha256(pluginJar.readBytes())
        val wireSchemaId = "kast-wire-v1"
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
        val manifest =
            "{\"schemaVersion\":1,\"runtimeId\":\"$runtimeId\",\"productVersion\":\"${productVersion.get()}\",\"platform\":\"macos\",\"architecture\":\"aarch64\",\"ideaBuild\":\"${ideaBuild.get()}\",\"kotlinPluginBuild\":\"${kotlinPluginBuild.get()}\",\"kastPluginSha256\":\"$pluginDigest\",\"wireSchemaId\":\"$wireSchemaId\",\"archive\":{\"fileName\":\"${archive.name}\",\"url\":\"$baseUrl/${archive.name}\",\"sha256\":\"$archiveDigest\",\"bytes\":${archive.length()}},\"layout\":{\"executable\":\"kast-indexer\",\"requiredEntries\":[\"kast-indexer\",\"runtime-libs/\",\"idea-home/product-info.json\",\"idea-home/plugins/kast-indexer/\"],\"executableEntries\":[\"kast-indexer\"]}}"
        output.resolve("semantic-runtime.json").writeText(manifest)
        output.resolve("operation-registry.json").writeText(
            "{\"schemaVersion\":1,\"operationIds\":[\"workspace.inspect\",\"symbol.discover\",\"symbol.resolve\",\"symbol.describe\",\"relation.read\",\"traversal.run\",\"diagnostic.check\",\"change.plan\",\"change.apply\",\"change.verify\",\"change.recover\"]}",
        )
        output.resolve("wire-schema.json").writeText(
            "{\"schemaVersion\":1,\"wireSchemaId\":\"$wireSchemaId\"}",
        )
        licenseFile.get().asFile.copyTo(output.resolve("licenses/LICENSE"))
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
}
