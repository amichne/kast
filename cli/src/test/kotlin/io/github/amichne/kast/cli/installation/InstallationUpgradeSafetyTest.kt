package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledUpgradePreparation
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationUpgradeSafetyTest {
    @Test
    fun `prior private service control is selected and an unsafe copy cannot fall back`(@TempDir temporary: Path) {
        val prior = temporary.toRealPath()
        val legacy = prior.resolve("bin/kast-complete")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, "#!/bin/sh\nexit 0\n")
        legacy.toFile().setExecutable(true)
        assertEquals(Refinement.Refined(PriorServiceControl.Legacy(legacy)), PriorServiceControl.admit(prior))
        val privateControl = prior.resolve("share/kast/libexec/kast-service")
        Files.createDirectories(privateControl.parent)
        Files.writeString(privateControl, "#!/bin/sh\nexit 0\n")
        privateControl.toFile().setExecutable(true)
        assertEquals(
            Refinement.Refined(PriorServiceControl.Private(privateControl)),
            PriorServiceControl.admit(prior),
        )
        assertEquals(listOf(privateControl.toString(), "disable"), PriorServiceControl.Private(privateControl).command)

        Files.delete(privateControl)
        Files.createSymbolicLink(privateControl, legacy)
        assertEquals(
            Refinement.Rejected<InstallationFailure>(InstallationFailure.PRIOR_RETIREMENT_EXECUTABLE_REJECTED),
            PriorServiceControl.admit(prior),
        )
    }

    @Test
    fun `pending daemon update preserves the selected service and command`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        Files.writeString(
            prior.resolve("config/workspaces.json"),
            Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString()))),
        )
        val retired = root.resolve("retired")
        Files.writeString(prior.resolve("bin/kast"), "#!/bin/sh\nprintf retired > '$retired'\n")
        val pending =
            InstallationWorkflow.execute(
                releaseRequest(root, installation, commands, home, codexHome, "1.2.4"),
                PriorDaemonUpgradeGateway { retirement, _, _ ->
                    assertEquals(prior.resolve("bin/kast"), retirement.daemonExecutable)
                    InstalledUpgradePreparation.Pending(NonEmptyFailures.one(UpgradeBlocker.ACTIVE_TURN))
                },
            )
        assertEquals(
            InstallationOutcome.UpgradePending(NonEmptyFailures.one(UpgradeBlocker.ACTIVE_TURN)),
            pending,
        )
        assertEquals(prior, installation.resolve("installation").toRealPath())
        assertFalse(Files.exists(commands.resolve("kast")))
        assertEquals(false, Files.exists(retired))
    }

    @Test
    fun `rejected prior admission or retirement preserves the selected release`(@TempDir temporary: Path) {
        for (brokenRetirement in listOf(false, true)) {
            val root = Files.createDirectory(temporary.resolve("case-$brokenRetirement")).toRealPath()
            val installation = root.resolve("installation")
            val commands = root.resolve("commands")
            val home = Files.createDirectory(root.resolve("home"))
            val codexHome = Files.createDirectory(home.resolve(".codex"))
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
            )
            discardFixtureReplacementAfterSetup(installation)
            val prior = installation.resolve("installation")
            val priorRegistry = Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString())))
            Files.writeString(
                prior.resolve("config/workspaces.json"),
                priorRegistry,
            )
            if (brokenRetirement) Files.writeString(prior.resolve("bin/kast"), "#!/bin/sh\nexit 17\n")
            val upgraded =
                executeFixtureInstallation(
                    releaseRequest(
                        root,
                        installation,
                        commands,
                        home,
                        codexHome,
                        "1.2.4",
                        lifecycleInspectionExit = if (brokenRetirement) 0 else 17,
                    )
                )
            val rejected = assertInstanceOf(InstallationOutcome.Rejected::class.java, upgraded)
            assertEquals(
                if (brokenRetirement) InstallationFailure.PRIOR_RETIREMENT_EXIT_REJECTED
                else InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED,
                rejected.failure,
            )
            val selected = installation.resolve("installation")
            assertEquals(prior, selected)
            assertFalse(Files.exists(commands.resolve("kast")))
            assertEquals(priorRegistry, Files.readString(prior.resolve("config/workspaces.json")))
        }
    }

    @Test
    fun `reinstall rejects corrupt same version payload and recovery metadata`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val request =
            releaseRequest(
                root,
                installation,
                root.resolve("commands"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
            )
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
        val selected = installation.resolve("installation")
        val manifest = Files.readString(selected.resolve("installation.json"))
        Files.writeString(selected.resolve("installation.json"), "broken manifest")
        Files.writeString(
            installation.resolve("recovery").resolve(selected.fileName).resolve("receipt.json"),
            "broken receipt",
        )
        val badManifest =
            assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(request))
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, badManifest.failure)
        assertEquals(selected, installation.resolve("installation").toRealPath())
        assertEquals("broken manifest", Files.readString(selected.resolve("installation.json")))
        Files.writeString(selected.resolve("installation.json"), manifest)
        val badReceipt = assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(request))
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, badReceipt.failure)
        assertEquals(
            "broken receipt",
            Files.readString(installation.resolve("recovery").resolve(selected.fileName).resolve("receipt.json")),
        )
    }

    @Test
    fun `corrupt active recovery rejects before candidate staging or prior retirement`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        val prior = installation.resolve("installation")
        val priorIdentity = observeInstallationFilesystemIdentity(prior)
        val retired = root.resolve("retired")
        Files.writeString(prior.resolve("bin/kast"), "#!/bin/sh\nprintf retired > '$retired'\n")
        Files.writeString(installation.resolve("recovery/installation/receipt.json"), "broken receipt")
        val rejected =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                executeFixtureInstallation(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
            )
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, rejected.failure)
        assertEquals(priorIdentity, observeInstallationFilesystemIdentity(prior))
        assertFalse(Files.exists(retired))
        assertFalse(Files.exists(installation.resolve("recovery/replacement")))
        Files.list(installation).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".install-") })
        }
    }
}
