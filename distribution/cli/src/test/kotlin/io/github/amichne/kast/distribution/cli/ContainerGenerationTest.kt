package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerGenerationTest {
    @Test
    fun `legacy migration waits for readers and preserves stopped profile and trust`(@TempDir temporary: Path) {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val legacy = legacyWorkspace(store, workspace)
        val directory = legacy.directory
        val record = directory.resolve("container.json")
        val configuration = Files.readString(legacy.configuration)
        (ContainerQueryLease.acquire(legacy) as Refinement.Refined).value.use {
            assertEquals(Refinement.Rejected(ContainerFailure.BUSY), migrateLegacyWorkspace(store, legacy.owned))
        }
        // A partial migration may retain trust, but cannot replace conflicting generation evidence.
        val originalRecord = Files.readString(record)
        writeContainerRecord(
            directory.resolve("generation.json"),
            ContainerGenerationRecord(legacy.runtime.key, "b".repeat(64)),
        )
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_RECORD), migrateLegacyWorkspace(store, legacy.owned))
        assertEquals(originalRecord, Files.readString(record))
        val retainedTrust = Files.readString(directory.resolve("trust.xml"))
        Files.delete(directory.resolve("generation.json"))
        val migrated = (migrateLegacyWorkspace(store, legacy.owned) as Refinement.Refined).value
        assertEquals(ContainerGenerationSource.Current, migrated.generation)
        assertEquals(legacy.id, migrated.id)
        assertEquals(legacy.gradleHome, migrated.gradleHome)
        val generation = (store.admitGeneration(migrated) as Refinement.Refined).value
        assertEquals(legacy.home, generation.home)
        assertEquals(configuration, Files.readString(generation.configuration))
        assertEquals(Refinement.Refined(ContainerState.STOPPED), containerDisposition(generation))
        assertEquals(retainedTrust, Files.readString(directory.resolve("trust.xml")))
        assertEquals(legacy.id, (store.workspace(workspace) as Refinement.Refined).value.id)
        assertEquals(Refinement.Refined(Unit), admitContainerTrust(migrated.directory.resolve("trust.xml"), workspace))
    }

    @Test
    fun `rebuilding requires retirement and preserves trust Gradle home and old evidence`(@TempDir temporary: Path) {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val previous =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val runtime = containerRuntimeFixture(store, "replacement Host")
        val trust = Files.readString(previous.directory.resolve("trust.xml"))
        val record = Files.readString(previous.directory.resolve("container.json"))
        val receipt = Files.createDirectories(previous.state.resolve("evidence")).resolve("recovery")
        Files.writeString(receipt, "unresolved change")
        (ContainerQueryLease.acquire(previous) as Refinement.Refined).value.use { lease ->
            assertEquals(
                Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH),
                prepareContainerGeneration(lease, runtime, ElapsedTimeAllowance.Unlimited, UncontendedPreparation()),
            )
        }
        assertEquals(
            Refinement.Refined(Unit),
            retireManagedWorkspaces(store, { Refinement.Refined(Unit) }) { Refinement.Refined(Unit) },
        )
        (ContainerQueryLease.acquire(previous.owned, store.platform) as Refinement.Refined).value.use { lease ->
            val current =
                (prepareContainerGeneration(lease, runtime, ElapsedTimeAllowance.Unlimited, UncontendedPreparation())
                        as Refinement.Refined)
                    .value
            assertNotEquals(previous.home, current.home)
            assertEquals(runtime.archiveSha256, current.runtime.archiveSha256)
            assertEquals(previous.gradleHome, current.gradleHome)
            assertEquals("unresolved change", Files.readString(receipt))
            assertEquals(trust, Files.readString(current.directory.resolve("trust.xml")))
            assertEquals(record, Files.readString(current.directory.resolve("container.json")))
            assertEquals(current.home, (store.admitGeneration(previous.owned) as Refinement.Refined).value.home)
        }
    }

    @Test
    fun `resuming a retired generation consumes its retirement proof`(@TempDir temporary: Path) {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val previous =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    Files.createDirectory(temporary.resolve("workspace")),
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val runtime = containerRuntimeFixture(store, "replacement Host")
        assertEquals(
            Refinement.Refined(Unit),
            retireManagedWorkspaces(store, { Refinement.Refined(Unit) }) { Refinement.Refined(Unit) },
        )
        assertEquals(Refinement.Refined(Unit), resumeContainerState(previous))
        (ContainerQueryLease.acquire(previous) as Refinement.Refined).value.use { lease ->
            assertEquals(
                Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH),
                prepareContainerGeneration(lease, runtime, ElapsedTimeAllowance.Unlimited, UncontendedPreparation()),
            )
        }
    }
}

private fun legacyWorkspace(store: ContainerStore, workspace: Path): AdmittedContainer {
    val id = (ContainerId.parse("a1".repeat(16)) as Refinement.Refined).value
    val directory = store.containers.resolve(id.text)
    val current =
        (store.create(
                AdmittedContainerCreation(
                    id,
                    (ContainerName.parse("legacy") as Refinement.Refined).value,
                    workspace,
                    directory,
                    directory.resolve("state/home/.gradle"),
                ),
                containerRuntimeFixture(store),
            ) as Refinement.Refined)
            .value
    Files.writeString(
        directory.resolve("container.json"),
        containerJson.encodeToString(
            LegacyContainerRecord(
                id.text,
                "legacy",
                workspace.toString(),
                current.runtime.key,
                current.gradleHome.toString(),
            )
        ),
    )
    Files.delete(directory.resolve("generation.json"))
    Files.delete(directory.resolve("trust.xml"))
    writeContainerRecord(current.stopped, ContainerStopRecord(id.text))
    return (store.select(id.text) as Refinement.Refined).value
}

private class UncontendedPreparation : ContainerPreparationWait {
    override fun elapsed(): Duration = Duration.ZERO

    override fun pause(duration: Duration): Unit = error("unexpected lock contention")

    override fun admitActive(): Refinement<Unit, ContainerFailure> = Refinement.Refined(Unit)
}
