package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerGenerationTest {
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
    fun `a rebuilt generation starts from its own retired indexes and from nothing else`(@TempDir temporary: Path) {
        val unproven: List<Pair<String, (AdmittedContainer) -> Unit>> =
            listOf(
                "no exit record" to { Files.delete(HostExitRecord.path(it.state)) },
                "indexing at exit" to
                    {
                        Files.delete(HostExitRecord.path(it.state))
                        recordExit(it, HostExitIndexState.DUMB)
                    },
                "leftover IDE lock" to { Files.writeString(ideConfiguration(it).resolve(".lock"), "stale PID") },
                "no index storage" to { Files.move(it.state.resolve("system/index"), it.state.resolve("moved")) },
            )
        for ((name, change) in unproven) {
            val current = rebuilt(Files.createDirectory(temporary.resolve(name.replace(' ', '-'))), change = change)
            assertEquals(WorkspaceSeedResult.NoEligibleDonor, seedResult(current), name)
            Files.list(current.state.resolve("system")).use { assertEquals(0, it.count(), name) }
        }
        val other = rebuilt(Files.createDirectory(temporary.resolve("other-ide")), distribution = "b".repeat(64))
        assertEquals(WorkspaceSeedResult.NoEligibleDonor, seedResult(other))

        val current = rebuilt(Files.createDirectory(temporary.resolve("same-ide")))
        if (!current.runtime.manifest.platform.publicName.startsWith("macos")) {
            assertEquals(WorkspaceSeedResult.UnsupportedFilesystem, seedResult(current))
            return
        }
        assertEquals(WorkspaceSeedResult.Seeded(current.id.text), seedResult(current))
        val system = current.state.resolve("system")
        assertEquals("index", Files.readString(system.resolve("index/idindex/data")))
        assertEquals("caches", Files.readString(system.resolve("caches/records.dat")))
        // A project model names the retired runtime's JDK and profile paths, so the new Host imports its own.
        Files.list(system).use { children ->
            assertEquals(setOf("caches", "index"), children.map { it.fileName.toString() }.toList().toSet())
        }
        assertFalse(Files.exists(HostExitRecord.path(current.state)))
        // The retired storage stays separate from the new Host's writes.
        Files.writeString(system.resolve("index/idindex/data"), "new Host")
        assertEquals("index", Files.readString(current.directory.resolve("state/system/index/idindex/data")))
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

/** Retires one stopped workspace, then rebuilds it for another Kast build. */
private fun rebuilt(
    root: Path,
    distribution: String = "a".repeat(64),
    change: (AdmittedContainer) -> Unit = {},
): AdmittedContainer {
    val store =
        ContainerStore(
            root.toRealPath().resolve("manager"),
            (ContainerPlatform.current() as Refinement.Refined).value,
        )
    val previous =
        (store.create(
                ContainerCommand.Create("backend", null, null, false),
                Files.createDirectory(root.resolve("workspace")).toRealPath(),
                containerRuntimeFixture(store),
            ) as Refinement.Refined)
            .value
    for ((path, text) in
        listOf(
            "index/idindex/data" to "index",
            "caches/records.dat" to "caches",
            "kast-projects/one/.idea/workspace.xml" to "model",
            "projects/one/project-model-cache/cache.data" to "model",
        )) {
        val file = previous.state.resolve("system").resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
    recordExit(previous, HostExitIndexState.SMART)
    change(previous)
    assertEquals(
        Refinement.Refined(Unit),
        retireManagedWorkspaces(store, { Refinement.Refined(Unit) }) { Refinement.Refined(Unit) },
    )
    val runtime = containerRuntimeFixture(store, "replacement Host", distribution = distribution)
    return (ContainerQueryLease.acquire(previous.owned, store.platform) as Refinement.Refined).value.use { lease ->
        (prepareContainerGeneration(lease, runtime, ElapsedTimeAllowance.Unlimited, UncontendedPreparation())
                as Refinement.Refined)
            .value
    }
}

private fun recordExit(container: AdmittedContainer, index: HostExitIndexState) =
    writeContainerRecord(
        HostExitRecord.path(container.state),
        HostExitRecord(HostExitReason.SETTLED, index, "123e4567-e89b-12d3-a456-426614174000"),
    )

private fun ideConfiguration(container: AdmittedContainer): Path {
    val launch = SelectedIdeInstallation.resolve(container.runtime.home) as SelectedIdeLaunch.Resolved
    val plugins = SelectedIdeInstallation.pluginRoot(launch, container.home) as Refinement.Refined
    return plugins.value.value.parent
}

private fun seedResult(container: AdmittedContainer): WorkspaceSeedResult =
    (readContainerRecord<WorkspaceSeedRecord>(container.state.resolve("workspace-seed.json")) as Refinement.Refined)
        .value
        .result

private class UncontendedPreparation : ContainerPreparationWait {
    override fun elapsed(): Duration = Duration.ZERO

    override fun pause(duration: Duration): Unit = error("unexpected lock contention")

    override fun admitActive(): Refinement<Unit, ContainerFailure> = Refinement.Refined(Unit)
}
