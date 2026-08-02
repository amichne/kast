package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class DescriptorRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun descriptor(
        workspaceRoot: String = "/tmp/workspace",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = RuntimeWorkspaceRoot.parse(workspaceRoot),
        backendVersion = RuntimeImplementationVersion("0.1.0"),
        socketPath = RuntimeSocketPath.of(Path.of("/tmp/workspace/.kast/s")),
        ownership = ServerInstanceOwnership.Legacy(ProcessId.of(pid)),
    )

    private fun registry(daemonsFile: Path): DescriptorRegistry =
        DescriptorRegistry(DescriptorRegistryPath.of(daemonsFile))

    private fun readDescriptors(daemonsFile: Path): List<ServerInstanceDescriptor> =
        json.decodeFromString(Files.readString(daemonsFile))

    private fun readElements(daemonsFile: Path): List<JsonElement> =
        (json.parseToJsonElement(Files.readString(daemonsFile)) as JsonArray).toList()

    private fun encoded(descriptor: ServerInstanceDescriptor): JsonElement =
        json.encodeToJsonElement(ServerInstanceDescriptor.serializer(), descriptor)

    private fun futureDescriptor(descriptor: ServerInstanceDescriptor): JsonElement = JsonObject(
        encoded(descriptor).jsonObject +
            ("schemaVersion" to JsonPrimitive(DescriptorSchemaVersion.CURRENT.value + 1)),
    )

    @Test
    fun `descriptor registry path rejects unnormalized or relative input`() {
        assertThrows(IllegalArgumentException::class.java) {
            DescriptorRegistryPath.parse("relative/daemons.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DescriptorRegistryPath.parse("/tmp/../tmp/daemons.json")
        }
        assertEquals(
            "/tmp/daemons.json",
            DescriptorRegistryPath.of(Path.of("/tmp/../tmp/daemons.json")).value,
        )
    }

    @Test
    fun `register persists a single descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)

        assertEquals(listOf(d), readDescriptors(daemonsFile))
    }

    @Test
    fun `register is idempotent for same workspace-backend-pid`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.register(d)
        assertEquals(listOf(d), readDescriptors(daemonsFile))
    }

    @Test
    fun `delete removes matching descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.delete(d)
        assertFalse(daemonsFile.exists())
    }

    @Test
    fun `registry keeps valid descriptors when another array element is invalid`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val validDescriptor = descriptor()
        val validJson = json.encodeToString(ServerInstanceDescriptor.serializer(), validDescriptor)
        val invalidJson = validJson.replace("\"indexer\"", "\"idea\"")
        Files.writeString(daemonsFile, "[$validJson,$invalidJson]")

        assertEquals(listOf(validDescriptor), registry(daemonsFile).descriptors())
    }

    @Test
    fun `register preserves an opaque future descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val newDescriptor = descriptor(workspaceRoot = "/tmp/other-workspace", pid = 43L)
        val futureDescriptor = futureDescriptor(descriptor())
        Files.writeString(daemonsFile, JsonArray(listOf(futureDescriptor)).toString())

        registry(daemonsFile).register(newDescriptor)

        val elements = readElements(daemonsFile)
        assertEquals(futureDescriptor, elements.first())
        assertEquals(
            newDescriptor,
            json.decodeFromJsonElement(ServerInstanceDescriptor.serializer(), elements.last()),
        )
    }

    @Test
    fun `delete preserves an opaque future descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val deletedDescriptor = descriptor(workspaceRoot = "/tmp/deleted-workspace", pid = 43L)
        val futureDescriptor = futureDescriptor(descriptor())
        Files.writeString(
            daemonsFile,
            JsonArray(listOf(futureDescriptor, encoded(deletedDescriptor))).toString(),
        )

        registry(daemonsFile).delete(deletedDescriptor)

        assertEquals(listOf(futureDescriptor), readElements(daemonsFile))
    }

    @Test
    fun `register preserves a legacy descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val legacyDescriptor = descriptor()
        val newDescriptor = descriptor(workspaceRoot = "/tmp/other-workspace", pid = 43L)
        Files.writeString(daemonsFile, JsonArray(listOf(encoded(legacyDescriptor))).toString())

        registry(daemonsFile).register(newDescriptor)

        assertEquals(listOf(legacyDescriptor, newDescriptor), readDescriptors(daemonsFile))
    }

    @Test
    fun `delete preserves an unrelated legacy descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val legacyDescriptor = descriptor()
        val deletedDescriptor = descriptor(workspaceRoot = "/tmp/deleted-workspace", pid = 43L)
        Files.writeString(
            daemonsFile,
            JsonArray(listOf(encoded(legacyDescriptor), encoded(deletedDescriptor))).toString(),
        )

        registry(daemonsFile).delete(deletedDescriptor)

        assertEquals(listOf(legacyDescriptor), readDescriptors(daemonsFile))
    }

    @Test
    fun `malformed or non-array registry roots fail closed without changing bytes`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val roots = listOf("not-json", "{}")

        roots.forEach { root ->
            Files.writeString(daemonsFile, root)

            assertThrows(IllegalStateException::class.java) {
                registry.register(descriptor(pid = 43L))
            }
            assertEquals(root, Files.readString(daemonsFile))

            assertThrows(IllegalStateException::class.java) {
                registry.delete(descriptor())
            }
            assertEquals(root, Files.readString(daemonsFile))
        }
    }
}
