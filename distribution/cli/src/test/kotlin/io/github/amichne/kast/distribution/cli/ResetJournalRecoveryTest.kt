package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResetJournalRecoveryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `interrupted detachment retains bytes and retry resumes recorded storage`() {
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "keep until quiescent")
        val selected = selected(root)
        val prior = (ForceResetFence.acquire(selected) as ResetFenceAcquisition.Acquired).fence
        val detached = (FencedReset.detach(prior) as ResetFencing.Fenced).reset
        val retained = (detached.storage as RetainedResetStorage.Held).directory
        prior.close()
        assertEquals("keep until quiescent", Files.readString(retained.resolve("payload/sentinel")))
        val runtime = ResetRuntimeScript(root, closedRetirementSteps() + closedRetirementSteps())
        val outcome =
            forceResetInstallation(
                root,
                home(),
                ForceResetOperation.UNINSTALL,
                ForceResetExecution(runtime, {}),
            )
        runtime.assertConsumed()
        assertEquals(ForceResetOutcome.Removed(root.toString()), outcome)
        assertFalse(Files.exists(retained))
        assertFalse(Files.exists(installationResetFence(root)))
    }

    @Test
    fun `recorded intent before atomic move can be resumed`() {
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "prior")
        val marker = installationResetFence(root)
        val directory = Files.createTempDirectory(marker.parent, prefix(marker))
        writeRecord(root, InstallationResetStorage.Retained(directory.toString()))
        (ForceResetFence.acquire(selected(root)) as ResetFenceAcquisition.Acquired).fence.use { fence ->
            val fenced = (FencedReset.detach(fence) as ResetFencing.Fenced).reset
            assertEquals(directory, (fenced.storage as RetainedResetStorage.Held).directory)
            assertEquals("prior", Files.readString(directory.resolve("payload/sentinel")))
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `ambiguous original and retained payload are protected and remain fenced`() {
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "original")
        val marker = installationResetFence(root)
        val directory = Files.createTempDirectory(marker.parent, prefix(marker))
        Files.createDirectories(directory.resolve("payload"))
        Files.writeString(directory.resolve("payload/sentinel"), "retained")
        writeRecord(root, InstallationResetStorage.Retained(directory.toString()))
        (ForceResetFence.acquire(selected(root)) as ResetFenceAcquisition.Acquired).fence.use { fence ->
            assertEquals(ResetFencing.Rejected(ForceResetFailure.FILESYSTEM_REJECTED), FencedReset.detach(fence))
        }
        assertEquals("original", Files.readString(root.resolve("sentinel")))
        assertEquals("retained", Files.readString(directory.resolve("payload/sentinel")))
        assertTrue(Files.exists(marker))
    }

    @Test
    fun `out of scope corrupt and foreign root journals cannot grant deletion authority`() {
        val root = root()
        val foreign = Files.createDirectories(temporary.resolve("protected"))
        Files.writeString(foreign.resolve("sentinel"), "protected")
        writeRecord(root, InstallationResetStorage.Retained(foreign.toString()))
        assertEquals(
            ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED),
            ForceResetFence.acquire(selected(root)),
        )
        Files.writeString(installationResetFence(root), "malformed")
        assertEquals(
            ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED),
            ForceResetFence.acquire(selected(root)),
        )
        Files.writeString(
            installationResetFence(root),
            managementJson.encodeToString(
                InstallationResetRequest(foreign.resolve("installation").toString(), InstallationResetStorage.Fenced)
            ),
        )
        assertEquals(
            ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED),
            ForceResetFence.acquire(selected(root)),
        )
        assertEquals("protected", Files.readString(foreign.resolve("sentinel")))
    }

    @Test
    fun `busy reset and consumed detachment cannot grant a second capability`() {
        val root = root()
        val selected = selected(root)
        (ForceResetFence.acquire(selected) as ResetFenceAcquisition.Acquired).fence.use { fence ->
            assertEquals(
                ResetFenceAcquisition.Rejected(ForceResetFailure.RESET_BUSY),
                ForceResetFence.acquire(selected),
            )
            assertTrue(FencedReset.detach(fence) is ResetFencing.Fenced)
            assertEquals(ResetFencing.Rejected(ForceResetFailure.RESET_BUSY), FencedReset.detach(fence))
        }
    }

    @Test
    fun `replaced fence identity rejects erasure and preserves retained bytes`() {
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "protected")
        (ForceResetFence.acquire(selected(root)) as ResetFenceAcquisition.Acquired).fence.use { fence ->
            val fenced = (FencedReset.detach(fence) as ResetFencing.Fenced).reset
            val runtime = ResetRuntimeScript(root, closedRetirementSteps())
            val retired = (QuiescentReset.retire(fenced, home(), runtime) as ResetRetirement.Retired).reset
            runtime.assertConsumed()
            val replacement = temporary.resolve("replacement")
            Files.writeString(replacement, "other inode")
            Files.move(replacement, fence.marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(ResetErasure.Rejected(ForceResetFailure.FENCE_REJECTED), ErasedReset.erase(retired))
            val directory = (fenced.storage as RetainedResetStorage.Held).directory
            assertEquals("protected", Files.readString(directory.resolve("payload/sentinel")))
        }
    }

    @Test
    fun `removal rejects a replacement fence without deleting it`() {
        ErasedResetFixture.create(root(), home()).use { fixture ->
            val erased = fixture.erased
            val marker = erased.fence.marker
            val content = Files.readString(marker)
            Files.move(marker, marker.resolveSibling("prior-marker"))
            Files.writeString(marker, content)

            assertEquals(ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED), erased.fence.liftRemoved(erased))
            assertEquals(content, Files.readString(marker))
        }
    }

    @Test
    fun `consumed erasure cannot remove a recreated root or lift its fence`() {
        val root = root()
        ErasedResetFixture.create(root, home()).use { fixture ->
            Files.createDirectories(root)
            Files.writeString(root.resolve("sentinel"), "keep")

            assertEquals(
                ResetErasure.Rejected(ForceResetFailure.RESET_BUSY),
                ErasedReset.erase(fixture.erased.quiescent),
            )
            assertEquals(
                ResetEffect.Rejected(ForceResetFailure.RESET_BUSY),
                fixture.erased.fence.liftRemoved(fixture.erased),
            )
            assertEquals("keep", Files.readString(root.resolve("sentinel")))
            assertTrue(Files.exists(fixture.erased.fence.marker))
        }
    }

    @Test
    fun `removal cannot lift another roots fence`() {
        val foreign = temporary.toRealPath().resolve("foreign")
        ErasedResetFixture.create(root(), home()).use { selected ->
            ErasedResetFixture.create(foreign, home()).use { other ->
                assertEquals(
                    ResetEffect.Rejected(ForceResetFailure.RESET_BUSY),
                    other.erased.fence.liftRemoved(selected.erased),
                )
                assertTrue(Files.exists(selected.erased.fence.marker))
                assertTrue(Files.exists(other.erased.fence.marker))
            }
        }
    }

    @Test
    fun `native launcher relocated with retained payload still must retire before erasure`() {
        val root = root()
        Files.createDirectories(root)
        Files.writeString(root.resolve("sentinel"), "protected")
        (ForceResetFence.acquire(selected(root)) as ResetFenceAcquisition.Acquired).fence.use { fence ->
            val fenced = (FencedReset.detach(fence) as ResetFencing.Fenced).reset
            val retained = (fenced.storage as RetainedResetStorage.Held).directory
            val snapshot =
                LifecycleProcessSnapshot(
                    91,
                    200,
                    retained.resolve("payload/installation/share/kast/libexec/kast-daemon").toString(),
                    emptyList(),
                )
            val runtime =
                ResetRuntimeScript(
                    root,
                    listOf(
                        ResetScriptStep.Table(ResetProcessTable.Observed(listOf(snapshot))),
                        ResetScriptStep.Capture(snapshot, ResetScriptCaptureResult.NO_SHARED_DESCRIPTORS),
                        ResetScriptStep.Job(true, true, ResetCommandObservation.Exited(113)),
                        ResetScriptStep.Job(false, true, ResetCommandObservation.Exited(113)),
                        ResetScriptStep.Signal(snapshot, LifecycleProcessObservation.Present(snapshot)),
                    ),
                )
            assertEquals(
                ResetRetirement.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED),
                QuiescentReset.retire(fenced, home(), runtime),
            )
            runtime.assertConsumed()
            assertEquals("protected", Files.readString(retained.resolve("payload/sentinel")))
        }
    }

    private fun writeRecord(root: Path, storage: InstallationResetStorage) {
        Files.writeString(
            installationResetFence(root),
            managementJson.encodeToString(InstallationResetRequest(root.resolve("installation").toString(), storage)),
        )
    }

    private fun prefix(marker: Path) =
        ".kast-retiring-" + marker.fileName.toString().removePrefix(".kast-reset-").removeSuffix(".json") + "-"

    private fun home(): Path = Files.createDirectories(temporary.resolve("home"))

    private fun root(): Path = temporary.toRealPath().resolve("kast")

    private fun selected(root: Path) = (ForceResetRoot.admit(root, home()) as ForceResetRootAdmission.Selected).root
}
