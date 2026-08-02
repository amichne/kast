package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.testing.InMemoryFileOperationsFixture
import io.github.amichne.kast.testing.inMemoryFileOperations
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescriptorRegistryJimfsTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun descriptor(
        workspaceRoot: String = "/workspace",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = RuntimeWorkspaceRoot.parse(workspaceRoot),
        backendVersion = RuntimeImplementationVersion("0.1.0"),
        socketPath = RuntimeSocketPath.parse("$workspaceRoot/.kast/s"),
        ownership = ServerInstanceOwnership.Legacy(ProcessId.of(pid)),
    )

    private fun readDescriptors(path: String, fixture: InMemoryFileOperationsFixture): List<ServerInstanceDescriptor> =
        json.decodeFromString(fixture.fileOps.readText(path))

    @Test
    fun `registry accepts KastFileOperations and operates in Jimfs`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"
        val registry = DescriptorRegistry(
            daemonsPath = DescriptorRegistryPath.parse(daemonsPath),
            fileOps = fixture.fileOps,
        )
        val d1 = descriptor(workspaceRoot = "${fixture.root}workspace-a")
        val d2 = descriptor(workspaceRoot = "${fixture.root}workspace-b", pid = 43L)

        registry.register(d1)
        registry.register(d2)

        assertEquals(listOf(d1, d2), readDescriptors(daemonsPath, fixture))
        assertTrue(
            fixture.fileOps.exists(daemonsPath),
            "daemons.json should exist in Jimfs memory",
        )

        registry.delete(d1)
        assertEquals(listOf(d2), readDescriptors(daemonsPath, fixture))
    }

    @Test
    fun `atomic writes use fileOps abstraction with Jimfs`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"
        val registry = DescriptorRegistry(
            daemonsPath = DescriptorRegistryPath.parse(daemonsPath),
            fileOps = fixture.fileOps,
        )
        val d = descriptor(workspaceRoot = "${fixture.root}workspace")

        registry.register(d)

        assertTrue(
            fixture.fileOps.exists(daemonsPath),
            "Atomic write should create file in Jimfs",
        )
        val parentDir = "${fixture.root}home/user/.kast"
        val files = fixture.fileOps.list(parentDir)
        assertEquals(
            1,
            files.size,
            "No temp files should leak after atomic write (only daemons.json)",
        )
    }
}
