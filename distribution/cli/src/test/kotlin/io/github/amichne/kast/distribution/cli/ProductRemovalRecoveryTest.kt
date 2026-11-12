package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ProductRemovalRecoveryTest {
    @Test
    fun `partial self-removal resumes with admitted retained bytes`(@TempDir temporary: Path) {
        val fixture = interrupted(temporary)
        val root = fixture.installation.parent
        val retained = root.resolve("recovery/installation/product-removal/control")
        var invoked = false
        uninstallInstallation(
            root,
            temporary.toRealPath(),
            executeInstaller = { script, arguments ->
                assertEquals(retained.resolve("share/kast/install-candidate.sh"), script)
                assertEquals("--remove-product", arguments.first())
                assertTrue(readUninstallRetirement(root).productRemoval != null)
                Files.delete(fixture.installation.resolve("installation.json"))
                Files.delete(fixture.installation)
                Files.move(fixture.runtime.root, temporary.resolve("removed-runtime"))
                invoked = true
                0
            },
        )
        assertTrue(invoked)
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(uninstallJournalPath(root)))
        assertEquals("protected", Files.readString(temporary.resolve("foreign")))
    }

    @Test
    fun `changed retained interpreter rejects before execution and protects evidence`(@TempDir temporary: Path) {
        val fixture = interrupted(temporary)
        val root = fixture.installation.parent
        val interpreter =
            root.resolve("recovery/installation/product-removal/control/share/kast/private-python/bin/python3.12")
        Files.writeString(interpreter, "foreign replacement")
        val before = Files.readString(uninstallJournalPath(root))
        val rejection =
            assertThrows<ManagementRejected> {
                uninstallInstallation(
                    root,
                    temporary.toRealPath(),
                    executeInstaller = { _, _ -> error("unadmitted execution") },
                )
            }
        assertEquals("uninstall-recovery", rejection.stage)
        assertEquals(before, Files.readString(uninstallJournalPath(root)))
        assertEquals("foreign replacement", Files.readString(interpreter))
        assertTrue(Files.exists(fixture.runtime.root))
    }

    @Test
    fun `partial terminal cleanup resumes without executing the erased helper`(@TempDir temporary: Path) {
        val fixture = interrupted(temporary)
        val root = fixture.installation.parent
        Files.delete(fixture.installation.resolve("installation.json"))
        Files.delete(fixture.installation)
        Files.move(fixture.runtime.root, temporary.resolve("removed-runtime"))
        Files.delete(productRemovalDirectory(root).resolve("control/share/kast/install-candidate.sh"))

        uninstallInstallation(
            root,
            temporary.toRealPath(),
            executeInstaller = { _, _ -> error("payload was already removed") },
        )

        assertFalse(Files.exists(root))
        assertFalse(Files.exists(uninstallJournalPath(root)))
        assertEquals("protected", Files.readString(temporary.resolve("foreign")))
    }

    @Test
    fun `malformed retirement fence is a typed rejection and remains intact`(@TempDir temporary: Path) {
        val fixture = interrupted(temporary)
        val root = fixture.installation.parent
        Files.delete(fixture.installation.resolve("installation.json"))
        Files.delete(fixture.installation)
        val fence = root.resolve(SHUTDOWN_FENCE)
        Files.writeString(fence, "{invalid")
        val journal = Files.readString(uninstallJournalPath(root))

        val rejected = assertThrows<ManagementRejected> { cleanupProductRemovalFence(root) }

        assertEquals("uninstall-recovery", rejected.stage)
        assertEquals("{invalid", Files.readString(fence))
        assertEquals(journal, Files.readString(uninstallJournalPath(root)))
    }

    @Test
    fun `foreign recovery survivor blocks terminal cleanup without deleting evidence`(@TempDir temporary: Path) {
        val fixture = interrupted(temporary)
        val root = fixture.installation.parent
        Files.delete(fixture.installation.resolve("installation.json"))
        Files.delete(fixture.installation)
        Files.move(fixture.runtime.root, temporary.resolve("removed-runtime"))
        val foreign = Files.writeString(productRemovalDirectory(root).resolve("foreign"), "protected")
        val journal = Files.readString(uninstallJournalPath(root))

        val rejected =
            assertThrows<ManagementRejected> {
                uninstallInstallation(
                    root,
                    temporary.toRealPath(),
                    executeInstaller = { _, _ -> error("unexpected child") },
                )
            }

        assertEquals("uninstall-recovery", rejected.stage)
        assertEquals("protected", Files.readString(foreign))
        assertEquals(journal, Files.readString(uninstallJournalPath(root)))
        assertTrue(Files.exists(productRemovalDirectory(root).resolve("control/installation.json")))
    }

    private fun interrupted(temporary: Path): InstalledRuntimeFixture {
        val fixture = productFixture(temporary)
        writeProductRemovalFixture(fixture)
        val root = fixture.installation.parent
        val executable = Files.writeString(temporary.resolve("control"), "fixture Control")
        Files.writeString(temporary.resolve("foreign"), "protected")
        addInterpreter(fixture.installation)
        writeProductManagement(fixture.installation, executable, fixture.reference)
        val receipt = admittedUninstallReceipt(root)
        persistUninstallRetirement(root, receipt, controlIdentity = uninstallFilesystemIdentity(fixture.installation))
        val recovery = root.resolve("recovery/installation/product-removal")
        val control = Files.createDirectories(recovery.resolve("control"))
        Files.walk(fixture.installation).use { paths ->
            paths.forEach { source ->
                val destination = control.resolve(fixture.installation.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            }
        }
        val journal =
            readUninstallRetirement(root)
                .copy(
                    productRemoval =
                        ProductRemovalProof(
                            1,
                            ProductRemovalPhase.PREPARED,
                            uninstallFilesystemIdentity(recovery),
                            sha256(control.resolve("installation.json")),
                            emptyList(),
                            uninstallFilesystemIdentity(fixture.runtime.root.parent),
                            null,
                            null,
                        )
                )
        Files.writeString(uninstallJournalPath(root), managementJson.encodeToString(journal))
        Files.move(fixture.installation.resolve("share"), temporary.resolve("removed-share"))
        return fixture
    }

    private fun addInterpreter(installation: Path) {
        val interpreter =
            Files.createDirectories(installation.resolve("share/kast/private-python/bin")).resolve("python3.12")
        Files.writeString(interpreter, "fixture interpreter")
        Files.setPosixFilePermissions(interpreter, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val original = (readBundledManifest(installation) as BundledManifestRead.Read).manifest
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(
                original.copy(
                    payloadFiles =
                        original.payloadFiles +
                            BundledPayload(
                                "share/kast/private-python/bin/python3.12",
                                "sha256:${sha256(interpreter)}",
                                448,
                            )
                )
            ),
        )
    }
}
