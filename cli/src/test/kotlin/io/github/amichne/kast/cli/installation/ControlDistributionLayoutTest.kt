package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlDistributionLayoutTest {
    @Test
    fun `control verification admits the shipped knowledge bundle file count`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val outcome =
            InstallationWorkflow.execute(
                releaseRequest(
                    root,
                    root.resolve("installation"),
                    root.resolve("commands"),
                    Files.createDirectory(root.resolve("home")),
                    Files.createDirectory(root.resolve("codex-home")),
                    "1.2.3",
                    controlFileCount = 7_494,
                    mode = InstallationMode.PLAN,
                )
            )

        assertInstanceOf(InstallationOutcome.Complete::class.java, outcome)
    }

    @Test
    fun `control verification reports layout rejection above the file limit`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val outcome =
            InstallationWorkflow.execute(
                releaseRequest(
                    root,
                    root.resolve("installation"),
                    root.resolve("commands"),
                    Files.createDirectory(root.resolve("home")),
                    Files.createDirectory(root.resolve("codex-home")),
                    "1.2.3",
                    controlFileCount = 16_385,
                    mode = InstallationMode.PLAN,
                )
            )

        val rejected = assertInstanceOf(InstallationOutcome.Rejected::class.java, outcome)
        assertEquals(InstallationFailure.CONTROL_LIMIT_EXCEEDED, rejected.failure)
        assertEquals(16_384L, rejected.limit?.maximum)
        assertEquals(16_385L, rejected.limit?.observedAtLeast)
    }

    @Test
    fun `installation with the shipped knowledge file count is idempotent`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val request =
            releaseRequest(
                root,
                root.resolve("installation"),
                root.resolve("commands"),
                Files.createDirectory(root.resolve("home")),
                Files.createDirectory(root.resolve("codex-home")),
                "1.2.3",
                controlFileCount = 7_494,
            )

        assertInstanceOf(InstallationOutcome.Complete::class.java, InstallationWorkflow.execute(request))
        assertInstanceOf(InstallationOutcome.Complete::class.java, InstallationWorkflow.execute(request))
    }

    @Test
    fun `upgrade uses the new lifecycle authority to admit an older installation`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        val home = Files.createDirectory(root.resolve("home"))
        val codexHome = Files.createDirectory(home.resolve(".codex"))

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            InstallationWorkflow.execute(
                releaseRequest(
                    root,
                    installation,
                    commands,
                    home,
                    codexHome,
                    "1.2.3",
                    lifecycleInspectionExit = 17,
                )
            ),
        )
        val prior = installation.resolve(Files.readSymbolicLink(installation.resolve("current")))
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(
            prior.resolve("config/workspaces.json"),
            Json.encodeToString(RegistryFixture(2, 1, listOf(workspace.toString()))),
        )

        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            InstallationWorkflow.execute(releaseRequest(root, installation, commands, home, codexHome, "1.2.4")),
        )
    }
}
