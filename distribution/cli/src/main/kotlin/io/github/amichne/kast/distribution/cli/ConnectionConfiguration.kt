package io.github.amichne.kast.distribution.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

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

internal sealed interface ConnectionConfigurationRead {
    data class Read(val configuration: ConnectionConfiguration) : ConnectionConfigurationRead

    data class Recovered(val configuration: ConnectionConfiguration) : ConnectionConfigurationRead

    data class Rejected(val failure: ConnectionConfigurationFailure) : ConnectionConfigurationRead
}
