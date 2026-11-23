package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class LoadedApplicationHost(
    val runtimeIdentity: ApplicationRuntimeIdentity,
    val compatibility: ApplicationHostCompatibility,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val gradleImportEnvironmentIdentity: GradleImportEnvironmentIdentity? = null,
)

@Serializable
internal data class ApplicationRuntimeIdentity(
    val ideaHome: String,
    val profile: String,
    val pid: Long,
    val processStartEpochMillis: Long,
    val hostLifetime: String,
    val buildIdentity: ApplicationHostBuild,
)

@Serializable
internal data class ApplicationHostBuild(val version: String, val sourceRevision: String, val sourceTree: String)

@Serializable
internal data class ApplicationHostCompatibility(
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val hostedPluginVersion: String,
    val hostedContract: ApplicationHostedContract,
)

@Serializable
internal data class ApplicationHostedContract(
    val type: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
)

@Serializable
private data class ApplicationEndpointDocument(
    val type: String,
    val protocol: Int,
    val root: String,
    val socket: String,
    val hostPid: Long,
    val host: String,
    val querySchema: String,
    val operations: List<String>,
    val application: LoadedApplicationHost,
)

internal enum class ApplicationHostFailure(val step: PassiveHostStep) {
    INSTALLATION_UNAVAILABLE(PassiveHostStep.SELECTION),
    SELECTION_UNAVAILABLE(PassiveHostStep.SELECTION),
    PROFILE_UNAVAILABLE(PassiveHostStep.SELECTION),
    DESCRIPTOR_UNAVAILABLE(PassiveHostStep.DESCRIPTOR_READ),
    DESCRIPTOR_REJECTED(PassiveHostStep.DESCRIPTOR_ADMISSION),
    PROCESS_UNAVAILABLE(PassiveHostStep.PROCESS_LOOKUP),
    PROCESS_IDENTITY_MISMATCH(PassiveHostStep.PROCESS_IDENTITY),
}

private val applicationJson = Json { ignoreUnknownKeys = false }

internal sealed interface ApplicationProcessObservation {
    data class Present(val pid: Long, val processStartEpochMillis: Long, val executable: String) :
        ApplicationProcessObservation

    data object Unavailable : ApplicationProcessObservation
}

private fun observeApplicationProcess(pid: Long): ApplicationProcessObservation {
    val selected = ProcessHandle.of(pid)
    if (selected.isEmpty || !selected.get().isAlive) return ApplicationProcessObservation.Unavailable
    val info = selected.get().info()
    val started = info.startInstant()
    val command = info.command()
    if (started.isEmpty || command.isEmpty) return ApplicationProcessObservation.Unavailable
    return ApplicationProcessObservation.Present(pid, started.get().toEpochMilli(), command.get())
}

/** Passive loaded identity comes from the existing immutable application advertisement and its exact live process. */
internal fun readApplicationHost(
    root: Path,
    home: Path,
    observeProcess: (Long) -> ApplicationProcessObservation = ::observeApplicationProcess,
): Observation<LoadedApplicationHost> = inspectApplicationHost(root, home, observeProcess).observation

internal fun inspectApplicationHost(
    root: Path,
    home: Path,
    observeProcess: (Long) -> ApplicationProcessObservation,
): PassiveHostInspection =
    observeSelectedApplication(home, observeProcess) {
        readApplicationSelection(InstallationLayout.of(selectedInstallation(root)).configuration.selectedIde, home)
    }

internal fun readWorkspaceApplicationHost(container: AdmittedContainer): PassiveHostInspection =
    observeSelectedApplication(container.home, ::observeApplicationProcess) {
        readApplicationSelection(container.selectedIde, container.home)
    }

private fun observeSelectedApplication(
    home: Path,
    observeProcess: (Long) -> ApplicationProcessObservation,
    readSelection: () -> ApplicationSelection,
): PassiveHostInspection {
    return try {
        val selected =
            try {
                readSelection()
            } catch (_: IllegalArgumentException) {
                return unavailable(ApplicationHostFailure.SELECTION_UNAVAILABLE)
            } catch (_: IOException) {
                return unavailable(ApplicationHostFailure.SELECTION_UNAVAILABLE)
            } catch (_: SecurityException) {
                return unavailable(ApplicationHostFailure.SELECTION_UNAVAILABLE)
            }
        when (val selection = selected) {
            is ApplicationSelection.Admitted -> readApplicationAdvertisement(selection, home, observeProcess)
            is ApplicationSelection.Rejected -> unavailable(selection.reason)
        }
    } catch (_: ManagementRejected) {
        unavailable(ApplicationHostFailure.INSTALLATION_UNAVAILABLE)
    } catch (_: IOException) {
        unavailable(ApplicationHostFailure.DESCRIPTOR_UNAVAILABLE)
    } catch (_: SerializationException) {
        unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED)
    } catch (_: IllegalArgumentException) {
        unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED)
    } catch (_: SecurityException) {
        unavailable(ApplicationHostFailure.DESCRIPTOR_UNAVAILABLE)
    }
}

private sealed interface ApplicationSelection {
    data class Admitted(
        val launch: SelectedIdeLaunch.Resolved,
        val profile: Path,
        val directory: Path,
        val record: Path,
        val recordAttributes: BasicFileAttributes,
    ) : ApplicationSelection

    data class Rejected(val reason: ApplicationHostFailure) : ApplicationSelection
}

private fun readApplicationSelection(record: Path, home: Path): ApplicationSelection {
    if (!physicalFile(record)) return ApplicationSelection.Rejected(ApplicationHostFailure.SELECTION_UNAVAILABLE)
    val attributes = Files.readAttributes(record, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val raw =
        readBoundedFile(record, MAXIMUM_APPLICATION_DESCRIPTOR_BYTES)
            ?: return ApplicationSelection.Rejected(ApplicationHostFailure.SELECTION_UNAVAILABLE)
    val selected = applicationJson.decodeFromString<SelectedIdeLaunch>(raw)
    if (selected !is SelectedIdeLaunch.Resolved || !SelectedIdeInstallation.matchesCurrentLaunch(selected))
        return ApplicationSelection.Rejected(ApplicationHostFailure.SELECTION_UNAVAILABLE)
    val rootSelection =
        when (val target = selected.hostPluginTarget) {
            is InstalledHostPluginTarget.Recorded -> SelectedIdeInstallation.recordedPluginRoot(target, home)
            InstalledHostPluginTarget.Unrecorded -> SelectedIdeInstallation.pluginRoot(selected, home)
        }
    val profile =
        when (rootSelection) {
            is Refinement.Refined -> rootSelection.value.value.parent
            is Refinement.Rejected -> return ApplicationSelection.Rejected(ApplicationHostFailure.PROFILE_UNAVAILABLE)
        }
    return ApplicationSelection.Admitted(
        selected,
        profile,
        io.github.amichne.kast.distribution.contract.HostedEndpointPaths.directory(
            home,
            io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.LIFECYCLE,
            selected.home,
        ),
        record,
        attributes,
    )
}

private fun readApplicationAdvertisement(
    selection: ApplicationSelection.Admitted,
    home: Path,
    observeProcess: (Long) -> ApplicationProcessObservation,
): PassiveHostInspection {
    val descriptor = selection.directory.resolve("endpoint.json")
    if (!physicalFile(descriptor)) return unavailable(ApplicationHostFailure.DESCRIPTOR_UNAVAILABLE)
    val attributes = Files.readAttributes(descriptor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val owner = Files.getOwner(home, NOFOLLOW_LINKS)
    if (
        Files.getOwner(descriptor, NOFOLLOW_LINKS) != owner ||
            Files.getOwner(selection.directory, NOFOLLOW_LINKS) != owner
    )
        return unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED)
    val raw =
        readBoundedFile(descriptor, MAXIMUM_APPLICATION_DESCRIPTOR_BYTES)
            ?: return unavailable(ApplicationHostFailure.DESCRIPTOR_UNAVAILABLE)
    val document =
        try {
            applicationJson.decodeFromString<ApplicationEndpointDocument>(raw)
        } catch (_: IllegalArgumentException) {
            return unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED, PassiveHostStep.DESCRIPTOR_DECODE)
        }
    if (!validApplicationDescriptor(document, selection)) return unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED)
    val inspection = inspectApplicationProcess(document.application, selection.launch.executable, observeProcess)
    if (inspection is PassiveHostInspection.Rejected) return inspection
    return if (stableApplicationFiles(descriptor, attributes, selection)) inspection
    else unavailable(ApplicationHostFailure.DESCRIPTOR_REJECTED, PassiveHostStep.DESCRIPTOR_RECHECK)
}

private fun inspectApplicationProcess(
    application: LoadedApplicationHost,
    executable: String,
    observeProcess: (Long) -> ApplicationProcessObservation,
): PassiveHostInspection {
    val identity = application.runtimeIdentity
    val process =
        try {
            observeProcess(identity.pid)
        } catch (_: SecurityException) {
            return unavailable(ApplicationHostFailure.PROCESS_UNAVAILABLE)
        }
    if (process !is ApplicationProcessObservation.Present)
        return unavailable(ApplicationHostFailure.PROCESS_UNAVAILABLE)
    if (
        process.pid != identity.pid ||
            process.processStartEpochMillis != identity.processStartEpochMillis ||
            process.executable != executable
    )
        return unavailable(ApplicationHostFailure.PROCESS_IDENTITY_MISMATCH)
    return PassiveHostInspection.Loaded(application)
}

private fun stableApplicationFiles(
    descriptor: Path,
    attributes: BasicFileAttributes,
    selection: ApplicationSelection.Admitted,
): Boolean =
    try {
        sameApplicationFile(descriptor, attributes) && sameApplicationFile(selection.record, selection.recordAttributes)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun sameApplicationFile(path: Path, before: BasicFileAttributes): Boolean {
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    return after.fileKey() == before.fileKey() &&
        after.size() == before.size() &&
        after.lastModifiedTime() == before.lastModifiedTime()
}

private fun validApplicationDescriptor(
    document: ApplicationEndpointDocument,
    selection: ApplicationSelection.Admitted,
): Boolean {
    return validApplicationRouting(document, selection) &&
        validApplicationIdentity(document, selection) &&
        validLoadedBuild(document.application)
}

private fun validApplicationRouting(
    document: ApplicationEndpointDocument,
    selection: ApplicationSelection.Admitted,
): Boolean {
    if (document.type != "KAST_IDE_ENDPOINT" || document.protocol != APPLICATION_ENDPOINT_PROTOCOL) return false
    if (document.querySchema != "kast.workspace.lifecycle.v2" || document.operations != listOf("WORKSPACE_LIFECYCLE"))
        return false
    return document.root == selection.launch.home &&
        document.socket == selection.directory.resolve("host.sock").toString()
}

private fun validApplicationIdentity(
    document: ApplicationEndpointDocument,
    selection: ApplicationSelection.Admitted,
): Boolean {
    val identity = document.application.runtimeIdentity
    if (document.hostPid != identity.pid || document.host != identity.hostLifetime) return false
    if (identity.ideaHome != selection.launch.home || identity.profile != selection.profile.toString()) return false
    if (identity.pid <= 0 || identity.processStartEpochMillis <= 0) return false
    return UUID.fromString(identity.hostLifetime).toString() == identity.hostLifetime
}

private fun validLoadedBuild(loaded: LoadedApplicationHost): Boolean {
    val build = loaded.runtimeIdentity.buildIdentity
    val compatibility = loaded.compatibility
    if (
        !Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(build.version) ||
            build.version != compatibility.hostedPluginVersion
    )
        return false
    return validApplicationProvenance(build) && validApplicationCompatibility(compatibility)
}

private fun validApplicationProvenance(build: ApplicationHostBuild): Boolean =
    listOf(build.sourceRevision, build.sourceTree).all {
        Regex("[0-9a-f]{40}").matches(it) && it != "0".repeat(SOURCE_REVISION_LENGTH)
    }

private fun validApplicationCompatibility(compatibility: ApplicationHostCompatibility): Boolean =
    Regex("(?:262|263)\\.[0-9]+\\.[0-9]+").matches(compatibility.ideBuild) &&
        compatibility.kotlinPluginBuild == "${compatibility.ideBuild}-IJ" &&
        validApplicationContract(compatibility.hostedContract)

private fun validApplicationContract(contract: ApplicationHostedContract): Boolean {
    if (
        contract.type != "HOSTED_CONTRACT" ||
            !Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\\.v[1-9][0-9]*").matches(contract.runtimeProtocolIdentity)
    )
        return false
    if (
        contract.capabilities.isEmpty() ||
            contract.capabilities.size > HOSTED_APPLICATION_CAPABILITIES.size ||
            contract.capabilities.distinct() != contract.capabilities
    )
        return false
    return contract.capabilities.all { it in HOSTED_APPLICATION_CAPABILITIES } &&
        listOf(contract.operationRegistryDigest, contract.wireSchemaDigest).all {
            Regex("sha256:[0-9a-f]{64}").matches(it)
        }
}

private fun physicalFile(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path

private fun unavailable(reason: ApplicationHostFailure, step: PassiveHostStep = reason.step): PassiveHostInspection =
    PassiveHostInspection.Rejected(reason, step)

private const val MAXIMUM_APPLICATION_DESCRIPTOR_BYTES = 65_536L
private const val SOURCE_REVISION_LENGTH = 40
private const val APPLICATION_ENDPOINT_PROTOCOL = 3
private val HOSTED_APPLICATION_CAPABILITIES =
    setOf(
        "workspace.lifecycle",
        "query.run",
        "source.read",
        "diagnostic.check",
        "change.plan",
        "change.apply",
        "change.recover",
    )
