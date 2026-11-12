package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerPublicationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `passive status isolates corrupt neighbors without locks trust or startup`() {
        val root = temporary.toRealPath().resolve("kast")
        val store =
            ContainerStore(
                root.resolveSibling("kast-workspaces"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        assertEquals(
            Observation.verified(emptyList<ManagedWorkspaceStatus>()),
            readWorkspaceStatuses(root, WorkspaceScope.All, temporary),
        )
        assertFalse(Files.exists(store.root))
        val containers = retirementContainers(store, temporary)
        for (container in containers) Files.writeString(container.workspace.resolve("settings.gradle.kts"), "")
        val corrupt = store.containers.resolve("a".repeat(64))
        assertTrue(privateContainerDirectory(corrupt) is Refinement.Refined)
        Files.writeString(corrupt.resolve("container.json"), "invalid")
        val before =
            Files.walk(store.root).use { paths ->
                paths.toList().associateWith { if (Files.isRegularFile(it)) sha256(it) else "directory" }
            }
        val all = readWorkspaceStatuses(root, WorkspaceScope.All, temporary)
        assertEquals(ObservationState.VERIFIED, all.state)
        val entries = requireNotNull(all.value)
        assertEquals(3, entries.size)
        assertEquals(
            listOf(ManagedWorkspaceStatus.Rejected(corrupt.fileName.toString(), ContainerFailure.INVALID_RECORD)),
            entries.filterIsInstance<ManagedWorkspaceStatus.Rejected>(),
        )
        for (recorded in entries.filterIsInstance<ManagedWorkspaceStatus.Recorded>()) {
            assertEquals(ObservationState.VERIFIED, recorded.runtimeArchiveSha256.state)
            assertEquals(ObservationState.UNAVAILABLE, recorded.host.state)
        }
        val selected = readWorkspaceStatuses(root, WorkspaceScope.Explicit(containers.first().workspace), temporary)
        assertEquals(
            listOf(containers.first().workspace.toString()),
            requireNotNull(selected.value).filterIsInstance<ManagedWorkspaceStatus.Recorded>().map { it.workspace },
        )
        assertEquals(
            before,
            Files.walk(store.root).use { paths ->
                paths.toList().associateWith { if (Files.isRegularFile(it)) sha256(it) else "directory" }
            },
        )
    }

    @Test
    fun `scoped stop preserves other workspaces and permits explicit restart`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val containers = retirementContainers(store, temporary)
        for (container in containers) Files.writeString(container.workspace.resolve("settings.gradle.kts"), "")
        val selected = containers.first()
        (ContainerQueryLease.acquire(selected) as Refinement.Refined).value.use {
            assertEquals(
                Refinement.Rejected(ContainerFailure.BUSY),
                stopManagedWorkspaces(store, WorkspaceScope.Current, selected.workspace) {
                    error("retired active request")
                },
            )
        }
        assertEquals(
            Refinement.Refined(listOf(selected.workspace.toString())),
            stopManagedWorkspaces(store, WorkspaceScope.Current, selected.workspace) {
                assertEquals(selected.id, it.id)
                assertEquals(Refinement.Rejected(ContainerFailure.BUSY), ContainerQueryLease.acquire(it))
                Refinement.Refined(Unit)
            },
        )
        assertFalse(Files.exists(selected.stopped))
        assertFalse(Files.exists(store.root.resolve(SHUTDOWN_FENCE)))
        assertFalse(Files.exists(containers.last().directory.resolve("generation-retirement.json")))
        assertEquals(Refinement.Refined(Unit), resumeContainerState(selected))
        (ContainerQueryLease.acquire(selected) as Refinement.Refined).value.close()
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE),
            stopManagedWorkspaces(store, WorkspaceScope.Current, temporary) { error("unscoped stop") },
        )
    }

    @Test
    fun `unpublished staging does not consume workspace capacity`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        assertTrue(privateContainerDirectory(store.containers) is Refinement.Refined)
        repeat(1025) { Files.createDirectory(store.containers.resolve(".creating-$it")) }
        assertEquals(Refinement.Refined(emptyList<ContainerStoreEntry>()), store.enumerate())
    }

    @Test
    fun `private profile staging leaves registry publication independent and cleans a losing caller`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val runtime = containerRuntimeFixture(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace"))
        val creation =
            (store.admitCreation(ContainerCommand.Create("one", null, null, false), workspace) as Refinement.Refined)
                .value
        assertEquals(
            Refinement.Refined(Unit),
            store.withStagedCreation(creation, runtime) { first ->
                assertFalse(Files.exists(creation.directory))
                assertEquals(Refinement.Refined(Unit), withContainerLock(store.root) { Refinement.Refined(Unit) })
                store.withStagedCreation(creation, runtime) { second ->
                    assertNotEquals(first.staging, second.staging)
                    assertTrue(withContainerLock(store.root) { store.publishCreation(first) } is Refinement.Refined)
                    val original = Files.readString(creation.directory.resolve("container.json"))
                    assertEquals(
                        Refinement.Rejected(ContainerFailure.WORKSPACE_EXISTS),
                        withContainerLock(store.root) { store.publishCreation(second) },
                    )
                    assertEquals(original, Files.readString(creation.directory.resolve("container.json")))
                    Refinement.Refined(Unit)
                }
            },
        )
        Files.list(store.containers).use { assertEquals(listOf(creation.directory), it.toList()) }
    }
}

class ContainerStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `create owns one workspace trust and private Gradle state over a shared immutable runtime`() {
        val root = temporary.toRealPath().resolve("manager")
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val store = ContainerStore(root, platform)
        val runtime = containerRuntimeFixture(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace & space")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
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
        assertEquals(2, (store.enumerate() as Refinement.Refined).value.size)
        Files.writeString(trust, containerTrustXml(other))
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_RECORD), store.select(created.id.text))
    }

    @Test
    fun `an unreadable neighbor cannot prevent exact workspace selection or nested root creation`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val runtime = containerRuntimeFixture(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val parent =
            (store.create(ContainerCommand.Create("parent", null, null, false), workspace, runtime)
                    as Refinement.Refined)
                .value
        val foreign = Files.writeString(store.containers.resolve("foreign"), "unreadable neighbor")
        assertEquals(parent.id, (store.select(parent.id.text) as Refinement.Refined).value.id)
        assertEquals(parent.id, (store.route(workspace) as Refinement.Refined).value.id)
        val nested = Files.createDirectory(workspace.resolve("nested"))
        Files.createDirectory(nested.resolve(".git"))
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE), store.route(nested))
        Files.writeString(nested.resolve("settings.gradle"), "")
        assertEquals(Refinement.Rejected(ContainerFailure.NOT_FOUND), store.route(nested))
        val child =
            (store.create(ContainerCommand.Create("child", null, null, false), nested, runtime) as Refinement.Refined)
                .value
        assertNotEquals(parent.id, child.id)
        assertEquals(child.id, (store.route(nested) as Refinement.Refined).value.id)
        val entries = (store.enumerate() as Refinement.Refined).value
        assertEquals(
            setOf(parent.id, child.id),
            entries.filterIsInstance<ContainerStoreEntry.Admitted>().map { it.workspace.id }.toSet(),
        )
        assertEquals(
            ContainerStoreEntry.Rejected(foreign, ContainerFailure.INVALID_ID),
            entries.filterIsInstance<ContainerStoreEntry.Rejected>().single(),
        )
        assertEquals("unreadable neighbor", Files.readString(foreign))
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
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val activeQuery = (ContainerQueryLease.acquire(created) as Refinement.Refined).value
        activeQuery.use {
            val second = ContainerQueryLease.acquire(created)
            assertTrue(second is Refinement.Refined, "concurrent readers share the native lock: $second")
            (second as Refinement.Refined).value.close()
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
        val runtime = containerRuntimeFixture(store)
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
                    "KAST_WORKSPACE_PREPARATION_WAIT" to "73000",
                    "JAVA_TOOL_OPTIONS" to "-Duser.home=/foreign",
                    "GRADLE_OPTS" to "unsafe",
                    "PATH" to "/usr/bin",
                ),
            )
        assertEquals(gradle.toString(), env["GRADLE_USER_HOME"])
        assertEquals("0", env["KAST_OUTPUT_TOON"])
        assertEquals("73000", env["KAST_WORKSPACE_PREPARATION_WAIT"])
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
        val runtime = containerRuntimeFixture(store)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_NAME),
            store.create(ContainerCommand.Create("../../outside", null, null, false), workspace, runtime),
        )
        assertEquals(Refinement.Refined(emptyList<ContainerStoreEntry>()), store.enumerate())
        Files.createDirectory(
            store.containers,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(containerDirectoryMode),
        )
        Files.createSymbolicLink(store.containers.resolve("a".repeat(32)), workspace)
        assertEquals(
            ContainerStoreEntry.Rejected(store.containers.resolve("a".repeat(32)), ContainerFailure.FOREIGN_STATE),
            (store.enumerate() as Refinement.Refined).value.single(),
        )
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
        val manifest =
            containerRuntimeManifestFixture(
                platform,
                listOf(RuntimeArchiveEntry.File("../escape", 1, "a".repeat(64), false)),
            )
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE),
            admitRuntimeManifest(manifest, "1.2.3", platform),
        )
        assertFalse(Files.exists(temporary.resolve("escape")))
    }

    @Test
    fun `legacy stop records retain their required schema shape`() {
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
}

class ContainerPreparationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `expiry and cancellation preserve the competing preparation owner`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val container =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val allowance = (io.github.amichne.kast.kernel.ElapsedTimeAllowance.admitMillis(40) as Refinement.Refined).value
        (ContainerQueryLease.acquire(container) as Refinement.Refined).value.use { lease ->
            withCompetingPreparation(container) { competing ->
                for (expected in
                    listOf(ContainerFailure.PREPARATION_WAIT_EXPIRED, ContainerFailure.CANCELLED_TERMINATE)) {
                    val waiting =
                        object : ContainerPreparationWait {
                            private var elapsed = Duration.ZERO

                            override fun elapsed(): Duration = elapsed

                            override fun pause(duration: Duration) {
                                elapsed += duration
                            }

                            override fun admitActive(): Refinement<Unit, ContainerFailure> =
                                if (expected == ContainerFailure.CANCELLED_TERMINATE && elapsed > Duration.ZERO)
                                    Refinement.Rejected(ContainerFailure.CANCELLED_TERMINATE)
                                else Refinement.Refined(Unit)
                        }
                    assertEquals(
                        Refinement.Rejected(expected),
                        withContainerPreparationLock<Unit>(lease, allowance, waiting) {
                            error("preparation without ownership")
                        },
                    )
                    assertTrue(competing.isValid)
                    assertEquals(
                        if (expected == ContainerFailure.PREPARATION_WAIT_EXPIRED) 40.milliseconds else 25.milliseconds,
                        waiting.elapsed(),
                    )
                }
            }
        }
        assertEquals(Refinement.Refined(Unit), ContainerQueryLease.whileIdle(container) { Refinement.Refined(Unit) })
    }

    @Test
    fun `preparation waits under a reader lease and retires no other query`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val container =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val allowance =
            (io.github.amichne.kast.kernel.ElapsedTimeAllowance.admitMillis(100) as Refinement.Refined).value
        val lease = (ContainerQueryLease.acquire(container) as Refinement.Refined).value
        lease.use {
            withCompetingPreparation(container) { competing ->
                val waiting =
                    object : ContainerPreparationWait {
                        private var elapsed = Duration.ZERO

                        override fun elapsed(): Duration = elapsed

                        override fun pause(duration: Duration) {
                            elapsed += duration
                            competing.release()
                        }

                        override fun admitActive(): Refinement<Unit, ContainerFailure> = Refinement.Refined(Unit)
                    }
                val result =
                    withContainerPreparationLock(lease, allowance, waiting) { remaining ->
                        assertEquals(75.milliseconds, remaining.duration)
                        assertEquals(
                            Refinement.Rejected(ContainerFailure.BUSY),
                            ContainerQueryLease.whileIdle<Unit>(container) {
                                error("retirement during preparation")
                            },
                        )
                        Refinement.Refined("prepared")
                    }
                assertEquals(Refinement.Refined("prepared"), result)
                assertTrue(waiting.elapsed() > Duration.ZERO)
            }
        }
        assertEquals(Refinement.Refined(Unit), ContainerQueryLease.whileIdle(container) { Refinement.Refined(Unit) })
    }

    @Test
    fun `a closed reader cannot prepare or recreate workspace state`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val container =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val lease = (ContainerQueryLease.acquire(container) as Refinement.Refined).value
        lease.close()
        val allowance =
            (io.github.amichne.kast.kernel.ElapsedTimeAllowance.admitMillis(100) as Refinement.Refined).value
        val waiting =
            object : ContainerPreparationWait {
                override fun elapsed(): Duration = error("closed lease must reject before waiting")

                override fun pause(duration: Duration): Unit = error("closed lease must not wait")

                override fun admitActive(): Refinement<Unit, ContainerFailure> = Refinement.Refined(Unit)
            }
        assertEquals(
            Refinement.Rejected(ContainerFailure.LEASE_CLOSED),
            withContainerPreparationLock<Unit>(lease, allowance, waiting) { error("closed lease must not prepare") },
        )
        assertFalse(Files.exists(container.directory.resolve("preparation.lock")))
    }
}

internal fun retirementContainers(store: ContainerStore, temporary: Path): List<AdmittedContainer> {
    val runtime = containerRuntimeFixture(store)
    return listOf("first", "second").map { name ->
        (store.create(
                ContainerCommand.Create(name, null, null, false),
                Files.createDirectory(temporary.resolve(name)),
                runtime,
            ) as Refinement.Refined)
            .value
    }
}

private fun withCompetingPreparation(container: AdmittedContainer, action: (java.nio.channels.FileLock) -> Unit) {
    java.nio.channels.FileChannel.open(
            container.directory.resolve("preparation.lock"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        .use { channel ->
            val competing = channel.lock()
            try {
                action(competing)
            } finally {
                if (competing.isValid) competing.release()
            }
        }
}
