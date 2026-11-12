package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Committed records are the registry. There is no second mutable index or selected-container link. */
internal class ContainerStore(val root: Path, val platform: ContainerPlatform) {
    val containers: Path = root.resolve("containers")
    val runtimes: Path = root.resolve("runtimes")

    /** Cheap workspace, name, and Gradle admission precedes runtime download or allocation. */
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
        val id = ContainerId.forWorkspace(workspace)
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

    internal fun admitAvailableCreation(creation: AdmittedContainerCreation): Refinement<Unit, ContainerFailure> =
        when (val all = enumerate()) {
            is Refinement.Rejected -> all
            is Refinement.Refined -> {
                val admitted = all.value.filterIsInstance<ContainerStoreEntry.Admitted>().map { it.workspace }
                when {
                    all.value.size >= MAXIMUM_CONTAINER_ENTRIES ->
                        Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
                    Files.exists(creation.directory, NOFOLLOW_LINKS) ||
                        admitted.any { creation.workspace == it.workspace } ->
                        Refinement.Rejected(ContainerFailure.WORKSPACE_EXISTS)
                    admitted.any {
                        creation.name == it.name || creation.name.text == it.id.text || creation.id.text == it.name.text
                    } -> Refinement.Rejected(ContainerFailure.NAME_EXISTS)
                    else -> Refinement.Refined(Unit)
                }
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
    ): Refinement<AdmittedContainer, ContainerFailure> = withStagedCreation(creation, runtime, ::publishCreation)

    fun <T> withStagedCreation(
        creation: AdmittedContainerCreation,
        runtime: AdmittedContainerRuntime,
        publish: (StagedContainerCreation) -> Refinement<T, ContainerFailure>,
    ): Refinement<T, ContainerFailure> = containerFiles {
        when (val private = privateContainerDirectory(containers)) {
            is Refinement.Rejected -> return@containerFiles private
            is Refinement.Refined -> Unit
        }
        val staging =
            Files.createDirectory(
                containers.resolve(".creating-${java.util.UUID.randomUUID()}"),
                PosixFilePermissions.asFileAttribute(containerDirectoryMode),
            )
        try {
            when (val staged = stageCreation(creation, runtime, staging)) {
                is Refinement.Rejected -> staged
                is Refinement.Refined -> publish(StagedContainerCreation(creation, staged.value, staging))
            }
        } finally {
            if (Files.exists(staging, NOFOLLOW_LINKS))
                Files.walk(staging).use { paths ->
                    for (path in paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path)
                }
        }
    }

    private fun stageCreation(
        creation: AdmittedContainerCreation,
        runtime: AdmittedContainerRuntime,
        staging: Path,
    ): Refinement<AdmittedContainer, ContainerFailure> {
        for (child in listOf("state/home/.gradle", "state/system", "state/log", "state/tmp", "config")) Files
            .createDirectories(staging.resolve(child), PosixFilePermissions.asFileAttribute(containerDirectoryMode))
        val admitted =
            AdmittedContainer(
                AdmittedWorkspace(
                    creation.id,
                    creation.name,
                    creation.workspace,
                    creation.directory,
                    creation.gradleHome,
                    ContainerGenerationSource.Current,
                ),
                runtime,
            )
        when (val initialized = initializeProfile(admitted, staging)) {
            is Refinement.Rejected -> return initialized
            is Refinement.Refined -> Unit
        }
        writeContainerRecord(
            staging.resolve("generation.json"),
            ContainerGenerationRecord(runtime.key, runtime.archiveSha256),
        )
        Files.writeString(
            staging.resolve("trust.xml"),
            containerTrustXml(creation.workspace),
            StandardOpenOption.CREATE_NEW,
        )
        writeContainerRecord(staging.resolve("container.json"), admitted.record())
        return Refinement.Refined(admitted)
    }

    fun publishCreation(staged: StagedContainerCreation): Refinement<AdmittedContainer, ContainerFailure> =
        containerFiles {
            when (val available = admitAvailableCreation(staged.creation)) {
                is Refinement.Rejected -> return@containerFiles available
                is Refinement.Refined -> Unit
            }
            // Only this rename publishes trust to routing; partial staging cannot be selected.
            Files.move(staged.staging, staged.creation.directory, StandardCopyOption.ATOMIC_MOVE)
            Refinement.Refined(staged.container)
        }

    fun rebuildRetiredGeneration(
        previous: AdmittedContainer,
        runtime: AdmittedContainerRuntime,
    ): Refinement<AdmittedContainer, ContainerFailure> = containerFiles {
        when (val retired = admitContainerRetirement(previous)) {
            is Refinement.Rejected -> return@containerFiles retired
            is Refinement.Refined -> Unit
        }
        if (runtime.root.parent != runtimes)
            return@containerFiles Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
        val generations =
            when (val owned = privateContainerDirectory(previous.directory.resolve("generations"))) {
                is Refinement.Rejected -> return@containerFiles owned
                is Refinement.Refined -> owned.value
            }
        val identity = java.util.UUID.randomUUID().toString()
        val current = AdmittedContainer(previous.owned, runtime, generations.resolve(identity))
        val staging = generations.resolve(".creating-$identity")
        when (val prepared = stageGeneration(current, staging)) {
            is Refinement.Rejected -> return@containerFiles prepared
            is Refinement.Refined -> Unit
        }
        publishGeneration(previous, current, staging)
    }

    private fun publishGeneration(
        previous: AdmittedContainer,
        current: AdmittedContainer,
        staging: Path,
    ): Refinement<AdmittedContainer, ContainerFailure> {
        when (val retired = admitContainerRetirement(previous)) {
            is Refinement.Rejected -> return retired
            is Refinement.Refined -> Unit
        }
        Files.move(staging, current.profile, StandardCopyOption.ATOMIC_MOVE)
        val identity = current.profile.fileName.toString()
        val record = previous.directory.resolve(".generation-$identity")
        writeContainerRecord(
            record,
            ContainerGenerationRecord(current.runtime.key, current.runtime.archiveSha256, 2, identity),
        )
        // Retired profiles and their unresolved change evidence are retained, never erased here.
        Files.move(
            record,
            previous.directory.resolve("generation.json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return admitGeneration(previous.owned)
    }

    private fun stageGeneration(container: AdmittedContainer, staging: Path): Refinement<Unit, ContainerFailure> {
        Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
        for (child in listOf("state/home", "state/system", "state/log", "state/tmp", "config")) {
            Files.createDirectories(
                staging.resolve(child),
                PosixFilePermissions.asFileAttribute(containerDirectoryMode),
            )
        }
        return initializeProfile(container, staging)
    }

    fun recordRetirement(container: AdmittedContainer): Refinement<Unit, ContainerFailure> = containerFiles {
        if (container.owned.generation != ContainerGenerationSource.Current)
            return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_RECORD)
        val destination = container.directory.resolve("generation-retirement.json")
        if (!Files.notExists(destination, NOFOLLOW_LINKS)) {
            when (val existing = readContainerRetirement(destination)) {
                is Refinement.Rejected -> return@containerFiles existing
                is Refinement.Refined -> Unit
            }
        }
        val temporary = container.directory.resolve(".retirement-${java.util.UUID.randomUUID()}")
        writeContainerRecord(temporary, retirementBinding(container))
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Refinement.Refined(Unit)
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
        val plugins = container.profile.resolve(staging.relativize(stagedPlugins))
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
        val durableTrust = container.directory.resolve("trust.xml")
        val grant =
            if (!Files.notExists(container.directory, NOFOLLOW_LINKS)) {
                when (val admitted = admitContainerTrust(durableTrust, container.workspace)) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> Files.readString(durableTrust)
                }
            } else containerTrustXml(container.workspace)
        Files.writeString(trust, grant, StandardOpenOption.CREATE_NEW)
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
}

internal const val MAXIMUM_CONTAINER_ENTRIES = 1024
private const val DEFAULT_NAME_CHARACTERS = 48
private const val DEFAULT_ID_CHARACTERS = 8
