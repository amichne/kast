package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

private const val MAXIMUM_CONNECTION_CONFIG_BYTES = 65536L
private const val MAXIMUM_CONNECTION_DIRECTORY_CHARACTERS = 4096
private const val FIRST_PRINTABLE_CHARACTER = 32

internal enum class ConnectionConfigurationFailure(val explanation: String) {
    DIRECTORY_INVALID("connection directory must be an absolute normalized path of at most 4096 characters"),
    DOCUMENT_INVALID("connection configuration is invalid"),
    RECOVERY_UNAVAILABLE(
        "connection configuration and its verified checkpoint are unavailable; " +
            "reconnect with --destination to rebuild Kast preferences while retaining the rejected config"
    ),
}

internal sealed interface ConnectionDirectoryAdmission {
    data class Admitted(val directory: ConnectionDirectory) : ConnectionDirectoryAdmission

    data class Rejected(val failure: ConnectionConfigurationFailure) : ConnectionDirectoryAdmission
}

/** Syntactic proof only. Registration separately admits physical path ownership before effects. */
@JvmInline
internal value class ConnectionDirectory private constructor(val path: Path) {
    companion object {
        fun admit(raw: String): ConnectionDirectoryAdmission {
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
                }
            if (
                raw.length !in 1..MAXIMUM_CONNECTION_DIRECTORY_CHARACTERS ||
                    raw.any { it.code < FIRST_PRINTABLE_CHARACTER }
            )
                return ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
            return if (!path.isAbsolute || path.normalize() != path || path.toString() != raw)
                ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
            else ConnectionDirectoryAdmission.Admitted(ConnectionDirectory(path))
        }
    }
}

@Serializable internal data class SavedConnectionDirectory(val type: HarnessConnection, val directory: String)

@Serializable
internal data class ConnectionConfigurationDocument(
    val schemaVersion: Int,
    val connections: List<SavedConnectionDirectory>,
)

internal sealed interface ConnectionConfigurationAdmission {
    data class Admitted(val configuration: ConnectionConfiguration) : ConnectionConfigurationAdmission

    data class Rejected(val failure: ConnectionConfigurationFailure) : ConnectionConfigurationAdmission
}

/** Each connection has at most one admitted directory; raw persisted paths never reach effects. */
internal class ConnectionConfiguration
private constructor(private val directories: Map<HarnessConnection, ConnectionDirectory>) {
    fun directory(connection: HarnessConnection): ConnectionDirectory? = directories[connection]

    fun selecting(connection: HarnessConnection, directory: ConnectionDirectory): ConnectionConfiguration =
        ConnectionConfiguration(directories + (connection to directory))

    fun document(): ConnectionConfigurationDocument =
        ConnectionConfigurationDocument(
            1,
            HarnessConnection.entries.mapNotNull { connection ->
                directories[connection]?.let { SavedConnectionDirectory(connection, it.path.toString()) }
            },
        )

    companion object {
        fun empty(): ConnectionConfiguration = ConnectionConfiguration(emptyMap())

        fun decode(raw: String): ConnectionConfigurationAdmission {
            val document =
                try {
                    managementJson.decodeFromString<ConnectionConfigurationDocument>(raw)
                } catch (_: SerializationException) {
                    return ConnectionConfigurationAdmission.Rejected(ConnectionConfigurationFailure.DOCUMENT_INVALID)
                } catch (_: IllegalArgumentException) {
                    return ConnectionConfigurationAdmission.Rejected(ConnectionConfigurationFailure.DOCUMENT_INVALID)
                }
            if (
                document.schemaVersion != 1 ||
                    document.connections.size > HarnessConnection.entries.size ||
                    document.connections.map { it.type }.toSet().size != document.connections.size
            )
                return ConnectionConfigurationAdmission.Rejected(ConnectionConfigurationFailure.DOCUMENT_INVALID)
            val directories = mutableMapOf<HarnessConnection, ConnectionDirectory>()
            for (connection in document.connections) {
                when (val admitted = ConnectionDirectory.admit(connection.directory)) {
                    is ConnectionDirectoryAdmission.Admitted -> directories[connection.type] = admitted.directory
                    is ConnectionDirectoryAdmission.Rejected ->
                        return ConnectionConfigurationAdmission.Rejected(admitted.failure)
                }
            }
            return ConnectionConfigurationAdmission.Admitted(ConnectionConfiguration(directories.toMap()))
        }
    }
}

internal fun connectionConfigurationPath(home: Path): Path = home.resolve(".config/kast/config.json")

internal fun connectionCheckpointPath(home: Path): Path = home.resolve(".config/kast/config.last-good.json")

internal sealed interface ConnectionConfigurationRead {
    data class Read(val configuration: ConnectionConfiguration) : ConnectionConfigurationRead

    data class Recovered(val configuration: ConnectionConfiguration) : ConnectionConfigurationRead

    data class Rejected(val failure: ConnectionConfigurationFailure) : ConnectionConfigurationRead
}

internal fun readConnectionConfiguration(home: Path): ConnectionConfigurationRead {
    val path = connectionConfigurationPath(home)
    requireRegistrationPath(path, home)
    val checkpoint = connectionCheckpointPath(home)
    requireRegistrationPath(checkpoint, home)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS))
        return ConnectionConfigurationRead.Read(ConnectionConfiguration.empty())
    val raw = readBoundedFile(path, MAXIMUM_CONNECTION_CONFIG_BYTES)
    val admitted = raw?.let(ConnectionConfiguration::decode)
    if (admitted is ConnectionConfigurationAdmission.Admitted)
        return ConnectionConfigurationRead.Read(admitted.configuration)
    val retained = readBoundedFile(checkpoint, MAXIMUM_CONNECTION_CONFIG_BYTES)?.let(ConnectionConfiguration::decode)
    return when (retained) {
        is ConnectionConfigurationAdmission.Admitted -> ConnectionConfigurationRead.Recovered(retained.configuration)
        is ConnectionConfigurationAdmission.Rejected,
        null -> ConnectionConfigurationRead.Rejected(ConnectionConfigurationFailure.RECOVERY_UNAVAILABLE)
    }
}

internal fun encodeConnectionConfiguration(configuration: ConnectionConfiguration): String =
    managementJson.encodeToString(configuration.document()) + "\n"

private fun admitRecordedFileDirectory(
    root: Path,
    connection: HarnessConnection,
    prior: ManagedRegistration,
): ConnectionDirectoryAdmission {
    val destination =
        when (val admitted = ConnectionDirectory.admit(prior.destination)) {
            is ConnectionDirectoryAdmission.Admitted -> admitted.directory.path
            is ConnectionDirectoryAdmission.Rejected -> return admitted
        }
    val directory =
        when (connection) {
            HarnessConnection.PI -> destination.parent?.parent
            HarnessConnection.COPILOT -> destination.parent?.parent?.parent
            HarnessConnection.CODEX_APP_SERVER -> destination.parent
            HarnessConnection.CODEX_MCP ->
                return ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
        } ?: return ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
    return when (val admitted = ConnectionDirectory.admit(directory.toString())) {
        is ConnectionDirectoryAdmission.Admitted ->
            if (registrationDestinationFor(root, connection, admitted.directory) == destination) admitted
            else ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.DIRECTORY_INVALID)
        is ConnectionDirectoryAdmission.Rejected -> admitted
    }
}

internal fun recordedFileDirectory(
    root: Path,
    connection: HarnessConnection,
    prior: ManagedRegistration,
): ConnectionDirectory =
    when (val admitted = admitRecordedFileDirectory(root, connection, prior)) {
        is ConnectionDirectoryAdmission.Admitted -> admitted.directory
        is ConnectionDirectoryAdmission.Rejected ->
            throw ManagementRejected("connection-destination", admitted.failure.explanation)
    }

internal fun admittedConnectionDirectory(raw: String): ConnectionDirectory =
    when (val admitted = ConnectionDirectory.admit(raw)) {
        is ConnectionDirectoryAdmission.Admitted -> admitted.directory
        is ConnectionDirectoryAdmission.Rejected ->
            throw ManagementRejected("connection-destination", admitted.failure.explanation)
    }

internal fun connectionDirectoryFor(
    root: Path,
    home: Path,
    connection: HarnessConnection,
    configuration: ConnectionConfiguration,
    environment: Map<String, String>,
    requested: ConnectionDirectory?,
    codexHome: Path,
    prior: ManagedRegistration?,
): ConnectionDirectory {
    requested?.let {
        return it
    }
    configuration.directory(connection)?.let {
        return it
    }
    prior?.directory?.let {
        return admittedConnectionDirectory(it)
    }
    // A pre-config receipt already proves the file location. Keep it through environment changes.
    if (prior != null && connection != HarnessConnection.CODEX_MCP) {
        return recordedFileDirectory(root, connection, prior)
    }
    val raw =
        when (connection) {
            HarnessConnection.PI -> environment["PI_CODING_AGENT_DIR"] ?: home.resolve(".pi/agent").toString()
            HarnessConnection.COPILOT -> environment["COPILOT_HOME"] ?: home.resolve(".copilot").toString()
            HarnessConnection.CODEX_MCP -> environment["CODEX_HOME"] ?: codexHome.toString()
            HarnessConnection.CODEX_APP_SERVER -> home.resolve(".local/bin").toString()
        }
    return admittedConnectionDirectory(raw)
}

internal fun recoverConnectionConfiguration(
    root: Path,
    receipt: ManagementReceipt,
): ConnectionConfigurationAdmission {
    if (receipt.registrations.isEmpty())
        return ConnectionConfigurationAdmission.Rejected(ConnectionConfigurationFailure.RECOVERY_UNAVAILABLE)
    var recovered = ConnectionConfiguration.empty()
    for (registration in receipt.registrations) {
        val admission =
            when {
                registration.directory != null -> ConnectionDirectory.admit(registration.directory)
                registration.connection != HarnessConnection.CODEX_MCP ->
                    admitRecordedFileDirectory(root, registration.connection, registration)
                else -> ConnectionDirectoryAdmission.Rejected(ConnectionConfigurationFailure.RECOVERY_UNAVAILABLE)
            }
        when (admission) {
            is ConnectionDirectoryAdmission.Admitted ->
                recovered = recovered.selecting(registration.connection, admission.directory)
            is ConnectionDirectoryAdmission.Rejected ->
                return ConnectionConfigurationAdmission.Rejected(admission.failure)
        }
    }
    return ConnectionConfigurationAdmission.Admitted(recovered)
}
