package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ForceInstallationResetTest {
    @TempDir lateinit var temporary: Path

    private fun home(): Path = Files.createDirectories(temporary.toRealPath().resolve("home"))

    @Test
    fun `a parent alias cannot redirect reset to another installation`() {
        val home = home()
        val protected = Files.createDirectories(temporary.toRealPath().resolve("protected/share/kast"))
        Files.writeString(protected.resolve("keep"), "protected")
        Files.createSymbolicLink(home.resolve(".local"), protected.parent.parent)

        assertEquals(
            ForceResetRootAdmission.Rejected,
            ForceResetRoot.admit(home.resolve(".local/share/kast"), home),
        )
        assertEquals("protected", Files.readString(protected.resolve("keep")))
    }

    @Test
    fun `force uninstall erases all unproven files without executing installed scripts`() {
        val root = Files.createDirectories(temporary.toRealPath().resolve("kast"))
        val protected = temporary.toRealPath().resolve("keep")
        Files.writeString(protected, "protected")
        Files.createDirectories(root.resolve("installation/share/kast"))
        listOf(
                "management.json",
                "management.lock",
                "shutdown.json",
                "installation/installation.json",
                "installation/share/kast/install.sh",
            )
            .forEach { Files.writeString(root.resolve(it), "invalid ownership") }
        Files.createSymbolicLink(root.resolve("foreign-link"), protected)
        val result =
            forceResetInstallation(
                root,
                home(),
                emptyMap(),
                ForceResetOperation.UNINSTALL,
                closedExecution(root, 2),
            )
        assertEquals(ForceResetOutcome.Removed(root.toString()), result)
        assertFalse(Files.exists(root))
        assertEquals("protected", Files.readString(protected))
    }

    @Test
    fun `force uninstall removes the exact exterior login entry before losing installed state`() {
        val root = Files.createDirectories(temporary.toRealPath().resolve("kast"))
        val home = home()
        val digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(root.resolve("installation").toRealPathOrAbsolute().toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
        val login = home.resolve("Library/LaunchAgents/io.github.amichne.kast.broker.$digest.login.plist")
        Files.createDirectories(login.parent)
        Files.writeString(login, "corrupt selected login entry")
        val protected = home.resolve("Library/LaunchAgents/unrelated.plist")
        Files.writeString(protected, "keep")
        val result =
            forceResetInstallation(root, home, emptyMap(), ForceResetOperation.UNINSTALL, closedExecution(root, 2))
        assertEquals(ForceResetOutcome.Removed(root.toString()), result)
        assertFalse(Files.exists(login), "selected login entry must not survive deletion")
        assertEquals("keep", Files.readString(protected))
    }

    private fun Path.toRealPathOrAbsolute(): Path = toAbsolutePath().normalize()

    private fun closedExecution(root: Path, retirements: Int): ForceResetExecution {
        val runtime = ResetRuntimeScript(root, List(retirements) { closedRetirementSteps() }.flatten())
        return ForceResetExecution(
            runtime,
            ForceReinstaller { _, _, _ -> error("unexpected installer") },
            observe = { observation ->
                if (
                    observation ==
                        ResetObservation.Completed(ForceResetOperation.UNINSTALL, ForceResetStage.FINAL_VERIFICATION)
                )
                    runtime.assertConsumed()
            },
        )
    }

    @Test
    fun `force reinstall calls the fresh installer only after the entire root is absent`() {
        val root = Files.createDirectories(temporary.toRealPath().resolve("kast"))
        Files.writeString(root.resolve("untracked"), "remove me")
        var calls = 0
        val runtime = ResetRuntimeScript(root, closedRetirementSteps() + closedRetirementSteps())
        val result =
            forceResetInstallation(
                root,
                home(),
                mapOf("PATH" to "/fixture"),
                ForceResetOperation.REINSTALL,
                ForceResetExecution(
                    runtime,
                    ForceReinstaller { erased, selectedHome, environment ->
                        calls++
                        assertEquals(root, erased.root)
                        assertEquals(temporary.toRealPath().resolve("home"), selectedHome)
                        assertEquals(mapOf("PATH" to "/fixture"), environment)
                        assertFalse(Files.exists(root))
                        FreshResetResult.Rejected(ForceResetFailure.INSTALLER_REJECTED)
                    },
                    observe = {},
                ),
            )
        runtime.assertConsumed()
        assertEquals(1, calls)
        assertEquals(
            ForceResetOutcome.Rejected(
                ForceResetOperation.REINSTALL,
                ForceResetStage.INSTALLATION,
                ForceResetFailure.INSTALLER_REJECTED,
            ),
            result,
        )
    }

    @Test
    fun `missing roots are already removed and root symlinks are unlinked without following`() {
        val root = temporary.toRealPath().resolve("kast")
        val home = home()
        assertEquals(
            ForceResetOutcome.Removed(root.toString()),
            forceResetInstallation(root, home, emptyMap(), ForceResetOperation.UNINSTALL, closedExecution(root, 2)),
        )
        Files.writeString(home.resolve("keep"), "protected")
        Files.createSymbolicLink(root, home)
        assertEquals(
            ForceResetOutcome.Removed(root.toString()),
            forceResetInstallation(root, home, emptyMap(), ForceResetOperation.UNINSTALL, closedExecution(root, 2)),
        )
        assertEquals("protected", Files.readString(home.resolve("keep")))
    }

    @Test
    fun `filesystem refusal cannot become successful force removal`() {
        assumeTrue((Files.getAttribute(temporary, "unix:uid") as Number).toLong() != 0L)
        val home = home()
        val parent = Files.createDirectories(temporary.toRealPath().resolve("protected"))
        val root = parent.resolve("kast")
        Files.writeString(root, "must remain when unlink is denied")
        val permissions = Files.getPosixFilePermissions(parent)
        try {
            Files.setPosixFilePermissions(
                parent,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            assertEquals(
                ForceResetOutcome.Rejected(
                    ForceResetOperation.UNINSTALL,
                    ForceResetStage.FENCE,
                    ForceResetFailure.FENCE_REJECTED,
                ),
                forceResetInstallation(root, home, emptyMap(), ForceResetOperation.UNINSTALL, closedExecution(root, 0)),
            )
            assertTrue(Files.exists(root))
        } finally {
            Files.setPosixFilePermissions(parent, permissions)
        }
    }

    @Test
    fun `force does not erase HOME filesystem roots or an aliased HOME`() {
        val home = home()
        val alias = Files.createSymbolicLink(temporary.toRealPath().resolve("alias"), temporary)
        listOf(home, Path.of("/"), temporary, alias.resolve("home")).forEach { root ->
            assertEquals(
                ForceResetOutcome.Rejected(
                    ForceResetOperation.UNINSTALL,
                    ForceResetStage.ADMISSION,
                    ForceResetFailure.ROOT_REJECTED,
                ),
                forceResetInstallation(root, home, emptyMap(), ForceResetOperation.UNINSTALL, closedExecution(root, 0)),
            )
        }
        assertTrue(Files.isDirectory(home))
    }
}
