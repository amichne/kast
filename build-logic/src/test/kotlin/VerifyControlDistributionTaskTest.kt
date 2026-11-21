import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import support.tasks.nativeQueryHeaderMatches
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.VerifyControlDistributionTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VerifyControlDistributionTaskTest {
    @Test
    fun `native inventory records accepted and rejected candidate identities`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        val report = directory.resolve("build/reports/distribution/control-native-inventory.json")
        task.verify()
        assertTrue(Files.isRegularFile(report), "native inventory must survive successful verification")
        val accepted = Json.parseToJsonElement(Files.readString(report)).jsonObject
        assertEquals("ACCEPTED", accepted.getValue("outcome").jsonPrimitive.content)
        assertEquals("1", accepted.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("macos-aarch64", accepted.getValue("target").jsonPrimitive.content)
        assertEquals("0", accepted.getValue("omittedCandidates").jsonPrimitive.content)
        val expectedHeader = "cffaedfe0c000001000000000200000000000000"
        val first = accepted.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("share/kast/libexec/kast-management", first.getValue("path").jsonPrimitive.content)
        assertEquals(expectedHeader, first.getValue("headerHex").jsonPrimitive.content)
        assertEquals("CONTROL", first.getValue("classification").jsonPrimitive.content)
        val bytes = java.util.HexFormat.of().parseHex(expectedHeader)
        val expectedDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        assertEquals(expectedDigest, first.getValue("sha256").jsonPrimitive.content)

        val extra = task.controlDirectory.get().asFile.toPath().resolve("extra-native")
        Files.write(extra, bytes)
        assertThrows(IllegalStateException::class.java, task::verify)
        val rejected = Json.parseToJsonElement(Files.readString(report)).jsonObject
        assertEquals("REJECTED", rejected.getValue("outcome").jsonPrimitive.content)
        val unexpected = rejected.getValue("candidates").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == "extra-native" }
        assertEquals("UNEXPECTED", unexpected.getValue("classification").jsonPrimitive.content)
        assertEquals(expectedDigest, unexpected.getValue("sha256").jsonPrimitive.content)
    }

    @Test
    fun `native diagnostic limits do not truncate the rejection inventory`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        val root = task.controlDirectory.get().asFile.toPath()
        repeat(65) { index -> Files.copy(task.qualifiedControlExecutable.get().asFile.toPath(), root.resolve("extra-$index")) }
        assertThrows(IllegalStateException::class.java, task::verify)
        val report = Json.parseToJsonElement(Files.readString(task.nativeInventoryReport.get().asFile.toPath())).jsonObject
        assertEquals("REJECTED", report.getValue("outcome").jsonPrimitive.content)
        assertEquals("66", report.getValue("executableCount").jsonPrimitive.content)
        assertEquals("66", report.getValue("candidateCount").jsonPrimitive.content)
        assertEquals("2", report.getValue("omittedCandidates").jsonPrimitive.content)
        assertEquals(64, report.getValue("candidates").jsonArray.size)
    }

    @Test
    fun `each native Control target requires its own operating system and CPU header`() {
        val headers = mapOf(
            "linux-aarch64" to "7f454c460201010000000000000000000200b700",
            "linux-x86_64" to "7f454c4602010100000000000000000002003e00",
            "macos-aarch64" to "cffaedfe0c000001000000000200000000000000",
            "macos-x86_64" to "cffaedfe07000001030000000200000000000000",
        ).mapValues { java.util.HexFormat.of().parseHex(it.value) }
        for ((platform, header) in headers) {
            for (target in headers.keys) assertEquals(platform == target, nativeQueryHeaderMatches(header, target), "$platform -> $target")
            assertFalse(nativeQueryHeaderMatches(header.copyOf(19), platform))
            assertFalse(nativeQueryHeaderMatches(header, "unknown"))
        }
    }

    @Test
    fun `control rejects JVM bytecode outside lib`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        val library = task.controlDirectory.get().asFile.toPath().resolve("legacy.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(library)).use { }
        assertEquals(
            "control product contains JVM bytecode outside lib",
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
    fun `control rejects retired payloads`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        val root = task.controlDirectory.get().asFile.toPath()
        for (name in listOf("container", "task-vm", "knowledge")) {
            val directory = Files.createDirectory(root.resolve("share/kast/$name"))
            assertEquals("control product contains a retired payload",
                assertThrows(IllegalStateException::class.java, task::verify).message)
            Files.delete(directory)
        }
    }

    @Test
    fun `optional MCP composition needs its native launcher`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        Files.delete(task.controlDirectory.get().asFile.toPath().resolve(
            "share/kast/agent-tools/compositions/mcp/plugins/kast/.mcp.json"))
        assertEquals("control product is missing required entries",
            assertThrows(IllegalStateException::class.java, task::verify).message)
    }

    @Test
    fun `default MCP transport and private environments reject`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        val root = task.controlDirectory.get().asFile.toPath()
        val defaultTransport = root.resolve("share/kast/agent-tools/plugins/kast/.mcp.json")
        Files.write(defaultTransport, byteArrayOf())
        assertEquals("default guidance contains automatic MCP transport",
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
            "bin/kast", "bin/kast-codex", "bin/kast-mcp", "bin/kast-query",
            "share/kast/libexec/kast-daemon", "share/kast/libexec/kast-service", "share/kast/libexec/kast-management",
            "share/kast/install.sh", "share/kast/operation-registry.json", "share/kast/provider-catalog.json",
            "share/kast/configuration-schema.json", "share/kast/wire-schema.json", "share/kast/ide-host.json",
            "share/kast/licenses/LICENSE", "share/kast/frontend.json", "lib/kast-cli.jar",
        ) + listOf("", "compositions/mcp/").flatMap { composition ->
            val prefix = "share/kast/agent-tools/$composition"
            listOf(".agents/plugins/marketplace.json", "plugins/kast/.codex-plugin/plugin.json",
                "plugins/kast/skills/kast/SKILL.md", "plugins/kast/skills/kast/public-cli.md",
                "plugins/kast/hooks/hooks.json",
                "plugins/kast/references/onboarding.md", "plugins/kast/references/query-examples.json").map { prefix + it }
        } + "share/kast/agent-tools/compositions/mcp/plugins/kast/.mcp.json"
        for (entry in entries) {
            val file = root.resolve(entry)
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) Files.write(file, byteArrayOf())
        }
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).forEach { it.toFile().setExecutable(true) } }
        Files.writeString(root.resolve("share/kast/jvm-control-v1"), "1\n")
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
            "control payload differs from its Go producer",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
        Files.copy(producer, payload, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(
            "control payload is not a native executable for macos-aarch64",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }
}
