package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
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

private class UncontendedPreparation : ContainerPreparationWait {
    override fun elapsed(): Duration = Duration.ZERO

    override fun pause(duration: Duration): Unit = error("unexpected lock contention")

    override fun admitActive(): Refinement<Unit, ContainerFailure> = Refinement.Refined(Unit)
}
