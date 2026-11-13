package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerRecordAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `workspace rejection diagnostics preserve the exact entry and finite cause`() {
        val encoded =
            containerJson.encodeToString(WorkspaceEntryRejection("bad-entry", ContainerFailure.INVALID_RECORD))
        val document = containerJson.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("entry", "failure", "stage", "outcome", "recovery"),
            document.keys,
        )
        assertEquals("bad-entry", document.getValue("entry").jsonPrimitive.content)
        assertEquals("INVALID_RECORD", document.getValue("failure").jsonPrimitive.content)
        assertEquals("SHUTDOWN", document.getValue("stage").jsonPrimitive.content)
        assertEquals("REJECTED", document.getValue("outcome").jsonPrimitive.content)
        assertEquals("kast uninstall", document.getValue("recovery").jsonPrimitive.content)
    }

    @Test
    fun `a leased workspace prevents every upgrade fence and Host retirement`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val containers = retirementContainers(store, temporary)
        (ContainerQueryLease.acquire(containers.last()) as Refinement.Refined).value.use {
            assertEquals(
                Refinement.Rejected(ContainerFailure.BUSY),
                retireManagedWorkspaces(store, { error("fenced before proving every workspace idle") }) {
                    error("retired a Host before proving every workspace idle")
                },
            )
        }
        val retired = mutableListOf<String>()
        var fenced = false
        assertEquals(
            Refinement.Refined(Unit),
            retireManagedWorkspaces(
                store,
                {
                    fenced = true
                    Refinement.Refined(Unit)
                },
            ) { container ->
                assertTrue(fenced)
                for (other in containers) assertEquals(
                    Refinement.Rejected(ContainerFailure.BUSY),
                    ContainerQueryLease.acquire(other),
                )
                retired += container.id.text
                Refinement.Refined(Unit)
            },
        )
        assertEquals(containers.map { it.id.text }.sorted(), retired)
        for (container in containers) {
            assertEquals(
                Refinement.Refined(Unit),
                ContainerQueryLease.whileIdle(container) { Refinement.Refined(Unit) },
            )
            assertFalse(Files.exists(container.stopped))
            assertEquals(
                Refinement.Refined(Unit),
                admitContainerTrust(container.directory.resolve("trust.xml"), container.workspace),
            )
        }
    }

    @Test
    fun `unreadable workspace and rejected fence cannot retire any Host`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val container =
            (store.create(
                    ContainerCommand.Create("owned", null, null, false),
                    Files.createDirectory(temporary.resolve("workspace")),
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        assertEquals(
            Refinement.Rejected(ContainerFailure.FOREIGN_STATE),
            retireManagedWorkspaces(
                store,
                {
                    Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
                },
            ) {
                error("retired behind a rejected fence")
            },
        )
        Files.writeString(container.directory.resolve("container.json"), "invalid")
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_RECORD),
            retireManagedWorkspaces(
                store,
                {
                    error("fenced despite unreadable workspace")
                },
            ) {
                error("retired despite unreadable workspace")
            },
        )
    }

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
    fun `runtime publication keeps a renameable private root over sealed payload bytes`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val runtime = containerRuntimeFixture(store)
        assertEquals(runtime.archiveSha256, runtime.key)
        val replacement = containerRuntimeFixture(store, "a different Host with the same embedded version")
        assertEquals(runtime.manifest.version, replacement.manifest.version)
        assertNotEquals(runtime.root, replacement.root)
        assertEquals(
            "fixture-not-a-plugin",
            Files.readString(runtime.plugins.resolve("kast-ide-hosted/lib/kast-ide-hosted.jar")),
        )
        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(runtime.root),
        )
        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"),
            Files.getPosixFilePermissions(runtime.home),
        )
        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("r--------"),
            Files.getPosixFilePermissions(runtime.root.resolve("manifest.json")),
        )
    }

    @Test
    fun `durable workspace records do not pin runtime generations`() {
        val platform = (ContainerPlatform.current() as Refinement.Refined).value
        val store = ContainerStore(temporary.toRealPath().resolve("manager"), platform)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val container =
            (store.create(
                    ContainerCommand.Create("backend", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val record =
            containerJson.parseToJsonElement(Files.readString(container.directory.resolve("container.json"))).jsonObject
        assertEquals(setOf("id", "name", "workspace", "gradleHome", "schemaVersion"), record.keys)
        assertEquals("2", record.getValue("schemaVersion").jsonPrimitive.content)
        val generation = container.directory.resolve("generation.json")
        assertTrue(Files.isRegularFile(generation))
        val trust = container.directory.resolve("trust.xml")
        val trustedBytes = Files.readAllBytes(trust)
        val recordBytes = Files.readAllBytes(container.directory.resolve("container.json"))
        assertEquals(container.id, (store.select(container.id.text) as Refinement.Refined).value.id)
        for ((key, digest, failure) in
            listOf(
                Triple(
                    "9.9.9-${platform.publicName}",
                    container.runtime.archiveSha256,
                    ContainerFailure.RUNTIME_UNAVAILABLE,
                ),
                Triple(container.runtime.key, "0".repeat(64), ContainerFailure.RUNTIME_MISMATCH),
            )) {
            Files.writeString(generation, containerJson.encodeToString(ContainerGenerationRecord(key, digest)))
            assertEquals(Refinement.Rejected(failure), store.select(container.id.text))
            assertEquals(container.id, (store.workspace(workspace) as Refinement.Refined).value.id)
            val entry = (store.enumerate() as Refinement.Refined).value.single() as ContainerStoreEntry.Admitted
            assertEquals(container.id, entry.workspace.id)
            val lease = (ContainerQueryLease.acquire(entry.workspace, platform) as Refinement.Refined).value
            lease.use {
                assertEquals(Refinement.Rejected(failure), it.admitGeneration())
                assertEquals(
                    Refinement.Rejected(ContainerFailure.BUSY),
                    ContainerQueryLease.whileIdle<Unit>(container) { error("retired a leased workspace") },
                )
            }
            assertEquals(Refinement.Rejected(ContainerFailure.LEASE_CLOSED), lease.admitGeneration())
            assertTrue(trustedBytes.contentEquals(Files.readAllBytes(trust)))
            assertTrue(recordBytes.contentEquals(Files.readAllBytes(container.directory.resolve("container.json"))))
        }
    }

    @Test
    fun `legacy records retain identity trust and stopped state without passive migration`() {
        val store =
            ContainerStore(
                temporary.toRealPath().resolve("manager"),
                (ContainerPlatform.current() as Refinement.Refined).value,
            )
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val container =
            (store.create(
                    ContainerCommand.Create("legacy", null, null, false),
                    workspace,
                    containerRuntimeFixture(store),
                ) as Refinement.Refined)
                .value
        val record = container.directory.resolve("container.json")
        val legacy =
            LegacyContainerRecord(
                container.id.text,
                "legacy",
                workspace.toString(),
                container.runtime.key,
                container.gradleHome.toString(),
            )
        val encoded = containerJson.encodeToString(legacy)
        Files.writeString(record, encoded)
        Files.delete(container.directory.resolve("generation.json"))
        Files.delete(container.directory.resolve("trust.xml"))
        writeContainerRecord(container.stopped, ContainerStopRecord(container.id.text))

        val admitted = (store.select(container.id.text) as Refinement.Refined).value
        assertEquals(container.id, admitted.id)
        assertEquals(container.workspace, admitted.workspace)
        assertEquals(container.runtime.archiveSha256, admitted.runtime.archiveSha256)
        assertEquals(Refinement.Refined(ContainerState.STOPPED), containerDisposition(admitted))
        assertEquals(encoded, Files.readString(record))
        assertFalse(Files.exists(container.directory.resolve("generation.json")))
        assertFalse(Files.exists(container.directory.resolve("trust.xml")))
        Files.writeString(record, containerJson.encodeToString(legacy.copy(schemaVersion = 2)))
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_RECORD), store.select(container.id.text))
    }
}
