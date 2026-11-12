package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectHarness(
    root: Path,
    home: Path,
    harness: Harness,
    executeCodexAt: (Path, List<String>) -> ProcessObservation = { selected, arguments ->
        runBounded(arguments, selected)
    },
): Boolean =
    HarnessConnection.entries
        .filter { it.harness == harness }
        .map { disconnectConnection(root, home, it, executeCodexAt) }
        .any { it }

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectConnection(
    root: Path,
    home: Path,
    connection: HarnessConnection,
    executeCodexAt: (Path, List<String>) -> ProcessObservation = { selected, arguments ->
        runBounded(arguments, selected)
    },
): Boolean =
    withRegistrationLock(root) {
        disconnectAdmittedConnection(root, home, connection, executeCodexAt)
    }

/** The caller holds the registration lock for the complete connection transaction. */
@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun disconnectAdmittedConnection(
    root: Path,
    home: Path,
    connection: HarnessConnection,
    executeCodexAt: (Path, List<String>) -> ProcessObservation,
): Boolean {
    val receipt = admittedReceipt(root)
    val prior = receipt.registrations.singleOrNull { it.connection == connection } ?: return false
    val directory = disconnectionDirectory(root, home, prior)
    val destination = registrationDestinationFor(root, connection, directory)
    if (prior.destination != destination.toString())
        throw ManagementRejected("disconnect", "recorded registration identity changed")
    if (connection == HarnessConnection.CODEX_MCP) {
        disconnectCodexEntry(directory, destination, executeCodexAt)
    } else {
        disconnectOwnedFile(home, directory, destination, prior)
    }
    writeManagementReceipt(
        root,
        receipt.copy(registrations = receipt.registrations.filterNot { it.connection == connection }),
    )
    return true
}

private fun disconnectCodexEntry(
    directory: ConnectionDirectory,
    destination: Path,
    executeCodexAt: (Path, List<String>) -> ProcessObservation,
) {
    requireRegistrationPath(directory.path.resolve("config.toml"), directory.path)
    val execute: (List<String>) -> ProcessObservation = { arguments -> executeCodexAt(directory.path, arguments) }
    val observed = codexRegistration(execute)
    if (observed != null && !ownsCodex(observed, destination))
        throw ManagementRejected("disconnect", "Codex name belongs to another configuration")
    if (observed != null) {
        val remove = execute(listOf("codex", "mcp", "remove", "kast"))
        // A provider can expose another transport after it removes the exact prior registration.
        if (
            remove !is ProcessObservation.Exited ||
                remove.code != 0 ||
                codexRegistration(execute)?.let { ownsCodex(it, destination) } == true
        )
            throw ManagementRejected("disconnect", "Codex removal was not verified")
    }
}

private fun disconnectOwnedFile(
    home: Path,
    directory: ConnectionDirectory,
    destination: Path,
    prior: ManagedRegistration,
) {
    requireRegistrationPath(destination, if (destination.startsWith(home)) home else directory.path)
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || sha256(destination) != prior.payloadSha256)
            throw ManagementRejected("disconnect", "integration file is no longer owned")
        Files.delete(destination)
    }
}

internal fun disconnectionDirectory(root: Path, home: Path, registration: ManagedRegistration): ConnectionDirectory {
    registration.directory?.let {
        return admittedConnectionDirectory(it)
    }
    if (registration.connection != HarnessConnection.CODEX_MCP)
        return recordedFileDirectory(root, registration.connection, registration)
    val configuration =
        when (val read = readConnectionConfiguration(home)) {
            is ConnectionConfigurationRead.Read -> read.configuration
            is ConnectionConfigurationRead.Recovered -> read.configuration
            is ConnectionConfigurationRead.Rejected ->
                throw ManagementRejected("disconnect-configuration", read.failure.explanation)
        }
    return connectionDirectoryFor(
        root,
        home,
        registration.connection,
        configuration,
        emptyMap(),
        null,
        home.resolve(".codex"),
        registration,
    )
}

internal fun removeRecordedGuidance(root: Path, harness: Harness): PluginObservation<Unit> {
    val receipt = admittedReceipt(root)
    for (guidance in receipt.guidance.filter { it.harness == harness }) {
        when (val removed = removeGuidanceDirectory(guidance)) {
            is PluginObservation.Rejected -> return removed
            is PluginObservation.Observed -> Unit
        }
    }
    writeManagementReceipt(root, receipt.copy(guidance = receipt.guidance.filterNot { it.harness == harness }))
    return PluginObservation.Observed(Unit)
}

private fun removeGuidanceDirectory(guidance: ManagedGuidance): PluginObservation<Unit> {
    val directory = Path.of(guidance.destination)
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return PluginObservation.Observed(Unit)
    val inventory =
        when (val read = guidanceInventory(directory)) {
            is GuidanceInventoryRead.Rejected -> return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
            is GuidanceInventoryRead.Read -> read
        }
    val expected = guidance.files.associateBy { it.path }
    val directories = expectedSkillDirectories(guidance.files.map { it.path })
    if (inventory.files.any { expected[it.path] != it } || !directories.containsAll(inventory.directories))
        return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
    for (file in inventory.files) {
        val path = directory.resolve(file.path)
        if (
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                path.toRealPath() != path ||
                sha256(path) != file.sha256
        )
            return PluginObservation.Rejected(PluginInstallFailure.PluginConflict)
        Files.delete(path)
    }
    for (relative in inventory.directories.filter { it.isNotEmpty() }.sortedByDescending { Path.of(it).nameCount }) {
        Files.delete(directory.resolve(relative))
    }
    Files.delete(directory)
    return PluginObservation.Observed(Unit)
}
