package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.io.KastFileOperations
import io.github.amichne.kast.api.io.LocalDiskFileOperations
import kotlinx.serialization.json.Json
import java.nio.file.Path

data class RegisteredDescriptor(
    val id: String,
    val descriptor: ServerInstanceDescriptor,
)

class DescriptorRegistry {
    private val daemonsPath: String
    private val fileOps: KastFileOperations

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Constructor accepting String path and injectable KastFileOperations.
     * This is the primary constructor for testing and alternative filesystem implementations.
     */
    constructor(
        daemonsPath: String,
        fileOps: KastFileOperations = LocalDiskFileOperations
    ) {
        this.daemonsPath = daemonsPath
        this.fileOps = fileOps
    }

    /**
     * Backward-compatible constructor accepting Path.
     * Delegates to the primary constructor using LocalDiskFileOperations.
     */
    constructor(daemonsFile: Path) : this(
        daemonsPath = daemonsFile.toAbsolutePath().toString(),
        fileOps = LocalDiskFileOperations
    )

    /**
     * List all registered descriptors.
     *
     * This method is intentionally lock-free for reads. It is also called
     * from within withLock-guarded read-modify-write operations in
     * register() and delete() to read the current state before modification.
     *
     * @return List of registered descriptors sorted by backend name and id
     */
    fun list(): List<RegisteredDescriptor> {
        if (!fileOps.exists(daemonsPath)) {
            return emptyList()
        }

        return runCatching {
            val descriptors: List<ServerInstanceDescriptor> =
                json.decodeFromString(fileOps.readText(daemonsPath))
            descriptors.map { d ->
                RegisteredDescriptor(
                    id = idFor(d),
                    descriptor = d,
                )
            }.sortedWith(compareBy({ it.descriptor.backendName }, { it.id }))
        }.getOrDefault(emptyList())
    }

    /**
     * Find descriptors by workspace root using String path.
     */
    fun findByWorkspaceRoot(workspaceRoot: String): List<RegisteredDescriptor> {
        val normalizedWorkspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize().toString()
        return list().filter { registered ->
            Path.of(registered.descriptor.workspaceRoot).toAbsolutePath().normalize().toString() == normalizedWorkspaceRoot
        }
    }

    /**
     * Find descriptors by workspace root using Path (backward compatibility).
     */
    fun findByWorkspaceRoot(workspaceRoot: Path): List<RegisteredDescriptor> {
        return findByWorkspaceRoot(workspaceRoot.toAbsolutePath().toString())
    }

    fun register(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath) {
            val current = list().map { it.descriptor }.toMutableList()
            val id = idFor(descriptor)
            current.removeAll { idFor(it) == id }
            current.add(descriptor)
            writeAtomically(current)
        }
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath) {
            val id = idFor(descriptor)
            val current = list().map { it.descriptor }.toMutableList()
            current.removeAll { idFor(it) == id }
            writeAtomically(current)
        }
    }

    private fun idFor(d: ServerInstanceDescriptor): String =
        "${d.workspaceRoot}:${d.backendName}:${d.pid}"

    private fun writeAtomically(descriptors: List<ServerInstanceDescriptor>) {
        if (descriptors.isEmpty()) {
            fileOps.delete(daemonsPath)
            return
        }
        val tempFile = fileOps.createTempFile(daemonsPath)
        try {
            fileOps.writeText(tempFile, json.encodeToString(descriptors))
            fileOps.moveAtomic(tempFile, daemonsPath)
        } catch (e: Exception) {
            fileOps.delete(tempFile)
            throw e
        }
    }
}
