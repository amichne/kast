package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationUpgradePreflightTest {
    @Test
    fun `rejected candidate leaves prior commands and service untouched`(@TempDir temporary: Path) {
        for (rejectedCommand in listOf("config", "--version")) {
            val root = Files.createDirectory(temporary.resolve(rejectedCommand)).toRealPath()
            val home = Files.createDirectory(root.resolve("home"))
            val install = root.resolve("installation")
            val commands = root.resolve("commands")
            val codex = Files.createDirectory(root.resolve("codex-home"))
            val first = releaseRequest(root, install, commands, home, codex, "1.2.3")
            Files.writeString(
                first.controlRoot.value.resolve("bin/kast"),
                """
                #!/bin/sh
                if [ "${'$'}1" = app-server ] && [ "${'$'}2" = disable ]; then
                  touch "${'$'}HOME/retirement-observed"
                fi
                exit 0
                """
                    .trimIndent() + "\n",
            )
            assertInstanceOf(InstallationOutcome.Complete::class.java, InstallationWorkflow.execute(first))
            val previous = install.resolve("current").toRealPath()
            Files.writeString(
                previous.resolve("config/workspaces.json"),
                Json.encodeToString(RegistryFixture(2, 0, emptyList())),
            )
            val second = releaseRequest(root, install, commands, home, codex, "1.2.4")
            Files.writeString(
                second.controlRoot.value.resolve("bin/kast"),
                """
                #!/bin/sh
                if [ "${'$'}1" = '$rejectedCommand' ]; then exit 1; fi
                exit 0
            """
                    .trimIndent() + "\n",
            )
            assertInstanceOf(InstallationOutcome.Rejected::class.java, InstallationWorkflow.execute(second))
            assertEquals(previous, install.resolve("current").toRealPath())
            assertEquals(previous.resolve("bin/kast-complete"), commands.resolve("kast").toRealPath())
            assertFalse(Files.exists(home.resolve("retirement-observed")), rejectedCommand)
        }
    }
}
