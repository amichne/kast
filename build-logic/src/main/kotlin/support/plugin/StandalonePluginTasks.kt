package support.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class BuildStandalonePluginTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val payloadJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val pluginArchive: RegularFileProperty

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
        writeArchiveAtomically(pluginArchive.get().asFile.toPath(), admittedFiles)
    }

    private fun fail(failure: StandalonePluginFailure): Nothing =
        throw GradleException("Standalone plugin rejected: ${failure.name}")
}

private sealed interface PayloadObservationResult {
    data class Complete(val observations: List<PluginPayloadObservation>) : PayloadObservationResult
    data class Rejected(val failure: StandalonePluginFailure) : PayloadObservationResult
}

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

internal sealed interface DescriptorParseResult {
    data class Complete(val descriptor: PluginDescriptorObservation.Present) : DescriptorParseResult
    data object Rejected : DescriptorParseResult
}

internal fun parseDescriptor(bytes: ByteArray): DescriptorParseResult {
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
                        document.registration("projectService"),
                        document.registration("postStartupActivity"),
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
