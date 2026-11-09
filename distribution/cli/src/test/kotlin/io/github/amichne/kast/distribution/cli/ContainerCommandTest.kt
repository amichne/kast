package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerCommandTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `container payload admission rejects a changed helper or lock`() {
        val installation = temporary.toRealPath()
        val entries = listOf("bin/kast-container", "bin/kast-container.lock", "packaging/dev_container.py")
        val payloads = entries.map { relative ->
            val path = installation.resolve("share/kast/container/$relative")
            Files.createDirectories(path.parent)
            Files.writeString(path, relative)
            BundledPayload("share/kast/container/$relative", "sha256:${sha256(path)}", 420)
        }
        val manifest = BundledManifest(INSTALLATION_MANIFEST_SCHEMA_VERSION, installation.toString(), payloads)
        assertTrue(ContainerLauncher.Admitted.fromInstallation(installation, manifest) is ContainerLauncher.Admitted)
        for (entry in entries) {
            val path = installation.resolve("share/kast/container/$entry")
            Files.writeString(path, "changed")
            assertEquals(
                ContainerLauncher.Unavailable,
                ContainerLauncher.Admitted.fromInstallation(installation, manifest),
            )
            Files.writeString(path, entry)
        }
    }

    @Test
    fun `container delegates literal arguments to its existing owner`() {
        for (arguments in
            listOf(emptyList(), listOf("--help"), listOf("run", "--", "printf", "%s", "\$HOME; literal"))) {
            assertEquals(
                ManagementParsing.Selected(ManagementCommand.Container(arguments)),
                parseManagementCommand(listOf("container") + arguments),
            )
        }
    }

    @Test
    fun `root help exposes the container command`() {
        val help = parseManagementCommand(listOf("--help")) as ManagementParsing.Print
        assertTrue(help.text.contains("container"))
    }
}
