package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationUpgradePreflightTest {
    @Test
    fun `rejected candidate executable leaves prior commands and service untouched`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("candidate")).toRealPath()
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
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(first))
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
            if [ "${'$'}1" = '--version' ]; then exit 1; fi
            exit 0
            """
                .trimIndent() + "\n",
        )
        assertInstanceOf(InstallationOutcome.Rejected::class.java, executeFixtureInstallation(second))
        assertEquals(previous, install.resolve("current").toRealPath())
        assertFalse(Files.exists(commands.resolve("kast")))
        assertFalse(Files.exists(home.resolve("retirement-observed")))
    }
}
