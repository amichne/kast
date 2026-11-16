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
    fun `ELF dynamic metadata needs a consistent file and memory mapping`(@TempDir directory: Path) {
        val path = directory.resolve("extension.so")
        val library = completeElfLibrary(java.util.HexFormat.of().parseHex(
            "7f454c4602010100000000000000000003003e00010000000090000000000000"))
        Files.write(path, library)
        assertEquals(support.tasks.ElfLibraryAdmission.LIBRARY, support.tasks.admitElfLibrary(path, "linux-x86_64"))
        for ((field, value) in listOf(136 to 0L, 104 to 128L, 160 to 16L, 136 to Long.MAX_VALUE, 80 to Long.MAX_VALUE)) {
            val malformed = library.copyOf()
            java.nio.ByteBuffer.wrap(malformed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(field, value)
            Files.write(path, malformed)
            assertEquals(support.tasks.ElfLibraryAdmission.MALFORMED,
                support.tasks.admitElfLibrary(path, "linux-x86_64"), "field=$field value=$value")
        }
    }

    @Test
    fun `ELF dependency admission uses dynamic metadata rather than entry point alone`(@TempDir directory: Path) {
        val task = guidanceFixture(directory)
        task.maximumInstalledBytes.set(4096)
        task.targetPlatform.set("linux-x86_64")
        Files.write(task.qualifiedControlExecutable.get().asFile.toPath(),
            java.util.HexFormat.of().parseHex("7f454c4602010100000000000000000002003e00"))
        val dependency = task.controlDirectory.get().asFile.toPath()
            .resolve("share/kast/mcp-dependencies/site-packages/extension.so")
        Files.createDirectories(dependency.parent)
        // ELF64 ET_DYN with a nonzero entry point, PT_LOAD and PT_DYNAMIC, no PT_INTERP or DF_1_PIE.
        val library = java.nio.ByteBuffer.allocate(256).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(java.util.HexFormat.of().parseHex("7f454c4602010100000000000000000003003e00010000000090000000000000"))
            putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 2)
            putInt(64, 1); putInt(68, 5); putLong(96, 256); putLong(104, 256)
            putInt(120, 2); putLong(128, 192); putLong(136, 192); putLong(152, 32); putLong(160, 32)
            putLong(192, 0x6ffffffb); putLong(200, 0)
        }.array()
        Files.write(dependency, library)
        task.verify()

        for (bytes in listOf(
            library.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(200, 0x08000000) },
            library.copyOf().apply {
                java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                    putLong(24, 0); putLong(200, 0x08000000)
                }
            },
            library.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(64, 3) },
            library.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE) },
            library.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(18, 183) },
            library.copyOf().apply { java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(208, 1) },
            library.copyOf(80),
        )) {
            Files.write(dependency, bytes)
            assertEquals("control product must contain exactly one native executable",
                assertThrows(IllegalStateException::class.java, task::verify).message)
        }
    }

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
        assertEquals("OUTSIDE_DEPENDENCIES", unexpected.getValue("classification").jsonPrimitive.content)
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
    fun `MCP shared libraries are not additional Control executables`(@TempDir directory: Path) {
        val cases = listOf(
            Triple("macos-aarch64", "cffaedfe0c000001000000000200000000000000", "cffaedfe0c000001000000000600000000000000"),
            Triple("macos-aarch64", "cffaedfe0c000001000000000200000000000000", "cffaedfe0c000001000000000800000000000000"),
            Triple("linux-aarch64", "7f454c460201010000000000000000000200b700", "7f454c460201010000000000000000000300b700010000000000000000000000"),
            Triple("linux-x86_64", "7f454c4602010100000000000000000002003e00", "7f454c4602010100000000000000000003003e00010000000000000000000000"),
        )
        for ((index, case) in cases.withIndex()) {
            val (platform, executable, library) = case
            val task = guidanceFixture(Files.createDirectory(directory.resolve("case-$index")))
            task.maximumInstalledBytes.set(4096)
            task.targetPlatform.set(platform)
            Files.write(task.qualifiedControlExecutable.get().asFile.toPath(), java.util.HexFormat.of().parseHex(executable))
            val root = task.controlDirectory.get().asFile.toPath()
            val dependency = root.resolve("share/kast/mcp-dependencies/site-packages/extension.so")
            Files.createDirectories(dependency.parent)
            val libraryBytes = java.util.HexFormat.of().parseHex(library).let { header ->
                if (platform.startsWith("linux")) completeElfLibrary(header) else header
            }
            Files.write(dependency, libraryBytes)
            task.verify()

            val outsideDependencies = root.resolve("extension.so")
            Files.move(dependency, outsideDependencies)
            assertEquals("control product must contain exactly one native executable",
                assertThrows(IllegalStateException::class.java, task::verify).message)
            Files.move(outsideDependencies, dependency)

            // A native executable remains forbidden even when named like a dependency.
            val disguised = if (platform.startsWith("linux")) libraryBytes.copyOf().apply {
                java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(200, 0x08000000)
            } else java.util.HexFormat.of().parseHex(executable)
            Files.write(dependency, disguised)
            assertEquals("control product must contain exactly one native executable",
                assertThrows(IllegalStateException::class.java, task::verify).message)
        }
    }

    private fun completeElfLibrary(header: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(256).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(header)
            putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 2)
            putInt(64, 1); putInt(68, 5); putLong(96, 256); putLong(104, 256)
            putInt(120, 2); putLong(128, 192); putLong(136, 192); putLong(152, 32); putLong(160, 32)
            putLong(192, 0x6ffffffb); putLong(200, 0)
        }.array()

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
            "share/kast/licenses/LICENSE",
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
