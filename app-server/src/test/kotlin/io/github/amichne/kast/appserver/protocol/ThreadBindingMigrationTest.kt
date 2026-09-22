package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.appserver.protocol.codex.codexThreadStoreFailurePresentation
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ThreadBindingMigrationTest {
    @Test
    fun `legacy bindings retain original bytes and require a new conversation even when the workspace is gone`(
        @TempDir temporary: Path
    ) = runTest {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val registration = WorkspaceRegistration(checkNotNull(CanonicalBrokerDirectory.admit(workspace)))
        val legacy =
            LegacyThreadBindingDocument(
                "old-thread",
                digest.value,
                workspace.toString(),
                workspace.toString(),
                registration.id.value,
                "protocolFixture",
            )
        val file = root.resolve("threads.json")
        privateWrite(file, Json { explicitNulls = false }.encodeToString(LegacyThreadStoreDocument(2, listOf(legacy))))
        val before = Files.readAllBytes(file)
        Files.delete(workspace)
        val observations = mutableListOf<ThreadMigrationObservation>()
        val store =
            assertInstanceOf(
                    FileThreadCatalogStoreOpen.Opened::class.java,
                    FileThreadCatalogStore.open(file, observations::add),
                )
                .store
        assertEquals(
            ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.NEW_CONVERSATION_REQUIRED),
            store.read("old-thread"),
        )
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
        assertEquals(
            listOf(ThreadMigrationOutcome.Started, ThreadMigrationOutcome.Committed),
            observations.map { it.outcome },
        )
        val fresh = binding("fresh-thread", root)
        assertEquals(ThreadStoreWrite.Written, store.write(fresh))
        assertInstanceOf(ThreadStoreRead.Found::class.java, store.read("fresh-thread"))
        assertEquals(
            ThreadStoreWrite.Rejected(ThreadCatalogStoreFailure.NEW_CONVERSATION_REQUIRED),
            store.write(binding("old-thread", root)),
        )
        observations.clear()
        assertInstanceOf(
            FileThreadCatalogStoreOpen.Opened::class.java,
            FileThreadCatalogStore.open(file, observations::add),
        )
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `a malformed unrelated shard is not enumerated on open or exact lookup`(@TempDir temporary: Path) = runTest {
        val root = temporary.toRealPath()
        val file = root.resolve("threads.json")
        val store = opened(file)
        store.write(binding("valid", root))
        store.write(binding("broken", root))
        privateWrite(recordPath(file, "broken"), "broken record")
        val reopened = opened(file)
        assertInstanceOf(ThreadStoreRead.Found::class.java, reopened.read("valid"))
        assertEquals(ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.DOCUMENT_MALFORMED), reopened.read("broken"))
        assertEquals(ThreadStoreRead.Missing, reopened.read("unknown"))
    }

    @Test
    fun `busy thread store rejects both reads and writes without replacing evidence`(@TempDir temporary: Path) =
        runTest {
            val root = temporary.toRealPath()
            val file = root.resolve("threads.json")
            val store = opened(file)
            val binding = binding("thread", root)
            FileChannel.open(file.resolveSibling("threads.json.d").resolve(".lock"), WRITE).use { channel ->
                channel.lock().use {
                    assertEquals(ThreadStoreRead.Rejected(ThreadCatalogStoreFailure.STORE_BUSY), store.read("thread"))
                    assertEquals(ThreadStoreWrite.Rejected(ThreadCatalogStoreFailure.STORE_BUSY), store.write(binding))
                    assertFalse(Files.exists(recordPath(file, "thread")))
                }
            }
            assertEquals(ThreadStoreWrite.Written, store.write(binding))
        }

    @Test
    fun `thread record preserves discriminators owner and exact encoded identity`(@TempDir temporary: Path) = runTest {
        val root = temporary.toRealPath()
        val file = root.resolve("threads.json")
        opened(file).write(binding("thread", root))
        val record = Json.parseToJsonElement(Files.readString(recordPath(file, "thread"))).jsonObject
        assertEquals(setOf("version", "key", "binding"), record.keys)
        assertEquals(JsonPrimitive(3), record["version"])
        assertEquals(JsonPrimitive("39200d1e8a8dbbb6d7bcea51e02b99f062d32a5f83151e8c5a9fab79576245dd"), record["key"])
        val stored = record.getValue("binding").jsonObject
        assertEquals(setOf("type", "document"), stored.keys)
        assertEquals(JsonPrimitive("current"), stored["type"])
        val document = stored.getValue("document").jsonObject
        assertEquals(setOf("threadId", "catalogDigest", "cwd", "workspaceRoot", "workspaceId", "owner"), document.keys)
        assertEquals(JsonPrimitive("thread"), document["threadId"])
        assertEquals(JsonPrimitive(root.toString()), document["workspaceRoot"])
        assertEquals(JsonPrimitive("protocolFixture"), document.getValue("owner").jsonObject["type"])
    }

    @Test
    fun `migration and tool failures retain every finite cause in bounded wire documents`() {
        val started =
            Json.parseToJsonElement(ThreadMigrationObservation(ThreadMigrationOutcome.Started).toJson()).jsonObject
        assertEquals(setOf("event", "outcome"), started.keys)
        assertEquals(JsonPrimitive("kast_thread_migration"), started["event"])
        assertEquals(JsonPrimitive("started"), started.getValue("outcome").jsonObject["type"])
        val committed =
            Json.parseToJsonElement(ThreadMigrationObservation(ThreadMigrationOutcome.Committed).toJson()).jsonObject
        assertEquals(JsonPrimitive("committed"), committed.getValue("outcome").jsonObject["type"])
        for (failure in ThreadCatalogStoreFailure.entries) {
            val rejected =
                Json.parseToJsonElement(ThreadMigrationObservation(ThreadMigrationOutcome.Rejected(failure)).toJson())
                    .jsonObject
                    .getValue("outcome")
                    .jsonObject
            assertEquals(setOf("type", "failure"), rejected.keys)
            assertEquals(JsonPrimitive("rejected"), rejected["type"])
            assertEquals(JsonPrimitive(failure.name), rejected["failure"])
            val presentation = codexThreadStoreFailurePresentation(failure)
            assertFalse(presentation.success)
            val payload = Json.parseToJsonElement(presentation.content.single().text).jsonObject
            assertEquals(setOf("status", "failure"), payload.keys)
            assertEquals(JsonPrimitive("rejected"), payload["status"])
            assertEquals(JsonPrimitive(failure.name), payload["failure"])
        }
    }

    private fun opened(file: Path) =
        assertInstanceOf(FileThreadCatalogStoreOpen.Opened::class.java, FileThreadCatalogStore.open(file)).store

    private fun binding(thread: String, root: Path) =
        (ThreadCatalogBinding.admit(thread, digest, root) as Refinement.Refined).value

    private fun recordPath(file: Path, thread: String): Path {
        val key = threadRecordDigest(thread)
        return file.resolveSibling("threads.json.d").resolve("records-v3").resolve(key.take(2)).resolve("$key.json")
    }

    private fun privateWrite(file: Path, text: String) {
        Files.writeString(file, text)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    companion object {
        private val digest = checkNotNull(CatalogDigest.admit("sha256:${"a".repeat(64)}"))
    }
}
