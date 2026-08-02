package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.io.KastFileOperations
import io.github.amichne.kast.api.io.LocalDiskFileOperations
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@JvmInline
value class DescriptorRegistryPath private constructor(val value: String) {
    fun toPath(): Path = Path.of(value)

    companion object {
        fun of(path: Path): DescriptorRegistryPath = DescriptorRegistryPath(
            path.toAbsolutePath().normalize().toString(),
        )

        fun parse(value: String): DescriptorRegistryPath {
            require(value.isNotBlank()) { "Descriptor registry path must not be blank" }
            require(value.none(Char::isISOControl)) {
                "Descriptor registry path must not contain control characters"
            }
            val path = Path.of(value)
            require(path.isAbsolute) { "Descriptor registry path must be absolute" }
            require(path.normalize().toString() == value) { "Descriptor registry path must be normalized" }
            return DescriptorRegistryPath(value)
        }
    }
}

class DescriptorRegistry(
    private val daemonsPath: DescriptorRegistryPath,
    private val fileOps: KastFileOperations = LocalDiskFileOperations,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun register(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath.value) {
            val current = readElements().toMutableList()
            val id = idFor(descriptor)
            current.removeAll { element ->
                decodeDescriptorOrNull(element)?.let(::idFor) == id
            }
            current.add(json.encodeToJsonElement(ServerInstanceDescriptor.serializer(), descriptor))
            writeAtomically(current)
        }
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath.value) {
            val id = idFor(descriptor)
            val current = readElements().toMutableList()
            current.removeAll { element ->
                decodeDescriptorOrNull(element)?.let(::idFor) == id
            }
            writeAtomically(current)
        }
    }

    fun descriptors(): List<ServerInstanceDescriptor> =
        fileOps.withLock(daemonsPath.value) { readElements().mapNotNull(::decodeDescriptorOrNull) }

    private fun readElements(): List<JsonElement> {
        if (!fileOps.exists(daemonsPath.value)) {
            return emptyList()
        }

        val root = try {
            json.parseToJsonElement(fileOps.readText(daemonsPath.value))
        } catch (e: SerializationException) {
            throw DescriptorRegistryFormatException("Descriptor registry must contain a JSON array", e)
        }
        if (root !is JsonArray) {
            throw DescriptorRegistryFormatException("Descriptor registry must contain a JSON array")
        }
        return root
    }

    private fun decodeDescriptorOrNull(element: JsonElement): ServerInstanceDescriptor? =
        try {
            json.decodeFromJsonElement(ServerInstanceDescriptor.serializer(), element)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun idFor(descriptor: ServerInstanceDescriptor): String = when (val owner = descriptor.ownership) {
        is ServerInstanceOwnership.Owned -> owner.runtimeInstanceId.value
        is ServerInstanceOwnership.Legacy ->
            "${descriptor.workspaceRoot.value}:${descriptor.backendName.value}:${owner.processId.value}"
        ServerInstanceOwnership.LegacyWithoutProcessId ->
            "${descriptor.workspaceRoot.value}:${descriptor.backendName.value}:legacy:${descriptor.socketPath.value}"
    }

    private fun writeAtomically(elements: List<JsonElement>) {
        if (elements.isEmpty()) {
            fileOps.delete(daemonsPath.value)
            return
        }
        val tempFile = fileOps.createTempFile(daemonsPath.value)
        try {
            fileOps.writeText(tempFile, json.encodeToString(JsonArray(elements)))
            fileOps.moveAtomic(tempFile, daemonsPath.value)
        } catch (e: Exception) {
            fileOps.delete(tempFile)
            throw e
        }
    }
}

class DescriptorRegistryFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
