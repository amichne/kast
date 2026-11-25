package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.managed.DependencyCacheRejection
import io.github.amichne.kast.distribution.managed.DependencyCacheSource
import io.github.amichne.kast.distribution.managed.RejectedDependencyCache
import io.github.amichne.kast.distribution.managed.SharedDependencyCacheOutcome
import io.github.amichne.kast.kernel.Refinement
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceSeedingTest {
    @Test
    fun `first workspace copies a locked user cache and reports a skipped one`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("shared"))
        val donor = Files.createDirectory(root.resolve("donor"))
        val lock = Files.write(donor.resolve("modules-2.lock"), gradleWriteState(0))
        Files.writeString(Files.createDirectory(donor.resolve("files-2.1")).resolve("sample.jar"), "sample")
        val staging = Files.createDirectory(root.resolve("staging"))
        val snapshot = home.resolve("kast-read-only")
        val progress = mutableListOf<SharedDependencyCacheProgress>()
        val prepare = { prepareSharedDependencyCache(home, staging, donor, progress::add) }
        prepare()
        assertFalse(Files.exists(snapshot))
        Files.write(lock, gradleWriteState(1))
        FileChannel.open(lock, WRITE).use { channel -> channel.lock(0, 17, false).use { prepare() } }
        assertEquals("sample", Files.readString(snapshot.resolve("modules-2/files-2.1/sample.jar")))
        prepare()
        assertEquals(
            listOf(
                SharedDependencyCacheOutcome.Skipped(
                    listOf(
                        RejectedDependencyCache(
                            DependencyCacheSource.SHARED_GRADLE,
                            DependencyCacheRejection.SOURCE_UNAVAILABLE,
                        ),
                        RejectedDependencyCache(
                            DependencyCacheSource.USER_GRADLE,
                            DependencyCacheRejection.WRITE_IN_PROGRESS,
                        ),
                    )
                ),
                SharedDependencyCacheOutcome.Published(DependencyCacheSource.USER_GRADLE),
            ),
            progress.map { it.result },
        )
        val encoded = containerJson.parseToJsonElement(containerJson.encodeToString(progress.last())).jsonObject
        assertEquals(setOf("result", "event"), encoded.keys)
        assertEquals("kast_dependency_cache", encoded.getValue("event").jsonPrimitive.content)
        assertEquals("PUBLISHED", encoded.getValue("result").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a populated shared cache produces one separate read only copy`(@TempDir temporary: Path) {
        val store = ContainerStore(temporary.toRealPath().resolve("store"), platform())
        val runtime = containerRuntimeFixture(store)
        val first = creation(store, temporary, "first", null, WorkspaceDependencySharing.SHARED)
        (store.create(first, runtime) as Refinement.Refined)
        val modules = Files.createDirectories(first.gradleHome.resolve("caches/modules-2"))
        val artifact = Files.createDirectories(modules.resolve("files-2.1/sample")).resolve("library.jar")
        Files.writeString(artifact, "published")
        Files.write(modules.resolve("modules-2.lock"), gradleWriteState(1))
        Files.writeString(modules.resolve("gc.properties"), "")
        val second = creation(store, temporary, "second", null, WorkspaceDependencySharing.SHARED)
        (store.create(second, runtime) as Refinement.Refined)
        val snapshot = first.gradleHome.resolve("kast-read-only/modules-2")
        assertEquals("published", Files.readString(snapshot.resolve("files-2.1/sample/library.jar")))
        assertFalse(Files.exists(snapshot.resolve("modules-2.lock")))
        assertFalse(Files.exists(snapshot.resolve("gc.properties")))
        Files.writeString(artifact, "changed")
        (store.create(creation(store, temporary, "third", null, WorkspaceDependencySharing.SHARED), runtime)
            as Refinement.Refined)
        assertEquals("published", Files.readString(snapshot.resolve("files-2.1/sample/library.jar")))
    }

    @Test
    fun `shared creation selects the owned Gradle home but leaves private creation unchanged`(
        @TempDir temporary: Path
    ) {
        val store = ContainerStore(temporary.toRealPath().resolve("store"), platform())
        val runtime = containerRuntimeFixture(store)
        val shared = creation(store, temporary, "shared", null, WorkspaceDependencySharing.SHARED)
        assertEquals(store.root.resolve("shared/gradle"), shared.gradleHome)
        val container = (store.create(shared, runtime) as Refinement.Refined).value
        assertTrue(Files.isDirectory(container.gradleHome))
        assertTrue(store.select(container.id.text) is Refinement.Refined)
        val private = creation(store, temporary, "private", null, WorkspaceDependencySharing.PRIVATE)
        assertEquals(private.directory.resolve("state/home/.gradle"), private.gradleHome)
        val privateContainer = (store.create(private, runtime) as Refinement.Refined).value
        val legacyJson = kotlinx.serialization.json.Json(containerJson) { encodeDefaults = false }
        Files.writeString(
            privateContainer.directory.resolve("container.json"),
            legacyJson.encodeToString(privateContainer.owned.record()),
        )
        assertEquals(
            WorkspaceDependencySharing.PRIVATE,
            (store.select(privateContainer.id.text) as Refinement.Refined).value.owned.dependencySharing,
        )
    }

    @Test
    fun `a stopped smart donor is cloned under its exclusive lease without sharing writes`(@TempDir temporary: Path) {
        var probes = 0
        lateinit var donor: AdmittedContainer
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, state, home ->
                    probes++
                    assertEquals(donor.state, state)
                    assertEquals(donor.home, home)
                    assertEquals(Refinement.Rejected(ContainerFailure.BUSY), ContainerQueryLease.acquire(donor))
                    WorkspaceSeedEndpoint.HOST_UNAVAILABLE
                },
            )
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        donor =
            (store.create(creation(store, temporary, "donor", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        replaceExit(donor, exitRecord())
        Files.writeString(donor.state.resolve("system/index/library-index"), "original")
        Files.writeString(donor.state.resolve("system/donor-model"), "private")
        val recipient =
            (store.create(creation(store, temporary, "recipient", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(1, probes)
        if (store.platform.publicName.startsWith("macos")) {
            val copied = recipient.state.resolve("system/index/library-index")
            assertEquals("original", Files.readString(copied))
            Files.writeString(copied, "recipient")
            assertEquals("original", Files.readString(donor.state.resolve("system/index/library-index")))
            assertFalse(Files.exists(recipient.state.resolve("system/donor-model")))
        } else {
            Files.list(recipient.state.resolve("system")).use { assertEquals(0, it.count()) }
        }
        assertFalse(Files.exists(HostExitRecord.path(recipient.state)))
        assertTrue(Files.isRegularFile(recipient.state.resolve("workspace-seed.json")))
        (ContainerQueryLease.acquire(donor) as Refinement.Refined).value.close()
    }

    @Test
    fun `unproven donors never reach the endpoint or exclude an eligible neighbor`(@TempDir temporary: Path) {
        var probes = 0
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, _, _ ->
                    probes++
                    WorkspaceSeedEndpoint.HOST_UNAVAILABLE
                },
            )
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        for ((name, mutate) in excludedDonors(store)) {
            val excluded =
                (store.create(creation(store, temporary, name, gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                        as Refinement.Refined)
                    .value
            replaceExit(excluded, exitRecord())
            mutate(excluded)
        }
        val otherGradle = Files.createDirectory(temporary.resolve("other-gradle")).toRealPath()
        val other =
            (store.create(creation(store, temporary, "other", otherGradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        replaceExit(other, exitRecord())
        val eligible =
            (store.create(creation(store, temporary, "eligible", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        replaceExit(eligible, exitRecord())
        val recipient =
            (store.create(creation(store, temporary, "recipient", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(1, probes)
        val result = seedResult(recipient)
        assertEquals(
            if (platform().publicName.startsWith("macos")) WorkspaceSeedResult.Seeded(eligible.id.text)
            else WorkspaceSeedResult.UnsupportedFilesystem,
            result,
        )
    }

    @Test
    fun `a donor of another Kast build must run the same IDE distribution`(@TempDir temporary: Path) {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, _, _ -> WorkspaceSeedEndpoint.HOST_UNAVAILABLE },
                { _, _ -> WorkspaceSeedClone.Completed },
            )
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val current = containerRuntimeFixture(store, "current Host")
        fun donor(name: String, runtime: AdmittedContainerRuntime): AdmittedContainer =
            (store.create(creation(store, temporary, name, gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
                .also { replaceExit(it, exitRecord()) }
        fun seeded(name: String): WorkspaceSeedResult =
            seedResult(
                (store.create(creation(store, temporary, name, gradle, WorkspaceDependencySharing.SHARED), current)
                        as Refinement.Refined)
                    .value
            )
        donor("other-ide", containerRuntimeFixture(store, "other Host", distribution = "b".repeat(64)))
        assertEquals(WorkspaceSeedResult.NoEligibleDonor, seeded("first"))
        val same = donor("same-ide", containerRuntimeFixture(store, "earlier Host"))
        assertEquals(WorkspaceSeedResult.Seeded(same.id.text), seeded("second"))
    }

    @Test
    fun `lease contention differs from an answering endpoint or leftover IDE lock`(@TempDir temporary: Path) {
        var probes = 0
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, _, _ ->
                    probes++
                    WorkspaceSeedEndpoint.UNPROVEN
                },
            )
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val donor =
            (store.create(creation(store, temporary, "donor", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        replaceExit(donor, exitRecord())
        (ContainerQueryLease.acquire(donor) as Refinement.Refined).value.use {
            val busy =
                (store.create(creation(store, temporary, "busy", gradle, WorkspaceDependencySharing.SHARED), runtime)
                        as Refinement.Refined)
                    .value
            assertEquals(WorkspaceSeedResult.DonorsBusy, seedResult(busy))
            assertEquals(0, probes)
        }
        val active =
            (store.create(creation(store, temporary, "active", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(WorkspaceSeedResult.NoEligibleDonor, seedResult(active))
        assertEquals(1, probes)
        val launch =
            io.github.amichne.kast.distribution.managed.SelectedIdeInstallation.resolve(donor.runtime.home)
                as io.github.amichne.kast.distribution.managed.SelectedIdeLaunch.Resolved
        val plugins =
            io.github.amichne.kast.distribution.managed.SelectedIdeInstallation.pluginRoot(launch, donor.home)
                as Refinement.Refined
        Files.writeString(plugins.value.value.parent.resolve(".lock"), "stale PID")
        val locked =
            (store.create(creation(store, temporary, "locked", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(WorkspaceSeedResult.NoEligibleDonor, seedResult(locked))
        assertEquals(1, probes)
    }

    @Test
    fun `newest exit record wins with container ID as deterministic tie break`(@TempDir temporary: Path) {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, _, _ -> WorkspaceSeedEndpoint.HOST_UNAVAILABLE },
                { _, _ -> WorkspaceSeedClone.Completed },
            )
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val donors =
            listOf("one", "two")
                .map { name ->
                    (store.create(creation(store, temporary, name, gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                            as Refinement.Refined)
                        .value
                        .also {
                            replaceExit(it, exitRecord())
                            Files.setLastModifiedTime(HostExitRecord.path(it.state), FileTime.fromMillis(1000))
                        }
                }
                .sortedBy { it.id.text }
        val tied =
            (store.create(creation(store, temporary, "tied", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(WorkspaceSeedResult.Seeded(donors.first().id.text), seedResult(tied))
        Files.setLastModifiedTime(HostExitRecord.path(donors.last().state), FileTime.fromMillis(2000))
        val newest =
            (store.create(creation(store, temporary, "newest", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(WorkspaceSeedResult.Seeded(donors.last().id.text), seedResult(newest))
    }
}

class WorkspaceSeedFallbackTest {
    @Test
    fun `nonprivate source roots and linked contents reject before native cloning`(@TempDir temporary: Path) {
        val source = (privateContainerDirectory(temporary.toRealPath().resolve("source")) as Refinement.Refined).value
        val destination =
            (privateContainerDirectory(temporary.toRealPath().resolve("destination")) as Refinement.Refined).value
        val rejected =
            if (platform().publicName.startsWith("macos"))
                WorkspaceSeedClone.Failed(WorkspaceSeedCloneStage.SOURCE_ADMISSION)
            else WorkspaceSeedClone.Unsupported
        Files.setPosixFilePermissions(source, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals(rejected, cloneWorkspaceSystem(source, destination))
        Files.setPosixFilePermissions(source, containerDirectoryMode)
        Files.createDirectory(source.resolve("caches"))
        val index = Files.writeString(Files.createDirectory(source.resolve("index")).resolve("data"), "original")
        Files.createSymbolicLink(source.resolve("index/link"), index)
        assertEquals(rejected, cloneWorkspaceSystem(source, destination))
        Files.list(destination).use { assertEquals(0, it.count()) }
        assertEquals("original", Files.readString(index))
    }

    @Test
    fun `partial clones and unsupported filesystems publish only empty system state`(@TempDir temporary: Path) {
        for ((name, cloneResult) in
            listOf(
                "failed" to WorkspaceSeedClone.Failed(WorkspaceSeedCloneStage.CLONE),
                "unsupported" to WorkspaceSeedClone.Unsupported,
            )) {
            var clones = 0
            val root = Files.createDirectory(temporary.resolve(name)).toRealPath()
            val store =
                ContainerStore(
                    root.resolve("store"),
                    platform(),
                    WorkspaceSeedHostProbe { _, _, _ -> WorkspaceSeedEndpoint.HOST_UNAVAILABLE },
                    { _, destination ->
                        clones++
                        Files.writeString(
                            Files.createDirectory(destination.resolve("partial")).resolve("index"),
                            "partial",
                        )
                        cloneResult
                    },
                )
            val runtime = containerRuntimeFixture(store)
            val gradle = Files.createDirectory(root.resolve("gradle")).toRealPath()
            val donor =
                (store.create(creation(store, root, "donor", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                        as Refinement.Refined)
                    .value
            replaceExit(donor, exitRecord())
            val recipient =
                (store.create(creation(store, root, "recipient", gradle, WorkspaceDependencySharing.SHARED), runtime)
                        as Refinement.Refined)
                    .value
            assertEquals(1, clones)
            Files.list(recipient.state.resolve("system")).use { assertEquals(0, it.count()) }
            assertEquals(
                if (name == "failed") WorkspaceSeedResult.CloneFailed(WorkspaceSeedCloneStage.CLONE)
                else WorkspaceSeedResult.UnsupportedFilesystem,
                seedResult(recipient),
            )
        }
    }

    @Test
    fun `cleanup cannot publish an aliased system root or touch its external target`(@TempDir temporary: Path) {
        val external = Files.createDirectory(temporary.resolve("external")).toRealPath()
        val sentinel = Files.writeString(external.resolve("sentinel"), "protected")
        var clones = 0
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("store"),
                platform(),
                WorkspaceSeedHostProbe { _, _, _ -> WorkspaceSeedEndpoint.HOST_UNAVAILABLE },
                { _, destination ->
                    clones++
                    Files.delete(destination)
                    Files.createSymbolicLink(destination, external)
                    WorkspaceSeedClone.Failed(WorkspaceSeedCloneStage.CLONE)
                },
            )
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val donor =
            (store.create(creation(store, temporary, "donor", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        replaceExit(donor, exitRecord())
        val recipient = creation(store, temporary, "recipient", gradle, WorkspaceDependencySharing.SHARED)
        assertEquals(Refinement.Rejected(ContainerFailure.FOREIGN_STATE), store.create(recipient, runtime))
        assertEquals(1, clones)
        assertFalse(Files.exists(recipient.directory))
        assertEquals("protected", Files.readString(sentinel))
    }

    @Test
    fun `seed receipts encode every closed result and required schema independently`() {
        val results =
            listOf(
                WorkspaceSeedResult.NotRequested to "NOT_REQUESTED",
                WorkspaceSeedResult.Seeded("a".repeat(64)) to "SEEDED",
                WorkspaceSeedResult.NoEligibleDonor to "NO_ELIGIBLE_DONOR",
                WorkspaceSeedResult.DonorsBusy to "DONORS_BUSY",
                WorkspaceSeedResult.UnsupportedFilesystem to "UNSUPPORTED_FILESYSTEM",
                WorkspaceSeedResult.CloneFailed(WorkspaceSeedCloneStage.CLONE) to "CLONE_FAILED",
                WorkspaceSeedResult.SelectionRejected(ContainerFailure.FOREIGN_STATE) to "SELECTION_REJECTED",
                WorkspaceSeedResult.CleanupRejected(ContainerFailure.FILESYSTEM_UNAVAILABLE) to "CLEANUP_REJECTED",
            )
        for ((result, name) in results) {
            val encoded =
                containerJson.parseToJsonElement(containerJson.encodeToString(WorkspaceSeedRecord(result))).jsonObject
            assertEquals(setOf("result", "schemaVersion"), encoded.keys)
            assertEquals("1", encoded.getValue("schemaVersion").jsonPrimitive.content)
            val payload = encoded.getValue("result").jsonObject
            assertEquals(name, payload.getValue("type").jsonPrimitive.content)
            when (name) {
                "SEEDED" -> assertEquals("a".repeat(64), payload.getValue("donor").jsonPrimitive.content)
                "CLONE_FAILED" -> assertEquals("CLONE", payload.getValue("stage").jsonPrimitive.content)
                "SELECTION_REJECTED" -> assertEquals("FOREIGN_STATE", payload.getValue("failure").jsonPrimitive.content)
                "CLEANUP_REJECTED" ->
                    assertEquals("FILESYSTEM_UNAVAILABLE", payload.getValue("failure").jsonPrimitive.content)
                else -> assertEquals(setOf("type"), payload.keys)
            }
        }
    }

    @Test
    fun `passive status compares recorded sharing policy even when the Gradle path is unchanged`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath().resolve("kast")
        val installation = Files.createDirectories(root.resolve("installation/config")).parent
        val store = ContainerStore(root.resolveSibling("kast-workspaces"), platform())
        val runtime = containerRuntimeFixture(store)
        val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
        val private =
            (store.create(creation(store, temporary, "private", gradle, WorkspaceDependencySharing.PRIVATE), runtime)
                    as Refinement.Refined)
                .value
        val shared =
            (store.create(creation(store, temporary, "shared", gradle, WorkspaceDependencySharing.SHARED), runtime)
                    as Refinement.Refined)
                .value
        assertEquals(
            WorkspaceDependencySharing.SHARED,
            (store.select(shared.id.text) as Refinement.Refined).value.owned.dependencySharing,
        )
        Files.writeString(
            installation.resolve("config/environment"),
            "KAST_WORKSPACE_DEPENDENCY_SHARING=SHARED\nGRADLE_USER_HOME=$gradle\n",
        )
        val before =
            Files.walk(root.parent).use { paths -> paths.filter(Files::isRegularFile).toList().associateWith(::sha256) }
        val statuses =
            requireNotNull(readWorkspaceStatuses(root, WorkspaceScope.All, temporary).value)
                .filterIsInstance<ManagedWorkspaceStatus.Recorded>()
                .associateBy { it.id }
        assertEquals(
            Observation.verified(WorkspaceDependencyMatch.DIFFERS_FROM_CREATION),
            statuses.getValue(private.id.text).savedDependencyConfiguration,
        )
        assertEquals(
            Observation.verified(WorkspaceDependencyMatch.MATCHES_CREATION),
            statuses.getValue(shared.id.text).savedDependencyConfiguration,
        )
        assertEquals(
            before,
            Files.walk(root.parent).use { paths ->
                paths.filter(Files::isRegularFile).toList().associateWith(::sha256)
            },
        )
    }
}

/** The state region that Gradle keeps at the start of modules-2.lock; sequence zero marks a write in progress. */
private fun gradleWriteState(sequence: Long): ByteArray =
    ByteBuffer.allocate(17).put(3).putLong(1).putLong(sequence).array()

private fun platform(): ContainerPlatform = (ContainerPlatform.current() as Refinement.Refined).value

private fun exitRecord(): HostExitRecord =
    HostExitRecord(HostExitReason.IDLE, HostExitIndexState.SMART, "123e4567-e89b-12d3-a456-426614174000")

private fun replaceExit(container: AdmittedContainer, record: HostExitRecord) {
    for (storage in listOf("caches", "index")) Files.createDirectories(container.state.resolve("system/$storage"))
    Files.deleteIfExists(HostExitRecord.path(container.state))
    writeContainerRecord(HostExitRecord.path(container.state), record)
}

private fun seedResult(container: AdmittedContainer): WorkspaceSeedResult =
    (readContainerRecord<WorkspaceSeedRecord>(container.state.resolve("workspace-seed.json")) as Refinement.Refined)
        .value
        .result

private fun excludedDonors(store: ContainerStore): List<Pair<String, (AdmittedContainer) -> Unit>> =
    listOf(
        "missing" to { Files.delete(HostExitRecord.path(it.state)) },
        "malformed" to { Files.writeString(HostExitRecord.path(it.state), "invalid") },
        "schema" to { replaceExit(it, exitRecord().copy(schemaVersion = 99)) },
        "dumb" to { replaceExit(it, exitRecord().copy(index = HostExitIndexState.DUMB)) },
        "lifetime" to { replaceExit(it, exitRecord().copy(hostLifetime = "invalid")) },
        "runtime" to
            {
                val path = it.directory.resolve("generation.json")
                val record = (readContainerRecord<ContainerGenerationRecord>(path) as Refinement.Refined).value
                Files.writeString(path, containerJson.encodeToString(record.copy(archiveSha256 = "f".repeat(64))))
            },
        "no-index" to { Files.delete(it.state.resolve("system/index")) },
        "removing" to
            {
                Files.writeString(store.containers.resolve(".removing-${it.id.text}.json"), "removal pending")
            },
    )

private fun creation(
    store: ContainerStore,
    temporary: Path,
    name: String,
    gradle: Path?,
    sharing: WorkspaceDependencySharing,
): AdmittedContainerCreation {
    val workspace = Files.createDirectory(temporary.resolve(name)).toRealPath()
    return (store.admitCreation(ContainerCommand.Create(name, null, gradle?.toString(), false), workspace, sharing)
            as Refinement.Refined)
        .value
}
