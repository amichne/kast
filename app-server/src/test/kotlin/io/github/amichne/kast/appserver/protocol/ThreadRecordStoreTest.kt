package io.github.amichne.kast.appserver.protocol

import io.github.amichne.kast.appserver.core.CatalogDigest
import io.github.amichne.kast.appserver.protocol.codex.codexThreadStoreFailurePresentation
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ThreadRecordStoreTest {
    @Test
    fun `a prior single-file thread store is never read`(@TempDir temporary: Path) = runTest {
        val root = temporary.toRealPath()
        val file = root.resolve("threads.json")
        privateWrite(file, "prior single-file thread store")
        val store = opened(file)
        assertEquals(ThreadStoreRead.Missing, store.read("old-thread"))
        assertEquals("prior single-file thread store", Files.readString(file))
        assertTrue(Files.exists(file.resolveSibling("threads.json.d").resolve("layout.json")))
    }

    @Test
    fun `unexpected store contents without a layout reject without effects`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val file = root.resolve("threads.json")
        val directory = Files.createDirectory(root.resolve("threads.json.d"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val stray = Files.createDirectory(directory.resolve(".migration-interrupted"))
        assertEquals(
            FileThreadCatalogStoreOpen.Rejected(ThreadCatalogStoreFailure.STORE_REJECTED),
            FileThreadCatalogStore.open(file),
        )
        assertTrue(Files.exists(stray))
        assertFalse(Files.exists(directory.resolve("layout.json")))
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
    fun `store failures retain every finite cause in tool presentations`() {
        for (failure in ThreadCatalogStoreFailure.entries) {
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
