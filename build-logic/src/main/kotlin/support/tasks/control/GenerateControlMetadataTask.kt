package support.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

@CacheableTask
abstract class GenerateControlMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostedContractFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationRegistryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configurationCatalogueFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val providerCatalogueFile: RegularFileProperty

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
        val hostedContract = controlMetadataJson.decodeFromString(
            HostedContractDocument.serializer(), hostedContractFile.get().asFile.readText(),
        )
        output.resolve("ide-host.json").writeText(controlMetadataJson.encodeToString(
            ControlMetadataDocument.serializer(), ControlMetadataDocument(
                schemaVersion = 2, productVersion = productVersion.get(), execution = "existing_ide",
                ideaBuild = ideaBuild.get(), kotlinPluginBuild = kotlinPluginBuild.get(),
                requiredHostedContract = hostedContract,
            ),
        ))
        operationRegistryFile.get().asFile.copyTo(output.resolve("operation-registry.json"))
        configurationCatalogueFile.get().asFile.copyTo(output.resolve("configuration-schema.json"))
        providerCatalogueFile.get().asFile.copyTo(output.resolve("provider-catalog.json"))
        output.resolve("wire-schema.json").writeBytes(CanonicalWireSchema.encodedBytes())
        licenseFile.get().asFile.copyTo(output.resolve("licenses/LICENSE"))
    }
}

@Serializable
internal data class ControlMetadataDocument(
    val schemaVersion: Int, val productVersion: String, val execution: String, val ideaBuild: String,
    val kotlinPluginBuild: String, val requiredHostedContract: HostedContractDocument,
)

@Serializable
internal enum class HostedContractType { HOSTED_CONTRACT }

@Serializable
internal data class HostedContractDocument(
    val type: HostedContractType,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
) {
    init {
        require(runtimeProtocolIdentity.isNotBlank())
        require(operationRegistryDigest.matches(Regex("sha256:[0-9a-f]{64}")))
        require(wireSchemaDigest.matches(Regex("sha256:[0-9a-f]{64}")))
        require(capabilities.isNotEmpty() && capabilities.all(String::isNotBlank))
        require(capabilities.distinct().size == capabilities.size)
    }
}

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
