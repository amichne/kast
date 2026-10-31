package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.ManagedCompositionPhase
import io.github.amichne.kast.distribution.contract.ManagedCompositionRecord
import io.github.amichne.kast.distribution.contract.ManagedFilePreimage
import io.github.amichne.kast.distribution.contract.ManagedHostIdentity
import io.github.amichne.kast.distribution.contract.ManagedHostPrevious
import io.github.amichne.kast.distribution.contract.ManagedHostReplacementRecord
import io.github.amichne.kast.distribution.contract.ManagedHostStrategy
import io.github.amichne.kast.distribution.contract.ManagementBuildBinding
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import io.github.amichne.kast.distribution.managed.InstallationReplacementWrite
import io.github.amichne.kast.distribution.managed.writeInstallationReplacementReceipt
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedControlAdapterTest {
    @Test
    fun `prepare records candidate without selecting or retiring it`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertTrue(Files.notExists(selected(request)))
        assertEquals(InstallationReplacementStage.PREPARED, receipt(request).stage)
        assertEquals(TRANSACTION, receipt(request).reservation?.transaction)
    }

    @Test
    fun `abort removes only prepared candidate and journal while installation stays absent`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        assertEquals(ManagedControlOutcome.Aborted(TRANSACTION), ManagedControlAdapter.abort(request, TRANSACTION))
        assertTrue(Files.notExists(Path.of(prepared.staged)))
        assertTrue(Files.notExists(journal(request)))
        assertTrue(Files.notExists(selected(request)))
    }

    @Test
    fun `abort preserves exact prior installation`(@TempDir temporary: Path) {
        val prior = request(temporary)
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(prior))
        discardFixtureReplacementAfterSetup(prior.installRoot.value)
        val identity = observeInstallationFilesystemIdentity(selected(prior))
        val manifest = Files.readString(selected(prior).resolve("installation.json"))
        val next = request(temporary, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            next.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        val prepared = prepare(next)
        assertEquals(ManagedControlOutcome.Aborted(TRANSACTION), ManagedControlAdapter.abort(next, TRANSACTION))
        assertEquals(identity, observeInstallationFilesystemIdentity(selected(prior)))
        assertEquals(manifest, Files.readString(selected(prior).resolve("installation.json")))
        assertTrue(Files.notExists(Path.of(prepared.staged)))
        assertTrue(Files.notExists(journal(next)))
    }

    @Test
    fun `abort rejects unknown journal entries and preserves every byte`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        val unknown = Files.writeString(journal(request).resolve("unknown"), "foreign evidence")
        assertEquals(
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED),
            ManagedControlAdapter.abort(request, TRANSACTION),
        )
        assertEquals("foreign evidence", Files.readString(unknown))
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertTrue(Files.isRegularFile(journal(request).resolve("receipt.json")))
    }

    @Test
    fun `abort rejects stale transaction without removing candidate`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        assertEquals(
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED),
            ManagedControlAdapter.abort(request, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        )
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertTrue(Files.isRegularFile(journal(request).resolve("receipt.json")))
    }

    @Test
    fun `abort maps invalid reservation facts to recovery without removing evidence`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        val receipt = journal(request).resolve("receipt.json")
        val original = Files.readString(receipt)
        val invalid = original.replace(request.controlDigest.value, "invalid-digest")
        assertTrue(invalid != original)
        Files.writeString(receipt, invalid)
        assertEquals(
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED),
            ManagedControlAdapter.abort(request, TRANSACTION),
        )
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertEquals(invalid, Files.readString(receipt))
    }

    @Test
    fun `commit before settlement preserves staged bytes and installation absence`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        val stagedIdentity = observeInstallationFilesystemIdentity(Path.of(prepared.staged))
        assertOwnershipRejection(ManagedControlAdapter.commit(request, TRANSACTION))
        assertEquals(stagedIdentity, observeInstallationFilesystemIdentity(Path.of(prepared.staged)))
        assertTrue(Files.notExists(selected(request)))
        assertEquals(InstallationReplacementStage.PREPARED, receipt(request).stage)
    }

    @Test
    fun `foreign transaction cannot commit settled reservation`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        settle(request)
        assertOwnershipRejection(ManagedControlAdapter.commit(request, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertTrue(Files.notExists(selected(request)))
    }

    @Test
    fun `changed staged manifest cannot commit even with valid JSON`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        settle(request)
        val manifest = Path.of(prepared.staged).resolve("installation.json")
        Files.writeString(manifest, Files.readString(manifest) + "\n")
        assertEquals(
            ManagedControlOutcome.Rejected(InstallationFailure.CONTROL_REJECTED),
            ManagedControlAdapter.commit(request, TRANSACTION),
        )
        assertTrue(Files.isDirectory(Path.of(prepared.staged)))
        assertTrue(Files.notExists(selected(request)))
    }

    @Test
    fun `changed staged payload cannot commit`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        settle(request)
        val executable = Path.of(prepared.staged).resolve("bin/kast-complete")
        Files.writeString(executable, "foreign bytes")
        assertEquals(
            ManagedControlOutcome.Rejected(InstallationFailure.CONTROL_REJECTED),
            ManagedControlAdapter.commit(request, TRANSACTION),
        )
        assertEquals("foreign bytes", Files.readString(executable))
        assertTrue(Files.notExists(selected(request)))
    }

    @Test
    fun `settled first installation commits through existing file owner`(@TempDir temporary: Path) {
        val request = request(temporary)
        val prepared = prepare(request)
        val identity = observeInstallationFilesystemIdentity(Path.of(prepared.staged))
        settle(request)
        assertEquals(ManagedControlOutcome.Committed(TRANSACTION), ManagedControlAdapter.commit(request, TRANSACTION))
        assertTrue(Files.notExists(Path.of(prepared.staged)))
        assertEquals(identity, observeInstallationFilesystemIdentity(selected(request)))
        assertEquals(InstallationReplacementStage.PAYLOAD_COMMITTED, receipt(request).stage)
        assertEquals(ManagedCompositionPhase.SETTLED, receipt(request).composition?.phase)
        assertTrue(Files.isRegularFile(selected(request).resolve("installation.json")))
    }

    @Test
    fun `prepare rejects prior payload before reservation and leaves selection intact`(@TempDir temporary: Path) {
        val prior = request(temporary)
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(prior))
        discardFixtureReplacementAfterSetup(prior.installRoot.value)
        val identity = observeInstallationFilesystemIdentity(selected(prior))
        val next = request(temporary, "1.2.4", 17)
        assertEquals(
            ManagedControlOutcome.Rejected(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED),
            ManagedControlAdapter.prepare(next, TRANSACTION),
        )
        assertEquals(identity, observeInstallationFilesystemIdentity(selected(prior)))
        assertTrue(Files.notExists(journal(next)))
        Files.list(next.installRoot.value).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".install-") })
        }
    }

    private fun request(temporary: Path, version: String = "1.2.3", inspectionExit: Int = 0): InstallationRequest {
        val root = temporary.toRealPath()
        val home = Files.createDirectories(root.resolve("home"))
        return releaseRequest(
            root,
            home.resolve(".local/share/kast"),
            home.resolve(".local/bin"),
            home,
            Files.createDirectories(root.resolve("codex")),
            version,
            lifecycleInspectionExit = inspectionExit,
        )
    }

    private fun prepare(request: InstallationRequest): ManagedControlOutcome.Prepared =
        assertInstanceOf(
            ManagedControlOutcome.Prepared::class.java,
            ManagedControlAdapter.prepare(request, TRANSACTION),
        )

    private fun receipt(request: InstallationRequest): InstallationReplacementReceipt =
        installationManifestJson.decodeFromString(
            InstallationReplacementReceipt.serializer(),
            Files.readString(journal(request).resolve("receipt.json")),
        )

    private fun settle(request: InstallationRequest) {
        val journal = journal(request)
        val absent = ManagedFilePreimage.Absent(journal.resolve("unrelated").toString())
        val host =
            ManagedHostReplacementRecord(
                pluginRoot = journal.resolve("plugins").toString(),
                pluginRootIdentity = null,
                retainedRootIdentity = null,
                destination = journal.resolve("plugins/kast-ide-hosted").toString(),
                candidate = journal.resolve("host-candidate").toString(),
                candidateIdentity = ManagedHostIdentity(1, 2, 3),
                candidateInventorySha256 = "a".repeat(64),
                backup = journal.resolve("host-prior").toString(),
                previous = ManagedHostPrevious.Absent,
                version = "1.2.3",
                sha256 = "b".repeat(64),
            )
        val composition =
            ManagedCompositionRecord(
                phase = ManagedCompositionPhase.SETTLED,
                strategy = ManagedHostStrategy.STOPPED,
                ideaHome = request.ideaHome.value.toString(),
                profile = journal.resolve("profile").toString(),
                selection = ManagedFilePreimage.Present("selection", "selection-copy", "c".repeat(64), 384),
                management = absent,
                publicExecutable = absent,
                profileOptions = absent,
                host = host,
                priorRuntime = absent,
                binding =
                    ManagementBuildBinding(
                        InstalledBuildIdentity("v1.2.3", "1.2.3", "d".repeat(40), "e".repeat(40)),
                        UpdatePolicy.TrackStable,
                    ),
            )
        assertEquals(
            InstallationReplacementWrite.Written,
            writeInstallationReplacementReceipt(journal, receipt(request).copy(composition = composition)),
        )
    }

    private fun assertOwnershipRejection(outcome: ManagedControlOutcome) =
        assertEquals(ManagedControlOutcome.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED), outcome)

    private fun selected(request: InstallationRequest): Path = request.installRoot.value.resolve("installation")

    private fun journal(request: InstallationRequest): Path = request.installRoot.value.resolve("recovery/replacement")

    companion object {
        private const val TRANSACTION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
