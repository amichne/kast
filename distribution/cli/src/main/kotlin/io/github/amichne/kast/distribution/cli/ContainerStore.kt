package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import javax.xml.stream.XMLOutputFactory

internal class AdmittedContainerCreation
internal constructor(
    val id: ContainerId,
    val name: ContainerName,
    val workspace: Path,
    val directory: Path,
    val gradleHome: Path,
)

/** Committed records are the registry. There is no second mutable index or selected-container link. */
internal class ContainerStore(val root: Path, val platform: ContainerPlatform) {
    val containers: Path = root.resolve("containers")
    val runtimes: Path = root.resolve("runtimes")

    fun list(): Refinement<List<AdmittedContainer>, ContainerFailure> = containerFiles {
        if (Files.notExists(containers, NOFOLLOW_LINKS)) return@containerFiles Refinement.Refined(emptyList())
        when (val private = privateContainerDirectory(containers)) {
            is Refinement.Rejected -> return@containerFiles private
            is Refinement.Refined -> Unit
        }
        readEntries()
    }

    private fun readEntries(): Refinement<List<AdmittedContainer>, ContainerFailure> =
        Files.newDirectoryStream(containers).use { readEntries(it) }

    private fun readEntries(
        entries: java.nio.file.DirectoryStream<Path>
    ): Refinement<List<AdmittedContainer>, ContainerFailure> {
        val result = mutableListOf<AdmittedContainer>()
        var count = 0
        for (directory in entries) {
            count += 1
            if (count > MAXIMUM_CONTAINER_ENTRIES) return Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
            val name = directory.fileName.toString()
            if (name.startsWith(".creating-") || name.startsWith(".removing-")) continue
            when (val admitted = read(directory)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> result += admitted.value
            }
        }
        val uniqueNames = result.map { it.name }.toSet().size == result.size
        val uniqueWorkspaces = result.map { it.workspace }.toSet().size == result.size
        return if (uniqueNames && uniqueWorkspaces) Refinement.Refined(result.sortedBy { it.id.text })
        else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

    fun select(selector: String): Refinement<AdmittedContainer, ContainerFailure> =
        when (val all = list()) {
            is Refinement.Rejected -> all
            is Refinement.Refined ->
                selectOne(
                    all.value.filter { it.id.text == selector || it.name.text == selector },
                    ContainerFailure.INVALID_RECORD,
                )
        }

    fun route(directory: Path): Refinement<AdmittedContainer, ContainerFailure> =
        when (val all = list()) {
            is Refinement.Rejected -> all
            is Refinement.Refined ->
                selectOne(all.value.filter { directory.startsWith(it.workspace) }, ContainerFailure.AMBIGUOUS_WORKSPACE)
        }

    /** Cheap workspace, name, overlap, and Gradle admission precedes runtime download or allocation. */
    fun admitCreation(
        request: ContainerCommand.Create,
        directory: Path,
    ): Refinement<AdmittedContainerCreation, ContainerFailure> = containerFiles { parseCreation(request, directory) }

    private fun parseCreation(
        request: ContainerCommand.Create,
        directory: Path,
    ): Refinement<AdmittedContainerCreation, ContainerFailure> {
        val workspace = Path.of(request.root ?: directory.toString()).toRealPath()
        if (!validWorkspace(workspace) || !Files.isDirectory(workspace))
            return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
        val id =
            when (val parsed = ContainerId.parse(UUID.randomUUID().toString().replace("-", ""))) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        val defaultName =
            workspace.fileName.toString().replace(Regex("(?:[^A-Za-z0-9._-])"), "-").take(DEFAULT_NAME_CHARACTERS)
        val name =
            when (
                val parsed =
                    ContainerName.parse(request.name ?: "c-$defaultName-${id.text.take(DEFAULT_ID_CHARACTERS)}")
            ) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        val destination = containers.resolve(id.text)
        val gradle =
            if (request.gradleHome == null) destination.resolve("state/home/.gradle")
            else Path.of(request.gradleHome).toRealPath()
        if (request.gradleHome != null && !validExternalGradle(gradle))
            return Refinement.Rejected(ContainerFailure.INVALID_GRADLE_HOME)
        val creation = AdmittedContainerCreation(id, name, workspace, destination, gradle)
        return when (val available = admitAvailableCreation(creation)) {
            is Refinement.Rejected -> available
            is Refinement.Refined -> Refinement.Refined(creation)
        }
    }

    private fun admitAvailableCreation(creation: AdmittedContainerCreation): Refinement<Unit, ContainerFailure> =
        when (val all = list()) {
            is Refinement.Rejected -> all
            is Refinement.Refined ->
                when {
                    all.value.size >= MAXIMUM_CONTAINER_ENTRIES ->
                        Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
                    all.value.any {
                        creation.workspace.startsWith(it.workspace) || it.workspace.startsWith(creation.workspace)
                    } -> Refinement.Rejected(ContainerFailure.WORKSPACE_EXISTS)
                    all.value.any {
                        creation.name == it.name || creation.name.text == it.id.text || creation.id.text == it.name.text
                    } -> Refinement.Rejected(ContainerFailure.NAME_EXISTS)
                    else -> Refinement.Refined(Unit)
                }
        }

    fun create(
        request: ContainerCommand.Create,
        directory: Path,
        runtime: AdmittedContainerRuntime,
    ): Refinement<AdmittedContainer, ContainerFailure> =
        when (val admitted = admitCreation(request, directory)) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> create(admitted.value, runtime)
        }

    fun create(
        creation: AdmittedContainerCreation,
        runtime: AdmittedContainerRuntime,
    ): Refinement<AdmittedContainer, ContainerFailure> = containerFiles {
        when (val private = privateContainerDirectory(containers)) {
            is Refinement.Rejected -> return@containerFiles private
            is Refinement.Refined -> Unit
        }
        val staging =
            Files.createDirectory(
                containers.resolve(".creating-${creation.id.text}"),
                PosixFilePermissions.asFileAttribute(containerDirectoryMode),
            )
        for (child in listOf("state/home/.gradle", "state/system", "state/log", "state/tmp", "config")) Files
            .createDirectories(staging.resolve(child), PosixFilePermissions.asFileAttribute(containerDirectoryMode))
        val admitted =
            AdmittedContainer(
                creation.id,
                creation.name,
                creation.workspace,
                creation.directory,
                runtime,
                creation.gradleHome,
            )
        when (val initialized = initializeProfile(admitted, staging)) {
            is Refinement.Rejected -> return@containerFiles initialized
            is Refinement.Refined -> Unit
        }
        writeContainerRecord(staging.resolve("container.json"), admitted.record())
        // Only this rename publishes trust to routing; partial staging cannot be selected.
        Files.move(staging, creation.directory, StandardCopyOption.ATOMIC_MOVE)
        Refinement.Refined(admitted)
    }

    private fun initializeProfile(container: AdmittedContainer, staging: Path): Refinement<Unit, ContainerFailure> {
        val launch =
            when (val selected = SelectedIdeInstallation.resolve(container.runtime.home)) {
                is SelectedIdeLaunch.Unavailable -> return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                is SelectedIdeLaunch.Resolved -> selected
            }
        val stagedPlugins =
            when (val selected = SelectedIdeInstallation.pluginRoot(launch, staging.resolve("state/home"))) {
                is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                is Refinement.Refined -> selected.value.value
            }
        when (val copied = copyPlugins(container.runtime.plugins, stagedPlugins)) {
            is Refinement.Rejected -> return copied
            is Refinement.Refined -> Unit
        }
        val plugins = container.directory.resolve(staging.relativize(stagedPlugins))
        val target =
            when (val parsed = SelectedIdeInstallation.admitPluginTarget(plugins.toString(), container.home)) {
                is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                is Refinement.Refined -> parsed.value
            }
        writeContainerRecord(
            staging.resolve("config/selected-ide.json"),
            SelectedIdeInstallation.recordInstalledHostTarget(launch, target),
        )
        val trust = stagedPlugins.parent.resolve("options/trusted-paths.xml")
        Files.createDirectories(trust.parent)
        Files.writeString(trust, containerTrustXml(container.workspace), StandardOpenOption.CREATE_NEW)
        writeContainerRecord(
            staging.resolve("state/gradle-home.json"),
            io.github.amichne.kast.distribution.managed.ManagedHeadlessGradleHome(container.gradleHome.toString()),
        )
        Files.writeString(
            staging.resolve("config/environment"),
            containerConfiguration(container),
            StandardOpenOption.CREATE_NEW,
        )
        return Refinement.Refined(Unit)
    }

    private fun copyPlugins(source: Path, destination: Path): Refinement<Unit, ContainerFailure> {
        Files.walk(source).use { paths ->
            for (path in paths.toList()) {
                val target = destination.resolve(source.relativize(path))
                when {
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> Files.copy(path, target)
                    else -> return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
                }
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun read(directory: Path): Refinement<AdmittedContainer, ContainerFailure> {
        val id =
            when (val parsed = ContainerId.parse(directory.fileName.toString())) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        when (val private = privateContainerDirectory(directory)) {
            is Refinement.Rejected -> return private
            is Refinement.Refined -> Unit
        }
        val record =
            when (val parsed = readContainerRecord<ContainerRecord>(directory.resolve("container.json"))) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        if (record.schemaVersion != 1 || record.id != id.text)
            return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        return admitRecord(record, id, directory)
    }

    private fun admitRecord(
        record: ContainerRecord,
        id: ContainerId,
        directory: Path,
    ): Refinement<AdmittedContainer, ContainerFailure> {
        val name =
            when (val parsed = ContainerName.parse(record.name)) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        val runtime =
            when (val parsed = runtime(record.runtime)) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        val workspace = Path.of(record.workspace)
        val gradle = Path.of(record.gradleHome)
        if (!validWorkspace(workspace)) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        val privateGradle = directory.resolve("state/home/.gradle")
        if (gradle != privateGradle && !validExternalGradle(gradle))
            return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        if (!Files.isDirectory(gradle) || gradle.toRealPath() != gradle)
            return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        return admitContainerState(AdmittedContainer(id, name, workspace, directory, runtime, gradle))
    }

    private fun runtime(key: String): Refinement<AdmittedContainerRuntime, ContainerFailure> =
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+-${platform.publicName}").matches(key))
            Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        else AdmittedContainerRuntime.read(runtimes.resolve(key), key.removeSuffix("-${platform.publicName}"), platform)

    private fun validWorkspace(path: Path): Boolean {
        val canonical = path.isAbsolute && path.toFile().canonicalFile.toPath() == path
        val disjoint = !path.startsWith(root) && !root.startsWith(path)
        return canonical && disjoint && validContainerTrustPath(path)
    }

    private fun validExternalGradle(path: Path): Boolean {
        val canonical = path.isAbsolute && path.toRealPath() == path && Files.isDirectory(path)
        val disjoint = !path.startsWith(root) && !root.startsWith(path)
        return canonical && disjoint && path.toString().none { it in "\r\n" }
    }

    private fun selectOne(
        matches: List<AdmittedContainer>,
        ambiguous: ContainerFailure,
    ): Refinement<AdmittedContainer, ContainerFailure> =
        when (matches.size) {
            0 -> Refinement.Rejected(ContainerFailure.NOT_FOUND)
            1 -> Refinement.Refined(matches.single())
            else -> Refinement.Rejected(ambiguous)
        }
}

private const val MAXIMUM_CONTAINER_ENTRIES = 1024
private const val DEFAULT_NAME_CHARACTERS = 48
private const val DEFAULT_ID_CHARACTERS = 8
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
