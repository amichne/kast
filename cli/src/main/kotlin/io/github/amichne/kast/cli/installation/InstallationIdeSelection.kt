package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_SAVED_SELECTION_BYTES = 65536

/** Control-only replacement retains the actual installed Host target instead of re-deriving a vendor profile. */
internal fun selectInstallationIdeLaunch(
    request: ControlInstallRequest,
    configuration: InstallationConfigurationSelection,
): Refinement<SelectedIdeLaunch, InstallationFailure> {
    val launch = SelectedIdeInstallation.resolve(request.ideaHome.value)
    if (request.controlOnly == InstallationSwitch.DISABLED)
        return selectFreshInstallationIdeLaunch(launch, request.ideaPluginTarget)
    val prior =
        when (configuration) {
            is InstallationConfigurationSelection.Prior -> configuration.root
            else -> return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
        }
    val saved =
        when (val read = readInstallationIdeLaunch(prior.resolve("config/selected-ide.json"))) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return retainInstallationIdeLaunch(launch, saved, request)
}

internal fun retainInstallationIdeLaunch(
    launch: SelectedIdeLaunch,
    saved: SelectedIdeLaunch,
    request: ControlInstallRequest,
): Refinement<SelectedIdeLaunch, InstallationFailure> =
    when (saved) {
        is SelectedIdeLaunch.Unavailable ->
            Refinement.Refined(recordIdePluginTarget(launch, InstalledHostPluginTarget.Unrecorded))
        is SelectedIdeLaunch.Resolved -> retainResolvedInstallationIdeLaunch(launch, saved, request)
    }

private fun retainResolvedInstallationIdeLaunch(
    launch: SelectedIdeLaunch,
    saved: SelectedIdeLaunch.Resolved,
    request: ControlInstallRequest,
): Refinement<SelectedIdeLaunch, InstallationFailure> {
    if (saved.home != request.ideaHome.value.toString())
        return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    val priorTarget =
        when (val target = saved.hostPluginTarget) {
            InstalledHostPluginTarget.Unrecorded -> null
            is InstalledHostPluginTarget.Recorded -> target.root
        }
    return when (val admitted = SelectedIdeInstallation.admitPluginTarget(priorTarget, request.home.value)) {
        is Refinement.Refined ->
            Refinement.Refined(
                recordIdePluginTarget(
                    when (launch) {
                        is SelectedIdeLaunch.Resolved -> launch
                        is SelectedIdeLaunch.Unavailable -> saved
                    },
                    admitted.value,
                )
            )
        is Refinement.Rejected -> Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    }
}

private fun selectFreshInstallationIdeLaunch(
    launch: SelectedIdeLaunch,
    target: InstalledHostPluginTarget,
): Refinement<SelectedIdeLaunch, InstallationFailure> =
    when (launch) {
        is SelectedIdeLaunch.Resolved -> Refinement.Refined(recordIdePluginTarget(launch, target))
        is SelectedIdeLaunch.Unavailable ->
            when (target) {
                InstalledHostPluginTarget.Unrecorded -> Refinement.Refined(launch)
                is InstalledHostPluginTarget.Recorded -> Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
            }
    }

private fun recordIdePluginTarget(launch: SelectedIdeLaunch, target: InstalledHostPluginTarget): SelectedIdeLaunch =
    SelectedIdeInstallation.recordInstalledHostTarget(launch, target)

internal fun admitInstallationIdeReuse(
    request: ControlInstallRequest,
    root: java.nio.file.Path,
): Refinement<Unit, InstallationFailure> {
    if (
        request.controlOnly == InstallationSwitch.ENABLED ||
            request.ideaPluginTarget == InstalledHostPluginTarget.Unrecorded
    )
        return Refinement.Refined(Unit)
    val expected =
        when (val selected = selectInstallationIdeLaunch(request, InstallationConfigurationSelection.FreshDefault)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    return when (val saved = readInstallationIdeLaunch(root.resolve("config/selected-ide.json"))) {
        is Refinement.Refined ->
            if (saved.value == expected) Refinement.Refined(Unit)
            else Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
        is Refinement.Rejected -> saved
    }
}

private fun readInstallationIdeLaunch(path: java.nio.file.Path): Refinement<SelectedIdeLaunch, InstallationFailure> =
    try {
        readRetainedInstallationIdeLaunch(path)
    } catch (_: java.io.IOException) {
        Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    } catch (_: SerializationException) {
        Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    } catch (_: IllegalArgumentException) {
        Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    }

private data class SavedSelectionFile(
    val identity: InstallationFilesystemIdentity,
    val permissions: Set<PosixFilePermission>,
    val directoryIdentity: InstallationFilesystemIdentity,
    val directoryPermissions: Set<PosixFilePermission>,
)

private fun readRetainedInstallationIdeLaunch(
    path: java.nio.file.Path
): Refinement<SelectedIdeLaunch, InstallationFailure> {
    val before =
        when (val captured = captureSavedSelection(path)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return captured
        }
    val raw = readSavedSelectionBytes(path)
    if (raw.size > MAXIMUM_SAVED_SELECTION_BYTES) return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    val selected = Json.decodeFromString<SelectedIdeLaunch>(raw.decodeToString(throwOnInvalidSequence = true))
    val retainedContent = raw.contentEquals(readSavedSelectionBytes(path))
    return when (val after = captureSavedSelection(path)) {
        is Refinement.Refined ->
            if (before == after.value && retainedContent) Refinement.Refined(selected)
            else Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
        is Refinement.Rejected -> after
    }
}

private fun captureSavedSelection(path: java.nio.file.Path): Refinement<SavedSelectionFile, InstallationFailure> {
    if (
        !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            path.toRealPath() != path ||
            path.parent.toRealPath() != path.parent
    )
        return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    val identity = savedSelectionIdentity(path)
    val directoryIdentity = savedSelectionIdentity(path.parent)
    val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
    val directoryPermissions = Files.getPosixFilePermissions(path.parent, NOFOLLOW_LINKS)
    val owner = com.sun.security.auth.module.UnixSystem().uid
    if (identity.owner != owner || directoryIdentity.owner != owner)
        return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    if (writableByAnotherOwner(permissions) || writableByAnotherOwner(directoryPermissions))
        return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
    return Refinement.Refined(SavedSelectionFile(identity, permissions, directoryIdentity, directoryPermissions))
}

private fun writableByAnotherOwner(permissions: Set<PosixFilePermission>): Boolean =
    PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions

private fun savedSelectionIdentity(path: java.nio.file.Path): InstallationFilesystemIdentity =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong(),
    )

private fun readSavedSelectionBytes(path: java.nio.file.Path): ByteArray =
    Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_SAVED_SELECTION_BYTES + 1) }

internal fun installationReportIdeLaunch(
    request: ControlInstallRequest,
    target: java.nio.file.Path,
    activation: InstallationActivation,
): SelectedIdeLaunch =
    when (activation) {
        InstallationActivation.Planned ->
            when (request.controlOnly) {
                InstallationSwitch.DISABLED ->
                    recordIdePluginTarget(
                        SelectedIdeInstallation.resolve(request.ideaHome.value),
                        request.ideaPluginTarget,
                    )
                InstallationSwitch.ENABLED -> savedInstallationReportIdeLaunch(target)
            }
        else -> savedInstallationReportIdeLaunch(target)
    }

private fun savedInstallationReportIdeLaunch(target: java.nio.file.Path): SelectedIdeLaunch =
    when (val saved = readInstallationIdeLaunch(target.resolve("config/selected-ide.json"))) {
        is Refinement.Refined -> saved.value
        is Refinement.Rejected ->
            SelectedIdeLaunch.Unavailable(
                io.github.amichne.kast.distribution.managed.IdeLaunchFailure.SAVED_SELECTION_REJECTED
            )
    }
