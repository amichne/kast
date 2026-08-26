package support.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Re-derives fixed standalone-plugin rejection cases")
abstract class StandalonePluginNegativeProofTask : DefaultTask() {
    @TaskAction
    fun verify() {
        val descriptor = PluginDescriptorObservation.Present(
            KastStandalonePlugin.id.value,
            RegistrationObservation.PRESENT,
            RegistrationObservation.PRESENT,
        )
        val cases = listOf(
            emptyList<PluginPayloadObservation>() to StandalonePluginFailure.MISSING_PAYLOAD,
            listOf(PluginPayloadObservation("idea-home/lib/payload.jar", emptySet(), descriptor)) to
                StandalonePluginFailure.PRIVATE_IDEA_HOME_LAYOUT,
            listOf(
                PluginPayloadObservation(
                    "${KastStandalonePlugin.root}/lib/platform.jar",
                    setOf("com/intellij/idea/Main.class"),
                    descriptor,
                ),
            ) to StandalonePluginFailure.PLATFORM_CLASS_PRESENT,
        )
        cases.forEach { (input, expected) ->
            val rejected = KastStandalonePlugin.admit(input)
            if (rejected != StandalonePluginPayloadResult.Rejected(expected)) {
                throw GradleException("KVP-010 negative case $expected was not rejected: $rejected")
            }
        }
        logger.lifecycle("KVP-010 rejected all {} standalone-plugin negative cases", cases.size)
    }
}

@CacheableTask
abstract class BuildStandalonePluginTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val payloadJars: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val pluginArchive: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun build() {
        val files = payloadJars.files.sortedBy { it.name }
        val observations = when (val result = observePayload(files.map { it.toPath() })) {
            is PayloadObservationResult.Complete -> result.observations
            is PayloadObservationResult.Rejected -> fail(result.failure)
        }
        val payload = when (val result = KastStandalonePlugin.admit(observations)) {
            is StandalonePluginPayloadResult.Complete -> result.payload
            is StandalonePluginPayloadResult.Rejected -> fail(result.failure)
        }
        val filesByEntry = files.associateBy { "${KastStandalonePlugin.root}/lib/${it.name}" }
        val admittedFiles = payload.jars.map { entry ->
            AdmittedPluginArchiveFile(entry, filesByEntry.getValue(entry.value).toPath())
        }
        val archive = pluginArchive.get().asFile.toPath()
        writeArchiveAtomically(archive, admittedFiles)
        val payloadDocuments = admittedFiles.map { file ->
            PayloadJarDocument(
                file.entry,
                sha256(Files.readAllBytes(file.source)),
                ArtifactSizeBytes(Files.size(file.source)),
            )
        }
        val root = repositoryRoot.get().asFile.toPath()
        val artifactPath = when (val result = admitRepositoryRelativeArtifactPath(root, archive)) {
            is RepositoryRelativeArtifactPathResult.Complete -> result.path
            is RepositoryRelativeArtifactPathResult.Rejected -> fail(result.failure)
        }
        val report = StandalonePluginReportDocument(
            schemaVersion = 1,
            taskId = StandalonePluginReportTaskId.KVP_010,
            pluginId = KastStandalonePlugin.id,
            descriptorJarEntry = payload.descriptorJarEntry,
            artifact = PluginArtifactDocument(
                artifactPath,
                sha256(Files.readAllBytes(archive)),
                ArtifactSizeBytes(Files.size(archive)),
            ),
            payloadJars = payloadDocuments,
        )
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            pluginReportJson.encodeToString(StandalonePluginReportDocument.serializer(), report) + "\n",
        )
    }

    private fun fail(failure: StandalonePluginFailure): Nothing =
        throw GradleException("KVP-010 standalone plugin rejected: ${failure.name}")
}

private sealed interface PayloadObservationResult {
    data class Complete(val observations: List<PluginPayloadObservation>) : PayloadObservationResult
    data class Rejected(val failure: StandalonePluginFailure) : PayloadObservationResult
}

/**
 * Proof transition: `List<Path> -> PayloadObservationResult`.
 * Establishes readable JAR structure and a securely parsed descriptor observation for every input.
 * Expected malformed JAR or XML input is finite [StandalonePluginFailure]; raw bytes and XML are
 * extracted only at this Gradle packaging boundary.
 */
private fun observePayload(paths: List<Path>): PayloadObservationResult {
    val observations = mutableListOf<PluginPayloadObservation>()
    paths.forEach { path ->
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return PayloadObservationResult.Rejected(StandalonePluginFailure.MALFORMED_JAR)
        }
        val entries = linkedSetOf<String>()
        var descriptorBytes: DescriptorBytesObservation = DescriptorBytesObservation.Absent
        try {
            JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
                var entry = jar.nextJarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries += entry.name
                        if (entry.name == "META-INF/plugin.xml") {
                            descriptorBytes = DescriptorBytesObservation.Present(jar.readBytes())
                        }
                    }
                    entry = jar.nextJarEntry
                }
            }
        } catch (_: Exception) {
            return PayloadObservationResult.Rejected(StandalonePluginFailure.MALFORMED_JAR)
        }
        val descriptor = when (val raw = descriptorBytes) {
            DescriptorBytesObservation.Absent -> PluginDescriptorObservation.Absent
            is DescriptorBytesObservation.Present -> when (val parsed = parseDescriptor(raw.bytes)) {
                is DescriptorParseResult.Complete -> parsed.descriptor
                DescriptorParseResult.Rejected -> return PayloadObservationResult.Rejected(
                    StandalonePluginFailure.MALFORMED_DESCRIPTOR,
                )
            }
        }
        observations += PluginPayloadObservation(
            "${KastStandalonePlugin.root}/lib/${path.fileName}",
            entries,
            descriptor,
        )
    }
    return PayloadObservationResult.Complete(observations)
}

private sealed interface DescriptorBytesObservation {
    data object Absent : DescriptorBytesObservation
    data class Present(val bytes: ByteArray) : DescriptorBytesObservation
}

private sealed interface DescriptorParseResult {
    data class Complete(val descriptor: PluginDescriptorObservation.Present) : DescriptorParseResult
    data object Rejected : DescriptorParseResult
}

/**
 * Proof transition: `ByteArray -> DescriptorParseResult`.
 * Establishes a parsed plugin identity and explicit required-registration observations. Expected
 * malformed XML or missing identity is closed [DescriptorParseResult.Rejected]. Raw XML is
 * permitted only in this outer packaging boundary.
 */
private fun parseDescriptor(bytes: ByteArray): DescriptorParseResult {
    return try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val ids = document.getElementsByTagName("id")
        if (ids.length != 1) {
            DescriptorParseResult.Rejected
        } else {
            val id = ids.item(0).textContent.trim()
            if (id.isEmpty()) {
                DescriptorParseResult.Rejected
            } else {
                DescriptorParseResult.Complete(
                    PluginDescriptorObservation.Present(
                        id,
                        document.registration("appStarter"),
                        document.registration("projectResolve"),
                    ),
                )
            }
        }
    } catch (_: Exception) {
        DescriptorParseResult.Rejected
    }
}

private fun org.w3c.dom.Document.registration(tagName: String): RegistrationObservation =
    if (getElementsByTagName(tagName).length == 1) {
        RegistrationObservation.PRESENT
    } else {
        RegistrationObservation.ABSENT
    }

private data class AdmittedPluginArchiveFile(
    val entry: PluginArchiveEntry,
    val source: Path,
)

private fun writeArchiveAtomically(
    target: Path,
    files: List<AdmittedPluginArchiveFile>,
) {
    val bytes = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.entry.value).apply { time = 0L })
                Files.copy(file.source, zip)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }
    writeBytesAtomically(target, bytes)
}

private fun writeTextAtomically(target: Path, content: String) =
    writeBytesAtomically(target, content.toByteArray(Charsets.UTF_8))

private fun writeBytesAtomically(target: Path, bytes: ByteArray) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

@Serializable
@JvmInline
private value class ArtifactSha256(val value: String)

@Serializable
@JvmInline
private value class ArtifactSizeBytes(val value: Long)

@Serializable
private enum class StandalonePluginReportTaskId {
    @SerialName("KVP-010")
    KVP_010,
}

private fun sha256(bytes: ByteArray): ArtifactSha256 = ArtifactSha256(
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) },
)

@Serializable
private data class PluginArtifactDocument(
    val path: RepositoryRelativeArtifactPath,
    val sha256: ArtifactSha256,
    val sizeBytes: ArtifactSizeBytes,
)

@Serializable
private data class PayloadJarDocument(
    val entry: PluginArchiveEntry,
    val sha256: ArtifactSha256,
    val sizeBytes: ArtifactSizeBytes,
)

@Serializable
private data class StandalonePluginReportDocument(
    val schemaVersion: Int,
    val taskId: StandalonePluginReportTaskId,
    val pluginId: StandalonePluginId,
    val descriptorJarEntry: PluginArchiveEntry,
    val artifact: PluginArtifactDocument,
    val payloadJars: List<PayloadJarDocument>,
)

private val pluginReportJson = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}
