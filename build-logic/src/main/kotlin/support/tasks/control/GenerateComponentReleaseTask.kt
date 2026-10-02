package support.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** Component release metadata never admits or records the other component's artifact. */
@CacheableTask
abstract class GenerateComponentReleaseTask : DefaultTask() {
    @get:Input abstract val component: Property<ReleaseComponent>
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val sourceRevision: Property<String>
    @get:Input @get:Optional abstract val ideaReleaseLine: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val hostedContractFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val companionArtifacts: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        require(releaseVersion.get().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(sourceRevision.get().matches(Regex("[0-9a-f]{40}")))
        val contract = controlMetadataJson.decodeFromString(
            HostedContractDocument.serializer(), hostedContractFile.get().asFile.readText(),
        )
        val primary = archive.get().asFile
        require(primary.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]+")) && primary.name.length <= 255)
        require(primary.length() in 1..1_073_741_824L)
        val artifact = ReleaseArtifactDocument(primary.name, checksum(primary), primary.length())
        val release = when (component.get()) {
            ReleaseComponent.CONTROL -> ComponentReleaseDocument.ControlRelease(
                ControlVersion.parse(releaseVersion.get()), artifact, contract, sourceRevision.get(),
            )
            ReleaseComponent.HOST -> {
                require(ideaReleaseLine.get().matches(Regex("[0-9]{3}")))
                ComponentReleaseDocument.HostRelease(
                    HostedPluginVersion.parse(releaseVersion.get()), artifact, contract, ideaReleaseLine.get(), sourceRevision.get(),
                )
            }
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val record = output.resolve("${component.get().recordName}-v${releaseVersion.get()}.json")
        record.writeText(controlMetadataJson.encodeToString(ComponentReleaseDocument.serializer(), release))
        (listOf(primary, record) + companionArtifacts.files).forEach { asset ->
            output.resolve("${asset.name}.sha256").writeText("${checksum(asset).removePrefix("sha256:")}  ${asset.name}\n")
        }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest())
    }
}

enum class ReleaseComponent(val recordName: String) {
    CONTROL("kast-control-release"), HOST("kast-host-release"),
}

@Serializable
internal data class ReleaseArtifactDocument(val fileName: String, val sha256: String, val bytes: Long)

@Serializable
internal sealed interface ComponentReleaseDocument {
    @Serializable
    @SerialName("CONTROL_RELEASE")
    data class ControlRelease(
        val controlVersion: ControlVersion,
        val artifact: ReleaseArtifactDocument,
        val requiredHostedContract: HostedContractDocument,
        val sourceRevision: String,
    ) : ComponentReleaseDocument

    @Serializable
    @SerialName("HOST_RELEASE")
    data class HostRelease(
        val hostedPluginVersion: HostedPluginVersion,
        val artifact: ReleaseArtifactDocument,
        val providedHostedContract: HostedContractDocument,
        val supportedIntellijReleaseLine: String,
        val sourceRevision: String,
    ) : ComponentReleaseDocument
}

@Serializable
@JvmInline
internal value class ControlVersion private constructor(val value: String) {
    companion object {
        fun parse(value: String): ControlVersion {
            require(value.length <= 64 && value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            return ControlVersion(value)
        }
    }
}

@Serializable
@JvmInline
internal value class HostedPluginVersion private constructor(val value: String) {
    companion object {
        fun parse(value: String): HostedPluginVersion {
            require(value.length <= 64 && value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            return HostedPluginVersion(value)
        }
    }
}
