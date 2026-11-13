package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.ManagedHeadlessGradleHome
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLOutputFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal fun ContainerStore.admitGeneration(owned: AdmittedWorkspace): Refinement<AdmittedContainer, ContainerFailure> =
    containerFiles {
        when (val source = owned.generation) {
            is ContainerGenerationSource.Legacy ->
                when (val selected = runtime(source.runtime)) {
                    is Refinement.Rejected -> selected
                    is Refinement.Refined -> admitContainerState(AdmittedContainer(owned, selected.value))
                }
            ContainerGenerationSource.Current -> readGeneration(owned)
        }
    }

private fun ContainerStore.readGeneration(owned: AdmittedWorkspace): Refinement<AdmittedContainer, ContainerFailure> {
    val generation =
        when (val parsed = readContainerRecord<ContainerGenerationRecord>(owned.directory.resolve("generation.json"))) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val profile =
        when (val admitted = generationProfile(owned, generation)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    return when (val selected = runtime(generation.runtime)) {
        is Refinement.Rejected -> selected
        is Refinement.Refined ->
            if (generation.archiveSha256 == selected.value.archiveSha256)
                admitContainerState(AdmittedContainer(owned, selected.value, profile))
            else Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
    }
}

private fun generationProfile(
    owned: AdmittedWorkspace,
    generation: ContainerGenerationRecord,
): Refinement<Path, ContainerFailure> =
    when (generation.schemaVersion) {
        1 ->
            if (generation.generation == null) Refinement.Refined(owned.directory)
            else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        2 -> {
            val identity = generation.generation
            if (identity == null || java.util.UUID.fromString(identity).toString() != identity)
                Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            else admitPrivateContainerDirectory(owned.directory.resolve("generations/$identity"))
        }
        else -> Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

private fun ContainerStore.runtime(key: String): Refinement<AdmittedContainerRuntime, ContainerFailure> =
    when {
        runtimeDigest.matches(key) -> AdmittedContainerRuntime.read(runtimes.resolve(key), platform)
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+-${platform.publicName}").matches(key) ->
            AdmittedContainerRuntime.read(runtimes.resolve(key), platform)
        else -> Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

internal fun ContainerStore.readWorkspace(directory: Path): Refinement<AdmittedWorkspace, ContainerFailure> =
    containerFiles {
        val id =
            when (val parsed = ContainerId.parse(directory.fileName.toString())) {
                is Refinement.Rejected -> return@containerFiles parsed
                is Refinement.Refined -> parsed.value
            }
        when (val private = admitPrivateContainerDirectory(directory)) {
            is Refinement.Rejected -> return@containerFiles private
            is Refinement.Refined -> Unit
        }
        val parsed = containerFiles { readContainerRecord<ContainerRecord>(directory.resolve("container.json")) }
        when (parsed) {
            is Refinement.Rejected ->
                if (parsed.failure == ContainerFailure.INVALID_RECORD) readLegacy(directory, id) else parsed
            is Refinement.Refined -> admitCurrentRecord(parsed.value, id, directory)
        }
    }

private fun ContainerStore.admitCurrentRecord(
    record: ContainerRecord,
    id: ContainerId,
    directory: Path,
): Refinement<AdmittedWorkspace, ContainerFailure> {
    // Migrated v1 records retain their 32-character identity and unchanged private profile paths.
    if (record.schemaVersion != 2 || record.id != id.text) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    if (id.text.length != LEGACY_CONTAINER_ID_LENGTH && ContainerId.forWorkspace(Path.of(record.workspace)) != id)
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return when (val trust = admitContainerTrust(directory.resolve("trust.xml"), Path.of(record.workspace))) {
        is Refinement.Rejected -> trust
        is Refinement.Refined -> admitRecord(record, id, directory, ContainerGenerationSource.Current)
    }
}

/** Legacy records remain readable without rewriting a live profile or granting new trust. */
private fun ContainerStore.readLegacy(
    directory: Path,
    id: ContainerId,
): Refinement<AdmittedWorkspace, ContainerFailure> {
    val record =
        when (val parsed = readContainerRecord<LegacyContainerRecord>(directory.resolve("container.json"))) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    if (record.schemaVersion != 1 || record.id != id.text) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    val owned =
        when (
            val admitted =
                admitRecord(
                    ContainerRecord(record.id, record.name, record.workspace, record.gradleHome),
                    id,
                    directory,
                    ContainerGenerationSource.Legacy(record.runtime),
                )
        ) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    return when (val generation = admitGeneration(owned)) {
        is Refinement.Rejected -> generation
        is Refinement.Refined -> Refinement.Refined(owned)
    }
}

private fun ContainerStore.admitRecord(
    record: ContainerRecord,
    id: ContainerId,
    directory: Path,
    generation: ContainerGenerationSource,
): Refinement<AdmittedWorkspace, ContainerFailure> {
    val name =
        when (val parsed = ContainerName.parse(record.name)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val workspace = Path.of(record.workspace)
    val gradle = Path.of(record.gradleHome)
    if (!validWorkspace(workspace)) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    val privateGradle = directory.resolve("state/home/.gradle")
    // Availability is a launch fact, not durable ownership of this private workspace.
    if (gradle != privateGradle && !validExternalGradlePath(gradle))
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return Refinement.Refined(AdmittedWorkspace(id, name, workspace, directory, gradle, generation))
}

internal fun ContainerStore.validWorkspace(path: Path): Boolean {
    val canonical = path.isAbsolute && path.toFile().canonicalFile.toPath() == path
    val disjoint = !path.startsWith(root) && !root.startsWith(path)
    return canonical && disjoint && validContainerTrustPath(path)
}

internal fun ContainerStore.validExternalGradle(path: Path): Boolean {
    return validExternalGradlePath(path) && Files.isDirectory(path) && path.toRealPath() == path
}

private fun ContainerStore.validExternalGradlePath(path: Path): Boolean {
    val canonical = path.isAbsolute && path.normalize() == path
    val disjoint = !path.startsWith(root) && !root.startsWith(path)
    return canonical && disjoint && path.toString().none { it in "\r\n" }
}

/** Persisted trust and selectors must still name the creation-time workspace and private profile. */
internal fun admitContainerState(container: AdmittedContainer): Refinement<AdmittedContainer, ContainerFailure> {
    val plugins =
        when (val selected = selectedContainerPlugins(container)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    when (val saved = readContainerRecord<ManagedHeadlessGradleHome>(container.state.resolve("gradle-home.json"))) {
        is Refinement.Rejected -> return saved
        is Refinement.Refined ->
            if (saved.value.path != container.gradleHome.toString())
                return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
    if (
        !boundedPhysicalRecord(container.configuration) ||
            Files.readString(container.configuration) != containerConfiguration(container)
    )
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return when (
        val trust = admitContainerTrust(plugins.parent.resolve("options/trusted-paths.xml"), container.workspace)
    ) {
        is Refinement.Rejected -> trust
        is Refinement.Refined -> Refinement.Refined(container)
    }
}

internal fun selectedContainerPlugins(container: AdmittedContainer): Refinement<Path, ContainerFailure> {
    val launch =
        when (val selected = SelectedIdeInstallation.resolve(container.runtime.home)) {
            is SelectedIdeLaunch.Unavailable -> return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
            is SelectedIdeLaunch.Resolved -> selected
        }
    val plugins =
        when (val selected = SelectedIdeInstallation.pluginRoot(launch, container.home)) {
            is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            is Refinement.Refined -> selected.value.value
        }
    val target =
        when (val selected = SelectedIdeInstallation.admitPluginTarget(plugins.toString(), container.home)) {
            is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            is Refinement.Refined -> selected.value
        }
    val expected = SelectedIdeInstallation.recordInstalledHostTarget(launch, target)
    return when (
        val saved = readContainerRecord<SelectedIdeLaunch>(container.profile.resolve("config/selected-ide.json"))
    ) {
        is Refinement.Rejected -> saved
        is Refinement.Refined ->
            if (saved.value == expected) Refinement.Refined(plugins)
            else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
}

private fun boundedPhysicalRecord(path: Path): Boolean =
    Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) <= CONTAINER_RECORD_BYTES

private const val LEGACY_CONTAINER_ID_LENGTH = 32

internal fun admitContainerTrust(trust: Path, workspace: Path): Refinement<Unit, ContainerFailure> {
    if (!boundedPhysicalRecord(trust)) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return try {
        val factory = DocumentBuilderFactory.newDefaultInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        val actual = builder.parse(InputSource(StringReader(Files.readString(trust)))).documentElement
        val required = builder.parse(InputSource(StringReader(containerTrustXml(workspace)))).documentElement
        if (sameTrust(actual, required)) Refinement.Refined(Unit)
        else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    } catch (_: org.xml.sax.SAXException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
}

internal fun containerConfiguration(container: AdmittedContainer): String =
    "KAST_INSTALL_IDEA_HOME=${container.runtime.home}\nKAST_MANAGED_HEADLESS_STATE=${container.state}\nGRADLE_USER_HOME=${container.gradleHome}\n"

private fun sameTrust(actual: Element, required: Element): Boolean {
    if (actual.tagName != required.tagName || actual.attributes.length != required.attributes.length) return false
    for (index in 0 until required.attributes.length) {
        val attribute = required.attributes.item(index)
        if (actual.getAttribute(attribute.nodeName) != attribute.nodeValue) return false
    }
    val children = (0 until actual.childNodes.length).map { actual.childNodes.item(it) }
    if (children.any { it.nodeType != org.w3c.dom.Node.ELEMENT_NODE && !it.textContent.isNullOrBlank() }) return false
    val elements = children.filterIsInstance<Element>()
    val expected = (0 until required.childNodes.length).map { required.childNodes.item(it) }.filterIsInstance<Element>()
    return elements.size == expected.size && elements.zip(expected).all { (a, b) -> sameTrust(a, b) }
}

private val workspaceXmlPath = Regex("[\\u0020-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]+")

internal fun validContainerTrustPath(path: Path): Boolean = workspaceXmlPath.matches(path.toString())

internal fun containerTrustXml(workspace: Path): String {
    val output = StringWriter()
    val xml = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(output)
    xml.writeStartElement("application")
    xml.writeStartElement("component")
    xml.writeAttribute("name", "Trusted.Paths")
    xml.writeStartElement("option")
    xml.writeAttribute("name", "TRUSTED_PROJECT_PATHS")
    xml.writeStartElement("map")
    xml.writeEmptyElement("entry")
    xml.writeAttribute("key", workspace.toString())
    xml.writeAttribute("value", "true")
    xml.writeEndElement()
    xml.writeEndElement()
    xml.writeEndElement()
    xml.writeEndElement()
    xml.close()
    return output.toString()
}
