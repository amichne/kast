package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdePluginRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal enum class HostPluginCleanupFailure {
    CONFIGURATION_UNPROVEN,
    TARGET_UNRECORDED,
    OWNERSHIP_UNPROVEN,
    FILESYSTEM_REJECTED,
    RECOVERY_REQUIRED,
    PAYLOAD_REJECTED,
    RELEASE_REJECTED,
    COMPATIBILITY_REJECTED,
    PROCESS_UNAVAILABLE,
    DEADLINE_EXCEEDED,
    OUTPUT_REJECTED,
}

internal sealed interface HostRemovalProcessObservation {
    data class Exited(val code: Int, val output: String) : HostRemovalProcessObservation

    data class Rejected(val failure: HostPluginCleanupFailure) : HostRemovalProcessObservation
}

internal fun removeConfiguredUninstallHost(
    payload: Path,
    home: Path,
    observe: (UninstallCleanupObservation) -> Unit,
    execute: (List<String>) -> HostRemovalProcessObservation = ::executeHostRemoval,
) {
    observe(UninstallCleanupObservation.Started(UninstallArtifact.HOST_PLUGIN))
    val removed = hostRemovalBoundary {
        when (val admitted = admitConfiguredHostRemoval(payload, home)) {
            is Refinement.Refined -> admitted.value.remove(execute)
            is Refinement.Rejected -> admitted
        }
    }
    when (removed) {
        is Refinement.Refined -> observe(UninstallCleanupObservation.Completed(UninstallArtifact.HOST_PLUGIN))
        is Refinement.Rejected -> rejectHostRemoval(removed.failure, observe)
    }
}

private class AdmittedHostRemoval(
    private val payload: Path,
    private val manifest: BundledManifest,
    private val root: SelectedIdePluginRoot,
    private val selection: CapturedUninstallIdeSelection,
) {
    fun remove(execute: (List<String>) -> HostRemovalProcessObservation): Refinement<Unit, HostPluginCleanupFailure> {
        if (!payloadOwned(payload, manifest, HOST_HELPER) || !selection.unchanged())
            return Refinement.Rejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN)
        val plugin = root.value.resolve("kast-ide-hosted")
        val command =
            listOf(
                "python3",
                "-I",
                payload.resolve(HOST_HELPER).toString(),
                "--remove",
                "--plugin-root",
                root.value.toString(),
            )
        return when (val response = admitHostRemovalOutput(execute(command), plugin)) {
            is Refinement.Rejected -> response
            is Refinement.Refined ->
                if (Files.notExists(plugin, NOFOLLOW_LINKS)) response
                else Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
        }
    }
}

private fun admitConfiguredHostRemoval(
    payload: Path,
    home: Path,
): Refinement<AdmittedHostRemoval, HostPluginCleanupFailure> {
    val manifest =
        when (val read = readBundledManifest(payload)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Unavailable,
            BundledManifestRead.Invalid -> return Refinement.Rejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN)
        }
    if (!payloadOwned(payload, manifest, HOST_HELPER))
        return Refinement.Rejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN)
    val selection =
        when (val admitted = CapturedUninstallIdeSelection.admit(payload.resolve(SELECTED_IDE))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return bindRecordedHostRemoval(payload, manifest, selection, home)
}

private fun bindRecordedHostRemoval(
    payload: Path,
    manifest: BundledManifest,
    selection: CapturedUninstallIdeSelection,
    home: Path,
): Refinement<AdmittedHostRemoval, HostPluginCleanupFailure> {
    val target =
        when (val recorded = selection.selected.hostPluginTarget) {
            InstalledHostPluginTarget.Unrecorded ->
                return Refinement.Rejected(HostPluginCleanupFailure.TARGET_UNRECORDED)
            is InstalledHostPluginTarget.Recorded -> recorded
        }
    return when (val root = SelectedIdeInstallation.recordedPluginRoot(target, home)) {
        is Refinement.Refined -> Refinement.Refined(AdmittedHostRemoval(payload, manifest, root.value, selection))
        is Refinement.Rejected -> Refinement.Rejected(HostPluginCleanupFailure.CONFIGURATION_UNPROVEN)
    }
}

internal fun admitHostRemovalOutput(
    observed: HostRemovalProcessObservation,
    plugin: Path,
): Refinement<Unit, HostPluginCleanupFailure> =
    when (observed) {
        is HostRemovalProcessObservation.Rejected -> Refinement.Rejected(observed.failure)
        is HostRemovalProcessObservation.Exited -> admitExitedHostRemoval(observed, plugin)
    }

private fun admitExitedHostRemoval(
    observed: HostRemovalProcessObservation.Exited,
    plugin: Path,
): Refinement<Unit, HostPluginCleanupFailure> {
    if (observed.output.toByteArray(Charsets.UTF_8).size > HOST_OUTPUT_LIMIT)
        return Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
    val reply =
        try {
            managementJson.decodeFromString<HostRemovalReply>(observed.output)
        } catch (_: SerializationException) {
            return Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
        }
    return admitHostReply(reply, observed.code, plugin)
}

private fun admitHostReply(
    reply: HostRemovalReply,
    code: Int,
    plugin: Path,
): Refinement<Unit, HostPluginCleanupFailure> =
    when (reply) {
        is HostRemovalReply.Removed ->
            if (code == 0 && reply.plugin == plugin.toString()) Refinement.Refined(Unit)
            else Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
        is HostRemovalReply.Rejected ->
            if (code == 1) Refinement.Rejected(reply.failure.cleanupFailure())
            else Refinement.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
    }

private inline fun <T> hostRemovalBoundary(
    action: () -> Refinement<T, HostPluginCleanupFailure>
): Refinement<T, HostPluginCleanupFailure> =
    try {
        action()
    } catch (_: java.io.IOException) {
        Refinement.Rejected(HostPluginCleanupFailure.FILESYSTEM_REJECTED)
    } catch (_: SecurityException) {
        Refinement.Rejected(HostPluginCleanupFailure.OWNERSHIP_UNPROVEN)
    }

@Serializable
private sealed interface HostRemovalReply {
    @Serializable @SerialName("REMOVED") data class Removed(val plugin: String) : HostRemovalReply

    @Serializable @SerialName("REJECTED") data class Rejected(val failure: HostOwnerFailure) : HostRemovalReply
}

@Serializable
private enum class HostOwnerFailure {
    PAYLOAD_REJECTED,
    RELEASE_REJECTED,
    COMPATIBILITY_REJECTED,
    OWNERSHIP_UNPROVEN,
    FILESYSTEM_REJECTED,
    RECOVERY_REQUIRED;

    fun cleanupFailure(): HostPluginCleanupFailure =
        when (this) {
            PAYLOAD_REJECTED -> HostPluginCleanupFailure.PAYLOAD_REJECTED
            RELEASE_REJECTED -> HostPluginCleanupFailure.RELEASE_REJECTED
            COMPATIBILITY_REJECTED -> HostPluginCleanupFailure.COMPATIBILITY_REJECTED
            OWNERSHIP_UNPROVEN -> HostPluginCleanupFailure.OWNERSHIP_UNPROVEN
            FILESYSTEM_REJECTED -> HostPluginCleanupFailure.FILESYSTEM_REJECTED
            RECOVERY_REQUIRED -> HostPluginCleanupFailure.RECOVERY_REQUIRED
        }
}

private fun rejectHostRemoval(
    failure: HostPluginCleanupFailure,
    observe: (UninstallCleanupObservation) -> Unit,
): Nothing {
    observe(UninstallCleanupObservation.HostRejected(failure))
    throw ManagementRejected("uninstall-host-plugin", "Host cleanup rejected: $failure; retained Control installation")
}

internal fun executeHostRemoval(arguments: List<String>): HostRemovalProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            return HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.PROCESS_UNAVAILABLE)
        } catch (_: SecurityException) {
            return HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.PROCESS_UNAVAILABLE)
        }
    val reader = Executors.newSingleThreadExecutor()
    return try {
        val output = reader.submit<ByteArray> { process.inputStream.use { it.readNBytes(HOST_OUTPUT_LIMIT + 1) } }
        val exited = process.waitFor(HOST_DEADLINE_SECONDS, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(HOST_TERMINATION_SECONDS, TimeUnit.SECONDS)
        }
        val bytes = output.get(HOST_TERMINATION_SECONDS, TimeUnit.SECONDS)
        if (bytes.size > HOST_OUTPUT_LIMIT)
            HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
        else if (!exited) HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.DEADLINE_EXCEEDED)
        else
            HostRemovalProcessObservation.Exited(
                process.exitValue(),
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.PROCESS_UNAVAILABLE)
    } catch (_: java.util.concurrent.TimeoutException) {
        HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.DEADLINE_EXCEEDED)
    } catch (_: java.util.concurrent.ExecutionException) {
        HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.PROCESS_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        HostRemovalProcessObservation.Rejected(HostPluginCleanupFailure.OUTPUT_REJECTED)
    } finally {
        if (process.isAlive) process.destroyForcibly()
        reader.shutdownNow()
    }
}

private const val HOST_HELPER = "share/kast/host-installation.py"
private const val SELECTED_IDE = "config/selected-ide.json"
private const val HOST_OUTPUT_LIMIT = 65536

private const val HOST_DEADLINE_SECONDS = 45L
private const val HOST_TERMINATION_SECONDS = 5L
