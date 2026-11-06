import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.VerifyControlDistributionTask

class VerifyControlDistributionTaskTest {
    @Test
    fun `control rejects a JVM library`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        val library = task.controlDirectory.get().asFile.toPath().resolve("legacy.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(library)).use { }
        assertEquals(
            "control product contains JVM libraries or bytecode",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }

    @Test
    fun `control rejects a second native executable`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        val root = task.controlDirectory.get().asFile.toPath()
        Files.copy(task.qualifiedControlExecutable.get().asFile.toPath(), root.resolve("extra-native"))
        assertEquals(
            "control product must contain exactly one native executable",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }

    @Test
    fun `CLI default and complete optional MCP composition satisfy the product layout`(@TempDir directory: Path) {
        guidanceFixture(directory).verify()
    }

    @Test
    fun `task tools need their guest receiver module`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        Files.delete(task.controlDirectory.get().asFile.toPath().resolve(
            "share/kast/task-vm/packaging/task_vm_guest.py"))
        assertEquals("control product is missing required entries",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    @Test
    fun `task launcher needs execute permission`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        val launcher = task.controlDirectory.get().asFile.toPath().resolve("share/kast/task-vm/bin/kast-task-vm")
        check(launcher.toFile().setExecutable(false, false))
        assertEquals("task launcher is not executable",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    @Test
    fun `optional MCP composition needs its complete transport`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        Files.delete(task.controlDirectory.get().asFile.toPath().resolve(
            "share/kast/agent-tools/compositions/mcp/plugins/kast/skills/kast/scripts/transport.py"))
        assertEquals("control product is missing required entries",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    @Test
    fun `default MCP transport and private environments reject`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        val root = task.controlDirectory.get().asFile.toPath()
        val defaultTransport = root.resolve("share/kast/agent-tools/plugins/kast/.mcp.json")
        Files.write(defaultTransport, byteArrayOf())
        assertEquals("default guidance contains automatic MCP or Python transport",
            assertThrows(IllegalStateException::class.java, task::verify).message)
        Files.delete(defaultTransport)
        val environment = Files.createDirectories(root.resolve("share/kast/.venv"))
        Files.write(environment.resolve("pyvenv.cfg"), byteArrayOf())
        assertEquals("control product contains a private environment or Python cache",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    @Test
    fun `default guidance rejects an undeclared hook`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        Files.write(task.controlDirectory.get().asFile.toPath().resolve(
            "share/kast/agent-tools/plugins/kast/hooks/foreign.sh"), byteArrayOf())
        assertEquals("default guidance contains an undeclared hook",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    private fun guidanceFixture(directory: Path): VerifyControlDistributionTask {
        val task = sizeFixture(directory)
        task.maximumInstalledBytes.set(23)
        val root = task.controlDirectory.get().asFile.toPath()
        val entries = listOf(
            "bin/kast", "bin/kast-codex", "bin/kast-mcp", "bin/kast-tool-rpc", "bin/kast-query",
            "share/kast/adapters/copilot/extension.mjs", "share/kast/adapters/pi/extension.ts",
            "share/kast/libexec/kast-daemon", "share/kast/libexec/kast-service", "share/kast/libexec/kast-management",
            "share/kast/install.sh", "share/kast/operation-registry.json", "share/kast/provider-catalog.json",
            "share/kast/configuration-schema.json", "share/kast/wire-schema.json", "share/kast/ide-host.json",
            "share/kast/knowledge/manifest.json", "share/kast/licenses/LICENSE",
            "share/kast/task-vm/bin/kast-task-vm", "share/kast/task-vm/bin/kast-task-vm.lock",
            "share/kast/task-vm/bin/setup-kast", "share/kast/task-vm/bin/setup-kast.lock",
            "share/kast/task-vm/packaging/task_vm.py", "share/kast/task-vm/packaging/task_vm_guest.py",
            "share/kast/task-vm/packaging/composed_bundle.py", "share/kast/task-vm/packaging/composition_platform.py",
            "share/kast/task-vm/packaging/managed_headless.py",
        ) + listOf("", "compositions/mcp/").flatMap { composition ->
            val prefix = "share/kast/agent-tools/$composition"
            listOf(".agents/plugins/marketplace.json", ".github/plugin/marketplace.json",
                "plugins/kast/.codex-plugin/plugin.json", "plugins/kast/.github/plugin/plugin.json",
                "plugins/kast/skills/kast/SKILL.md", "plugins/kast/skills/kast/public-cli.md",
                "plugins/kast/hooks/hooks.json", "plugins/kast/hooks/copilot.json", "plugins/kast/hooks/pi.ts",
                "plugins/kast/references/onboarding.md", "plugins/kast/references/query-examples.json").map { prefix + it }
        } + listOf(".mcp.json", "skills/kast/scripts/server.py", "skills/kast/scripts/server.py.lock",
            "skills/kast/scripts/service.py", "skills/kast/scripts/tasks.py", "skills/kast/scripts/transport.py",
            "skills/kast/scripts/access.py", "skills/kast/scripts/access.py.lock").map {
            "share/kast/agent-tools/compositions/mcp/plugins/kast/$it"
        }
        for (entry in entries) {
            val file = root.resolve(entry)
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) Files.write(file, byteArrayOf())
        }
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).forEach { it.toFile().setExecutable(true) } }
        Files.writeString(root.resolve("share/kast/native-control-v1"), "1\n")
        return task
    }

    @Test
    fun `archive byte cap rejects equality and reports its configured value`(@TempDir directory: Path) {
        val task = sizeFixture(directory)
        task.maximumArchiveBytes.set(3)
        assertEquals(
            "control archive is 3 bytes. It must be less than 3 bytes.",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }

    @Test
    fun `installed byte cap rejects equality and reports its configured value`(@TempDir directory: Path) {
        val task = sizeFixture(directory)
        task.maximumInstalledBytes.set(20)
        assertEquals(
            "installed control product is 20 bytes. It must be less than 20 bytes.",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }

    @Test
    fun `smaller payload passes byte caps and still needs required entries`(@TempDir directory: Path) {
        val task = sizeFixture(directory)
        assertEquals(
            "control product is missing required entries",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }

    private fun sizeFixture(directory: Path): VerifyControlDistributionTask {
        val root = Files.createDirectories(directory.resolve("product"))
        val query = Files.createDirectories(root.resolve("share/kast/libexec")).resolve("kast-management")
        Files.write(query, java.util.HexFormat.of().parseHex("cffaedfe0c000001000000000200000000000000"))
        val archive = Files.write(directory.resolve("archive.tar.gz"), byteArrayOf(1, 2, 3))
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("verifyControl", VerifyControlDistributionTask::class.java).get().apply {
            controlDirectory.set(root.toFile())
            controlArchive.set(archive.toFile())
            qualifiedControlExecutable.set(query.toFile())
            targetPlatform.set("macos-aarch64")
            maximumEntries.set(16384)
            maximumArchiveBytes.set(4)
            maximumInstalledBytes.set(21)
        }
    }

    @Test
    fun `query artifact rejects a substituted payload and a script producer`(@TempDir directory: Path) {
        val root = Files.createDirectories(directory.resolve("product"))
        val payload = Files.createDirectories(root.resolve("share/kast/libexec")).resolve("kast-management")
        val producer = directory.resolve("producer")
        Files.writeString(producer, "#!/bin/sh\nexec java Main\n")
        Files.writeString(payload, "#!/bin/sh\nexec java Other\n")
        val archive = Files.write(directory.resolve("archive.tar.gz"), byteArrayOf())
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("verifyControl", VerifyControlDistributionTask::class.java).get().apply {
            controlDirectory.set(root.toFile())
            controlArchive.set(archive.toFile())
            qualifiedControlExecutable.set(producer.toFile())
            targetPlatform.set("macos-aarch64")
            maximumEntries.set(16384)
            maximumArchiveBytes.set(64L * 1024 * 1024)
            maximumInstalledBytes.set(128L * 1024 * 1024)
        }
        assertEquals(
            "control payload differs from its native producer",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
        Files.copy(producer, payload, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(
            "control payload is not a native executable for macos-aarch64",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }
}
