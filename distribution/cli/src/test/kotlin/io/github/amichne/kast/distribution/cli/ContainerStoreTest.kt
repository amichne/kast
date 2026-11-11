package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the existing read-accessible manager root need not become a private profile`() {
        val root = Files.createDirectory(temporary.toRealPath().resolve("manager"))
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals(Refinement.Refined(42), withContainerLock(root) { Refinement.Refined(42) })
        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
            Files.getPosixFilePermissions(root),
        )
    }

    @Test
    fun `create owns one workspace trust and private Gradle state over a shared immutable runtime`() {
        val root = temporary.toRealPath().resolve("manager")
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val store = ContainerStore(root, platform)
        val runtime = runtime(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace & space")).toRealPath()
        val first = store.create(ContainerCommand.Create("backend", null, null, false), workspace, runtime)
        assertTrue(first is Refinement.Refined, first.toString())
        val created = (first as Refinement.Refined).value
        assertEquals(workspace, created.workspace)
        assertEquals(created.home.resolve(".gradle"), created.gradleHome)
        assertEquals(runtime.root, created.runtime.root)
        assertEquals(
            created.id,
            (store.route(Files.createDirectory(workspace.resolve("src"))) as Refinement.Refined).value.id,
        )
        assertEquals(created.id, (store.select("backend") as Refinement.Refined).value.id)
        assertEquals(created.id, (store.select(created.id.text) as Refinement.Refined).value.id)
        assertEquals(
            Refinement.Rejected(ContainerFailure.WORKSPACE_EXISTS),
            store.create(ContainerCommand.Create("duplicate", workspace.toString(), null, false), workspace, runtime),
        )
        val other = Files.createDirectory(temporary.resolve("second")).toRealPath()
        val second =
            store.create(ContainerCommand.Create("frontend", null, null, false), other, runtime) as Refinement.Refined
        assertEquals(runtime.root, second.value.runtime.root)
        assertNotEquals(created.home, second.value.home)
        val trust =
            Files.walk(created.home).use {
                it.filter { p -> p.fileName.toString() == "trusted-paths.xml" }.toList().single()
            }
        assertTrue(Files.readString(trust).contains("workspace &amp; space"))
        assertFalse(Files.exists(created.directory.resolve("runtime")))
        assertEquals(Refinement.Refined(ContainerState.ENABLED), containerDisposition(created))
        writeContainerRecord(created.stopped, ContainerStopRecord(created.id.text))
        assertEquals(Refinement.Refined(ContainerState.STOPPED), containerDisposition(created))
        assertEquals(2, (store.list() as Refinement.Refined).value.size)
        Files.writeString(trust, containerTrustXml(other))
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_RECORD), store.select(created.id.text))
    }

    @Test
    fun `query readers prevent shutdown and erasure until their exact lifetime ends`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val created =
            (store.create(ContainerCommand.Create("backend", null, null, false), workspace, runtime(store))
                    as Refinement.Refined)
                .value
        val activeQuery = (ContainerQueryLease.acquire(created) as Refinement.Refined).value
        activeQuery.use {
            var erased = false
            val removal =
                ContainerQueryLease.whileIdle(created) {
                    erased = true
                    Refinement.Refined(Unit)
                }
            assertEquals(Refinement.Rejected(ContainerFailure.BUSY), removal)
            assertFalse(erased)
            assertTrue(Files.isDirectory(created.home))
        }
        assertEquals(Refinement.Refined(Unit), ContainerQueryLease.whileIdle(created) { Refinement.Refined(Unit) })
    }

    @Test
    fun `explicit external Gradle home is preserved and dangerous ambient launch options are removed`() {
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val store = ContainerStore(temporary.toRealPath().resolve("manager"), platform)
        val runtime = runtime(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val container =
            (store.create(ContainerCommand.Create("shared", null, gradle.toString(), true), workspace, runtime)
                    as Refinement.Refined)
                .value
        val env =
            containerEnvironment(
                container,
                mapOf(
                    "HOME" to "/foreign",
                    "KAST_INSTALL_IDEA_HOME" to "/desktop",
                    "KAST_OUTPUT_TOON" to "0",
                    "JAVA_TOOL_OPTIONS" to "-Duser.home=/foreign",
                    "GRADLE_OPTS" to "unsafe",
                    "PATH" to "/usr/bin",
                ),
            )
        assertEquals(gradle.toString(), env["GRADLE_USER_HOME"])
        assertEquals("0", env["KAST_OUTPUT_TOON"])
        assertEquals(container.home.toString(), env["HOME"])
        assertEquals(container.configuration.toString(), env["KAST_CONFIGURATION_FILE"])
        assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"))
        assertFalse(env.containsKey("GRADLE_OPTS"))
        assertFalse(env.containsKey("KAST_INSTALL_IDEA_HOME"))
        assertTrue(Files.isDirectory(gradle))
    }

    @Test
    fun `malformed identity and foreign registry entries cannot become routed containers`() {
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val store = ContainerStore(temporary.toRealPath().resolve("manager"), platform)
        val runtime = runtime(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_NAME),
            store.create(ContainerCommand.Create("../../outside", null, null, false), workspace, runtime),
        )
        assertEquals(Refinement.Refined(emptyList<AdmittedContainer>()), store.list())
        Files.createDirectory(
            store.containers,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(containerDirectoryMode),
        )
        Files.createSymbolicLink(store.containers.resolve("a".repeat(32)), workspace)
        assertTrue(store.list() is Refinement.Rejected)
        assertTrue(Files.isDirectory(workspace))
    }

    @Test
    fun `XML-normalized and forbidden workspace characters reject before profile publication`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        assertFalse(validContainerTrustPath(Path.of("workspace\uFFFE")))
        assertTrue(validContainerTrustPath(Path.of("workspace & space")))
        for (suffix in listOf("\t", "\u0001")) {
            val workspace = Files.createDirectory(temporary.resolve("workspace$suffix")).toRealPath()
            assertEquals(
                Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE),
                store.admitCreation(ContainerCommand.Create("backend", null, null, false), workspace),
            )
        }
        assertFalse(Files.exists(store.containers))
    }

    @Test
    fun `digest mismatch and traversal reject before publication`() {
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val archive = temporary.resolve("wrong.zip")
        Files.writeString(archive, "not an archive")
        val runtimes = temporary.resolve("runtimes")
        assertEquals(
            Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH),
            importContainerRuntime(archive, "0".repeat(64), runtimes, "1.2.3", platform),
        )
        assertFalse(Files.exists(runtimes))
        val manifest = manifest(platform, listOf(RuntimeArchiveEntry.File("../escape", 1, "a".repeat(64), false)))
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE),
            admitRuntimeManifest(manifest, "1.2.3", platform),
        )
        assertFalse(Files.exists(temporary.resolve("escape")))
    }

    @Test
    fun `encoded records have independent required discriminator and schema shape`() {
        val completed =
            containerJson
                .parseToJsonElement(
                    containerJson.encodeToString<ContainerDocument>(
                        ContainerDocument.Completed(ContainerDisposition.STOPPED, "abc")
                    )
                )
                .jsonObject
        assertEquals(setOf("type", "operation", "id"), completed.keys)
        assertEquals("complete", completed.getValue("type").jsonPrimitive.content)
        assertEquals("STOPPED", completed.getValue("operation").jsonPrimitive.content)
        assertEquals("abc", completed.getValue("id").jsonPrimitive.content)
        val stop = containerJson.parseToJsonElement(containerJson.encodeToString(ContainerStopRecord("a"))).jsonObject
        assertEquals(setOf("id", "schemaVersion"), stop.keys)
        assertEquals("1", stop.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("a", stop.getValue("id").jsonPrimitive.content)
        assertTrue(ContainerName.parse("bad/name") is Refinement.Rejected)
        assertTrue(ContainerId.parse("backend") is Refinement.Rejected)
        val invalidRemove = parseManagementCommand(listOf("container", "rm", "backend"))
        assertTrue(invalidRemove is ManagementParsing.Print && invalidRemove.error)
        assertEquals(
            Refinement.Rejected(ContainerFailure.UNSUPPORTED_PLATFORM),
            ContainerPlatform.current("Linux", "riscv64"),
        )
    }

    private fun runtime(store: ContainerStore): AdmittedContainerRuntime {
        Files.createDirectories(
            store.root,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(containerDirectoryMode),
        )
        val platform = store.platform
        val cpu = if (platform.publicName.endsWith("aarch64")) "aarch64" else "amd64"
        val mac = platform.publicName.startsWith("macos")
        val home = platform.host
        val product =
            containerJson.encodeToString(
                Product(
                    "262.10315.125",
                    "KastTest2026.2",
                    listOf(Launch(if (mac) "macOS" else "Linux", cpu, if (mac) "../MacOS/idea" else "bin/idea")),
                )
            )
        val payload =
            linkedMapOf(
                "$home/${if (mac) "Resources/" else ""}product-info.json" to product.toByteArray(),
                "$home/${if (mac) "MacOS/idea" else "bin/idea"}" to "fixture-not-a-host".toByteArray(),
                "$home/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar" to
                    "bootstrap".toByteArray(),
                "components/plugins/kast-ide-hosted/lib/kast-ide-hosted.jar" to "fixture-not-a-plugin".toByteArray(),
            )
        val records = payload.map { (path, bytes) ->
            RuntimeArchiveEntry.File(path, bytes.size.toLong(), digest(bytes), path.endsWith("/idea"))
        }
        val manifest = manifest(platform, records)
        val zip = Files.createTempFile(temporary, "payload-", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(containerJson.encodeToString(manifest).toByteArray())
            output.closeEntry()
            for ((path, bytes) in payload) {
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        val imported = importContainerRuntime(zip, sha256(zip), store.runtimes, "1.2.3", platform)
        assertTrue(imported is Refinement.Refined, imported.toString())
        return (imported as Refinement.Refined).value
    }

    private fun manifest(platform: ContainerPlatform, files: List<RuntimeArchiveEntry>): RuntimeArchiveManifest =
        RuntimeArchiveManifest(
            "1.2.3",
            platform,
            "262.10315.125",
            "a".repeat(64),
            digest("bootstrap".toByteArray()),
            files,
        )

    private fun digest(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Serializable
    private data class Product(val buildNumber: String, val dataDirectoryName: String, val launch: List<Launch>)

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)
}
