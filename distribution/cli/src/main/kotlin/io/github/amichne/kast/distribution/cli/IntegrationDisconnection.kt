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
        val receipt = admittedReceipt(root)
        val prior =
            receipt.registrations.singleOrNull { it.connection == connection } ?: return@withRegistrationLock false
        val directory = disconnectionDirectory(root, home, prior)
        val destination = registrationDestinationFor(root, connection, directory)
        if (prior.destination != destination.toString())
            throw ManagementRejected("disconnect", "recorded registration identity changed")
        if (connection == HarnessConnection.CODEX_MCP) {
            requireRegistrationPath(directory.path.resolve("config.toml"), directory.path)
            val execute: (List<String>) -> ProcessObservation = { arguments ->
                executeCodexAt(directory.path, arguments)
            }
            val observed = codexRegistration(execute)
            if (observed != null && !ownsCodex(observed, destination))
                throw ManagementRejected("disconnect", "Codex name belongs to another configuration")
            if (observed != null) {
                val remove = execute(listOf("codex", "mcp", "remove", "kast"))
                if (remove !is ProcessObservation.Exited || remove.code != 0 || codexRegistration(execute) != null)
                    throw ManagementRejected("disconnect", "Codex removal was not verified")
            }
        } else {
            requireRegistrationPath(destination, if (destination.startsWith(home)) home else directory.path)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (
                    !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ||
                        sha256(destination) != prior.payloadSha256
                )
                    throw ManagementRejected("disconnect", "integration file is no longer owned")
                Files.delete(destination)
            }
        }
        writeManagementReceipt(
            root,
            receipt.copy(registrations = receipt.registrations.filterNot { it.connection == connection }),
        )
        true
    }

private fun disconnectionDirectory(root: Path, home: Path, registration: ManagedRegistration): ConnectionDirectory {
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
