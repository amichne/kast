package io.github.amichne.kast.indexer

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val ENDPOINT_SCHEMA = "kast.runtime.endpoint.v1"
private const val ENDPOINT_FRAMING = "length-prefixed-json-v1"

internal sealed interface EndpointDescriptorPublication {
    data class Published(
        val path: Path,
    ) : EndpointDescriptorPublication

    data object Rejected : EndpointDescriptorPublication
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

internal fun deleteEndpointDescriptor(path: Path) {
    deleteDescriptorFile(path)
}

private fun IndexerLaunchOptions.endpointDescriptorDocument(): String = buildString {
    append('{')
    appendJsonField("schema", ENDPOINT_SCHEMA)
    append(',')
    appendJsonField("canonicalRoot", workspaceRoot.toString())
    append(',')
    appendJsonField("runtimeId", runtimeId.value)
    append(',')
    appendJsonField("socketPath", socketPath.toString())
    append(',')
    appendJsonField("framing", ENDPOINT_FRAMING)
    append('}')
}

private fun StringBuilder.appendJsonField(
    name: String,
    value: String,
) {
    append('"')
    append(name)
    append("\":\"")
    append(value.jsonEscaped())
    append('"')
}

private fun String.jsonEscaped(): String = buildString(length) {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

private fun deleteDescriptorFile(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}
