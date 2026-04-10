package io.github.amichne.kast.api

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class RegisteredDescriptor(
    val id: String,
    val descriptor: ServerInstanceDescriptor,
)

class DescriptorRegistry(
    private val daemonsFile: Path,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun list(): List<RegisteredDescriptor> {
        if (!daemonsFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val descriptors: List<ServerInstanceDescriptor> =
                json.decodeFromString(daemonsFile.readText())
            descriptors.map { d ->
                RegisteredDescriptor(
                    id = idFor(d),
                    descriptor = d,
                )
            }.sortedWith(compareBy({ it.descriptor.backendName }, { it.id }))
        }.getOrDefault(emptyList())
    }

    fun findByWorkspaceRoot(workspaceRoot: Path): List<RegisteredDescriptor> {
        val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize().toString()
        return list().filter { registered ->
            Path.of(registered.descriptor.workspaceRoot).toAbsolutePath().normalize().toString() == normalizedWorkspaceRoot
        }
    }

    fun register(descriptor: ServerInstanceDescriptor) {
        val current = list().map { it.descriptor }.toMutableList()
        val id = idFor(descriptor)
        current.removeAll { idFor(it) == id }
        current.add(descriptor)
        writeAtomically(current)
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        val id = idFor(descriptor)
        val current = list().map { it.descriptor }.toMutableList()
        current.removeAll { idFor(it) == id }
        writeAtomically(current)
    }

    private fun idFor(d: ServerInstanceDescriptor): String =
        "${d.workspaceRoot}:${d.backendName}:${d.pid}"

    private fun writeAtomically(descriptors: List<ServerInstanceDescriptor>) {
        if (descriptors.isEmpty()) {
            Files.deleteIfExists(daemonsFile)
            return
        }
        Files.createDirectories(daemonsFile.parent)
        val tempFile = Files.createTempFile(daemonsFile.parent, "daemons", ".tmp")
        try {
            tempFile.writeText(json.encodeToString(descriptors))
            Files.move(tempFile, daemonsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
    }
}
