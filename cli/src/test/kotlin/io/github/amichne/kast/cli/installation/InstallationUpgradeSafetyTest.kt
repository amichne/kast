package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationUpgradeSafetyTest {
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
                InstallationWorkflow.execute(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
            )
            val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
            val priorRegistry = Json.encodeToString(RegistryFixture(2, 1, listOf(root.toString())))
            Files.writeString(
                prior.resolve("config/workspaces.json"),
                priorRegistry,
            )
            if (brokenRetirement) Files.writeString(prior.resolve("bin/kast"), "#!/bin/sh\nexit 17\n")
            val upgraded =
                InstallationWorkflow.execute(
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
            val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
            assertEquals(prior, selected)
            assertEquals(prior.resolve("bin/kast-complete"), commands.resolve("kast").toRealPath())
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
        assertInstanceOf(InstallationOutcome.Complete::class.java, InstallationWorkflow.execute(request))
        val selected = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val manifest = Files.readString(selected.resolve("installation.json"))
        Files.writeString(selected.resolve("installation.json"), "broken manifest")
        Files.writeString(
            installation.resolve("recovery").resolve(selected.fileName).resolve("receipt.json"),
            "broken receipt",
        )
        val badManifest =
            assertInstanceOf(InstallationOutcome.Rejected::class.java, InstallationWorkflow.execute(request))
        assertEquals(InstallationFailure.CANDIDATE_EXISTING_UNTRUSTED, badManifest.failure)
        assertEquals(selected, installation.resolve("current").toRealPath())
        assertEquals("broken manifest", Files.readString(selected.resolve("installation.json")))
        Files.writeString(selected.resolve("installation.json"), manifest)
        val badReceipt =
            assertInstanceOf(InstallationOutcome.Rejected::class.java, InstallationWorkflow.execute(request))
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, badReceipt.failure)
        assertEquals(
            "broken receipt",
            Files.readString(installation.resolve("recovery").resolve(selected.fileName).resolve("receipt.json")),
        )
    }

    @Test
    fun `corrupt candidate recovery rejects before prior retirement`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(root.resolve("codex"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            InstallationWorkflow.execute(releaseRequest(root, installation, commands, home, codexHome, "1.2.3")),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val priorManifest = Files.readString(prior.resolve("installation.json"))
        Files.writeString(prior.resolve("installation.json"), "broken manifest")
        val candidateRequest = releaseRequest(root, installation, commands, home, codexHome, "1.2.4")
        val rejectedAdmission =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(candidateRequest),
            )
        assertEquals(InstallationFailure.PRIOR_ADMISSION_EXIT_REJECTED, rejectedAdmission.failure)
        val candidate =
            Files.list(installation.resolve("versions")).use { roots ->
                roots.filter { it != prior }.findFirst().orElseThrow()
            }
        val receipt = installation.resolve("recovery").resolve(candidate.fileName).resolve("receipt.json")
        Files.writeString(receipt, "broken receipt")
        Files.writeString(prior.resolve("installation.json"), priorManifest)
        val rejectedRecovery =
            assertInstanceOf(
                InstallationOutcome.Rejected::class.java,
                InstallationWorkflow.execute(candidateRequest),
            )
        assertEquals(InstallationFailure.RECOVERY_REQUIRED, rejectedRecovery.failure)
        assertEquals(prior, installation.resolve("current").toRealPath())
        assertEquals(prior.resolve("bin/kast-complete"), commands.resolve("kast").toRealPath())
        assertEquals("broken receipt", Files.readString(receipt))
    }
}
