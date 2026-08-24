package io.github.amichne.kast.indexer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val ENDPOINT_SCHEMA = "kast.runtime.endpoint.v1"
private const val ENDPOINT_FRAMING = "length-prefixed-json-v1"

private val endpointJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

/** Fixed installed endpoint schema encoded by its compiler-generated serializer. */
@Serializable
internal data class IndexerEndpointDescriptorDocument(
    val schema: String,
    val canonicalRoot: String,
    val runtimeId: String,
    val socketPath: String,
    val framing: String,
)

internal sealed interface EndpointDescriptorPublication {
    data class Published(
        val path: Path,
    ) : EndpointDescriptorPublication

    data object Rejected : EndpointDescriptorPublication
}

internal sealed interface EndpointDescriptorRetirement {
    data object Retired : EndpointDescriptorRetirement
    data object Rejected : EndpointDescriptorRetirement
}

/**
 * Proof transition: `IndexerLaunchOptions -> EndpointDescriptorPublication`.
 *
 * Establishes one atomically published, versioned descriptor for the bound exact-root runtime.
 * [EndpointDescriptorPublication.Rejected] is the closed expected filesystem failure. Raw launch
 * paths and identity text leave only in the descriptor document at this installed-host boundary.
 */
internal fun publishEndpointDescriptor(
    options: IndexerLaunchOptions,
): EndpointDescriptorPublication {
    val descriptor = options.socketPath.endpointDescriptorPath()
    val parent = descriptor.parent ?: return EndpointDescriptorPublication.Rejected
    val temporary = try {
        Files.createTempFile(parent, ".${descriptor.fileName}.", ".tmp")
    } catch (_: IOException) {
        return EndpointDescriptorPublication.Rejected
    } catch (_: SecurityException) {
        return EndpointDescriptorPublication.Rejected
    }
    return try {
        Files.writeString(
            temporary,
            options.endpointDescriptorDocument(),
            StandardCharsets.UTF_8,
        )
        try {
            Files.move(
                temporary,
                descriptor,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, descriptor, StandardCopyOption.REPLACE_EXISTING)
        }
        EndpointDescriptorPublication.Published(descriptor)
    } catch (_: IOException) {
        deleteDescriptorFile(temporary)
        EndpointDescriptorPublication.Rejected
    } catch (_: SecurityException) {
        deleteDescriptorFile(temporary)
        EndpointDescriptorPublication.Rejected
    }
}

internal fun Path.endpointDescriptorPath(): Path =
    resolveSibling("${fileName}.endpoint.json")

/**
 * Proof transition: `Path -> EndpointDescriptorRetirement`.
 *
 * [EndpointDescriptorRetirement.Retired] establishes absence of the exact stale descriptor before
 * a new runtime construction attempt. [EndpointDescriptorRetirement.Rejected] closes inaccessible
 * filesystem state. The raw path leaves only at the filesystem boundary.
 */
internal fun retireEndpointDescriptor(path: Path): EndpointDescriptorRetirement = try {
    Files.deleteIfExists(path)
    EndpointDescriptorRetirement.Retired
} catch (_: IOException) {
    EndpointDescriptorRetirement.Rejected
} catch (_: SecurityException) {
    EndpointDescriptorRetirement.Rejected
}

internal fun deleteEndpointDescriptor(path: Path) {
    deleteDescriptorFile(path)
}

private fun IndexerLaunchOptions.endpointDescriptorDocument(): String =
    endpointJson.encodeToString(
        IndexerEndpointDescriptorDocument.serializer(),
        IndexerEndpointDescriptorDocument(
            schema = ENDPOINT_SCHEMA,
            canonicalRoot = workspaceRoot.toString(),
            runtimeId = runtimeId.value,
            socketPath = socketPath.toString(),
            framing = ENDPOINT_FRAMING,
        ),
    )

private fun deleteDescriptorFile(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}
