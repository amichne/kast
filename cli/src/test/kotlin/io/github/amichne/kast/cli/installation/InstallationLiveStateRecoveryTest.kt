package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationLiveStateRecoveryTest {
    @Test
    fun `rejected activation restarts the unchanged prior without enrolling a workspace`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = request(root, "1.2.3")
        val installation = first.installRoot.value
        val service = Files.createDirectories(first.controlRoot.value.resolve("share/kast/libexec"))
        val executable =
            Files.writeString(
                service.resolve("kast-service"),
                """
                |#!/bin/sh
                |[ "${'$'}#" = 1 ] || exit 81
                |config="${'$'}{KAST_CONFIGURATION_FILE%/*}"
                |printf '%s\n' "${'$'}1" >> "${'$'}config/service-actions"
                |case "${'$'}1" in
                |  disable) rm -rf -- "${'$'}config/../../recovery"; printf blocked > "${'$'}config/../../recovery" ;;
                |  bootstrap) exit 0 ;;
                |  enable) printf enrolled > "${'$'}config/workspaces.json"; exit 91 ;;
                |  *) exit 82 ;;
                |esac
                |"""
                    .trimMargin(),
            )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val manifest = Files.readString(prior.resolve("installation.json"))
        val registry = Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString())))
        Files.writeString(prior.resolve("config/workspaces.json"), registry)
        val second = request(root, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            second.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val outcome = executeFixtureInstallation(second)

        assertEquals(InstallationOutcome.RolledBack(InstallationFailure.ACTIVATION_REJECTED), outcome)
        assertEquals(listOf("disable", "bootstrap"), Files.readAllLines(prior.resolve("config/service-actions")))
        assertEquals(manifest, Files.readString(prior.resolve("installation.json")))
        assertEquals(registry, Files.readString(prior.resolve("config/workspaces.json")))
        assertEquals("blocked", Files.readString(installation.resolve("recovery")))
    }

    @Test
    fun `restoration rejects payload drift before executing the saved command`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = request(root, "1.2.3")
        val service = Files.createDirectories(first.controlRoot.value.resolve("share/kast/libexec"))
        val executable = Files.writeString(service.resolve("kast-service"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
        val prior = first.installRoot.value.resolve("installation")
        val retirement = (PriorRetirement.admit(prior, first) as Refinement.Refined).value
        val marker = prior.resolve("config/changed-command-executed")
        Files.writeString(
            prior.resolve("share/kast/libexec/kast-service"),
            "#!/bin/sh\nprintf changed > '${marker}'\n",
        )
        val candidate = request(root, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            candidate.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val outcome =
            retirement.restore(
                InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED,
                candidate.controlRoot.value,
                candidate,
            )

        assertEquals(
            InstallationOutcome.RecoveryRequired(
                InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED,
                InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED,
            ),
            outcome,
        )
        assertTrue(Files.notExists(marker))
    }

    @Test
    fun `failed retirement restarts the still selected prior service`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = request(root, "1.2.3")
        val installation = first.installRoot.value
        val service = Files.createDirectories(first.controlRoot.value.resolve("share/kast/libexec"))
        val executable =
            Files.writeString(
                service.resolve("kast-service"),
                """
                |#!/bin/sh
                |[ "${'$'}#" = 1 ] || exit 81
                |config="${'$'}{KAST_CONFIGURATION_FILE%/*}"
                |printf '%s\n' "${'$'}1" >> "${'$'}config/service-actions"
                |case "${'$'}1" in disable) exit 17 ;; bootstrap) exit 0 ;; *) exit 82 ;; esac
                |"""
                    .trimMargin(),
            )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val manifest = Files.readString(prior.resolve("installation.json"))
        val second = request(root, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            second.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val outcome = executeFixtureInstallation(second)

        assertEquals(InstallationOutcome.RolledBack(InstallationFailure.PRIOR_RETIREMENT_EXIT_REJECTED), outcome)
        assertEquals(listOf("disable", "bootstrap"), Files.readAllLines(prior.resolve("config/service-actions")))
        assertEquals(manifest, Files.readString(prior.resolve("installation.json")))
    }

    @Test
    fun `persistent state rejection restores the prior service`(@TempDir temporary: Path) {
        verifyPersistentStateRejection(true, temporary)
    }

    @Test
    fun `failed prior restoration preserves both failure conditions`(@TempDir temporary: Path) {
        verifyPersistentStateRejection(false, temporary)
    }

    private fun verifyPersistentStateRejection(restoreWorks: Boolean, temporary: Path) {
        val root = temporary.toRealPath()
        val first = request(root, "1.2.3")
        val installation = first.installRoot.value
        val service = Files.createDirectories(first.controlRoot.value.resolve("share/kast/libexec"))
        val executable =
            Files.writeString(
                service.resolve("kast-service"),
                """
                |#!/bin/sh
                |[ "${'$'}#" = 1 ] || exit 81
                |case "${'$'}1" in disable|bootstrap) ;; *) exit 82 ;; esac
                |config="${'$'}{KAST_CONFIGURATION_FILE%/*}"
                |printf '%s\n' "${'$'}1" >> "${'$'}config/service-actions"
                |[ "${'$'}1" != bootstrap ] || exit ${if (restoreWorks) 0 else 91}
                |"""
                    .trimMargin(),
            )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val manifest = Files.readString(prior.resolve("installation.json"))
        val external = Files.writeString(root.resolve("external"), "preserved")
        Files.createSymbolicLink(Files.createDirectories(prior.resolve("state/run")).resolve("persistent"), external)
        val second = request(root, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            second.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val outcome = executeFixtureInstallation(second)

        val expected =
            if (restoreWorks) InstallationOutcome.RolledBack(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED)
            else
                InstallationOutcome.RecoveryRequired(
                    InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED,
                    InstallationFailure.PRIOR_RECOVERY_REJECTED,
                )
        assertEquals(expected, outcome)
        assertEquals(listOf("disable", "bootstrap"), Files.readAllLines(prior.resolve("config/service-actions")))
        assertTrue(Files.isSymbolicLink(prior.resolve("state/run/persistent")))
        assertEquals("preserved", Files.readString(external))
        assertEquals(manifest, Files.readString(prior.resolve("installation.json")))
    }

    @Test
    fun `upgrade retires live state before inspecting the snapshot`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = request(root, "1.2.3")
        val installation = first.installRoot.value
        val service = Files.createDirectories(first.controlRoot.value.resolve("share/kast/libexec"))
        val retirement =
            Files.writeString(
                service.resolve("kast-service"),
                """
                |#!/bin/sh
                |[ "${'$'}#" = 1 ] && [ "${'$'}1" = disable ] || exit 81
                |config="${'$'}{KAST_CONFIGURATION_FILE%/*}"
                |rm -- "${'$'}config/../state/run/live-link" || exit 82
                |printf 'retired\n' >> "${'$'}config/retirement-count"
                |"""
                    .trimMargin(),
            )
        Files.setPosixFilePermissions(retirement, PosixFilePermissions.fromString("rwx------"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val external = Files.writeString(root.resolve("external"), "preserved")
        val run = Files.createDirectories(prior.resolve("state/run"))
        Files.createSymbolicLink(run.resolve("live-link"), external)
        val second = request(root, "1.2.4")
        Files.copy(
            Path.of("../packaging/installation-lifecycle.py"),
            second.controlRoot.value.resolve("share/kast/installation-lifecycle.py"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val upgraded = executeFixtureInstallation(second)

        assertInstanceOf(InstallationOutcome.Complete::class.java, upgraded)
        assertTrue(Files.notExists(prior.resolve("state/run/live-link")))
        assertEquals("preserved", Files.readString(external))
        val retiredPrior = installation.resolve("recovery/replacement/payload")
        assertEquals("retired\n", Files.readString(retiredPrior.resolve("config/retirement-count")))
    }

    private fun request(root: Path, version: String): InstallationRequest {
        val home = Files.createDirectories(root.resolve("home"))
        return releaseRequest(
            fixture = root,
            installation = home.resolve(".local/share/kast"),
            commands = home.resolve(".local/bin"),
            home = home,
            codexHome = Files.createDirectories(home.resolve(".codex")),
            version = version,
        )
    }
}
