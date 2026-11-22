package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedHostBridgeTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `zero-project Host inspection binds process incarnation and selected profile`() {
        val fixture = fixture()
        val commands = mutableListOf<IdeLifecycleCommand>()
        val result =
            executeManagedHost(
                ManagedHostRequest.Inspect(fixture.idea.toString(), fixture.profile.toString()),
                fixture.home,
                processes = { ManagedProcessInventory.Captured(listOf(fixture.process)) },
                exchange = { _, command ->
                    commands += command
                    fixture.inspected
                },
            )
        assertEquals(ManagedHostResponse.Inspected(listOf(fixture.process), fixture.inspected), result)
        assertEquals(listOf(IdeLifecycleCommand.Inspect), commands)
        assertTrue(fixture.inspected.projects.isEmpty())
    }

    @Test
    fun `a reused PID or foreign profile rejects before a Host effect`() {
        val fixture = fixture()
        for (changed in
            listOf(
                fixture.inspected.copy(runtimeIdentity = fixture.identity.copy(processStartEpochMillis = 999)),
                fixture.inspected.copy(
                    runtimeIdentity = fixture.identity.copy(profile = temporary.resolve("foreign").toString())
                ),
            )) {
            val commands = mutableListOf<IdeLifecycleCommand>()
            val result =
                executeManagedHost(
                    ManagedHostRequest.Settle(
                        fixture.idea.toString(),
                        fixture.profile.toString(),
                        fixture.identity.hostLifetime,
                        "update",
                    ),
                    fixture.home,
                    processes = { ManagedProcessInventory.Captured(listOf(fixture.process)) },
                    exchange = { _, command ->
                        commands += command
                        changed
                    },
                )
            assertEquals(ManagedHostResponse.Rejected(ManagedHostFailure.HOST_IDENTITY_MISMATCH, changed), result)
            assertEquals(listOf(IdeLifecycleCommand.Inspect), commands)
        }
    }

    @Test
    fun `multiple IDEA instances reject before Host inspection`() {
        val fixture = fixture()
        val result =
            executeManagedHost(
                ManagedHostRequest.Inspect(fixture.idea.toString(), fixture.profile.toString()),
                fixture.home,
                processes = {
                    ManagedProcessInventory.Captured(listOf(fixture.process, fixture.process.copy(pid = 43)))
                },
                exchange = { _, _ -> error("ambiguous instance set must not contact a Host") },
            )
        assertEquals(ManagedHostResponse.Rejected(ManagedHostFailure.INSTANCE_AMBIGUOUS), result)
    }

    @Test
    fun `new instance between inspection and settlement rejects before Host effects`() {
        val fixture = fixture()
        var enumerations = 0
        val commands = mutableListOf<IdeLifecycleCommand>()
        val result =
            executeManagedHost(
                ManagedHostRequest.Settle(
                    fixture.idea.toString(),
                    fixture.profile.toString(),
                    fixture.identity.hostLifetime,
                    "update",
                ),
                fixture.home,
                processes = {
                    enumerations++
                    ManagedProcessInventory.Captured(if (enumerations == 1) listOf(fixture.process) else emptyList())
                },
                exchange = { _, command ->
                    commands += command
                    fixture.inspected
                },
            )
        assertEquals(ManagedHostResponse.Rejected(ManagedHostFailure.CONCURRENT_CHANGE), result)
        assertEquals(listOf(IdeLifecycleCommand.Inspect), commands)
    }

    private fun fixture(): Fixture {
        val root = temporary.toRealPath()
        val idea = Files.createDirectories(root.resolve("Selected.app/Contents"))
        val executable = idea.resolve("MacOS/idea")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "fixture")
        executable.toFile().setExecutable(true)
        Files.createDirectories(idea.resolve("Resources"))
        Files.writeString(
            idea.resolve("Resources/product-info.json"),
            Json.encodeToString(Product("262.1.1", listOf(Launch("macOS", "aarch64", "../MacOS/idea")))),
        )
        val home = Files.createDirectories(root.resolve("user"))
        val profile = Files.createDirectories(home.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2"))
        val process = ManagedIdeProcess(42, 100, executable.toString())
        val identity =
            IdeHostRuntimeIdentity(
                idea.toString(),
                profile.toString(),
                42,
                100,
                "host",
                IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
            )
        val inspected =
            IdeLifecycleResult.Inspected(
                "host",
                idea.toString(),
                "262.1.1",
                emptyList(),
                HostedCompatibilityDocument(
                    "262.1.1",
                    "262.1.1-IJ",
                    "1.2.3",
                    HostedContractDocument(
                        runtimeProtocolIdentity = "test",
                        operationRegistryDigest = "test",
                        wireSchemaDigest = "test",
                        capabilities = emptyList(),
                    ),
                ),
                runtimeIdentity = identity,
            )
        return Fixture(idea, home, profile, process, identity, inspected)
    }

    private data class Fixture(
        val idea: Path,
        val home: Path,
        val profile: Path,
        val process: ManagedIdeProcess,
        val identity: IdeHostRuntimeIdentity,
        val inspected: IdeLifecycleResult.Inspected,
    )

    @Serializable
    private data class Product(
        val buildNumber: String,
        val launch: List<Launch>,
        val dataDirectoryName: String = "IntelliJIdea2026.2",
    )

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)
}
