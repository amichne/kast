package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.io.KastFileOperations
import io.github.amichne.kast.api.io.LocalDiskFileOperations
import kotlinx.serialization.json.Json

class DescriptorRegistry(
    private val daemonsPath: String,
    private val fileOps: KastFileOperations = LocalDiskFileOperations,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun register(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath) {
            val current = readDescriptors().toMutableList()
            val id = idFor(descriptor)
            current.removeAll { idFor(it) == id }
            current.add(descriptor)
            writeAtomically(current)
        }
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        fileOps.withLock(daemonsPath) {
            val id = idFor(descriptor)
            val current = readDescriptors().toMutableList()
            current.removeAll { idFor(it) == id }
            writeAtomically(current)
        }
    }

    private fun readDescriptors(): List<ServerInstanceDescriptor> {
        if (!fileOps.exists(daemonsPath)) {
            return emptyList()
        }

        return runCatching {
            json.decodeFromString<List<ServerInstanceDescriptor>>(fileOps.readText(daemonsPath))
        }.getOrDefault(emptyList())
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
