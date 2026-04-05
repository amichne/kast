package io.github.amichne.kast.api

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

data class RegisteredDescriptor(
    val path: Path,
    val descriptor: ServerInstanceDescriptor,
)

class DescriptorRegistry(
    private val directory: Path,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun list(): List<RegisteredDescriptor> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }

        return Files.list(directory).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { path -> Files.isRegularFile(path) && path.extension == "json" }
                .mapNotNull { path ->
                    runCatching {
                        RegisteredDescriptor(
                            path = path,
                            descriptor = json.decodeFromString(ServerInstanceDescriptor.serializer(), path.readText()),
                        )
                    }.getOrNull()
                }
                .sortedWith(compareBy({ it.descriptor.backendName }, { it.path.name }))
                .toList()
        }
    }

    fun findByWorkspaceRoot(workspaceRoot: Path): List<RegisteredDescriptor> {
        val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize().toString()
        return list().filter { registered ->
            Path.of(registered.descriptor.workspaceRoot).toAbsolutePath().normalize().toString() == normalizedWorkspaceRoot
        }
    }

    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }
}
