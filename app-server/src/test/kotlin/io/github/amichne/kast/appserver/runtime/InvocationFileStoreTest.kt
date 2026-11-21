package io.github.amichne.kast.appserver.runtime

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InvocationFileStoreTest {
    @Test
    fun `a prior single-file journal is never read`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        privateWrite(file, "prior single-file journal")
        assertEquals(InvocationAdmission.Admitted, InvocationFence(file).initialization())
        assertEquals("prior single-file journal", Files.readString(file))
        assertTrue(Files.exists(store(file).resolve("layout.json")))
    }

    @Test
    fun `unexpected store contents without a layout reject without effects`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val directory = store(file)
        Files.createDirectory(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val stray = Files.createDirectory(directory.resolve(".migration-interrupted"))
        assertEquals(rejected(InvocationFenceFailure.STORE_REJECTED), InvocationFence(file).initialization())
        assertTrue(Files.exists(stray))
        assertFalse(Files.exists(directory.resolve("layout.json")))
    }

    @Test
    fun `completed history does not consume active capacity`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fence = InvocationFence(file, maximumActive = limit(1))
        repeat(3) { ordinal ->
            assertEquals(InvocationAdmission.Admitted, fence.admit("call-$ordinal", fingerprint))
            assertEquals(rejected(InvocationFenceFailure.CAPACITY_EXCEEDED), fence.admit("other", fingerprint))
            assertEquals(InvocationAdmission.Admitted, fence.finish("call-$ordinal", InvocationSettlement.COMPLETED))
        }
        val reopened = InvocationFence(file, maximumActive = limit(1))
        repeat(3) { ordinal ->
            assertEquals(
                rejected(InvocationFenceFailure.ALREADY_COMPLETED),
                reopened.admit("call-$ordinal", fingerprint),
            )
        }
        assertEquals(InvocationAdmission.Admitted, reopened.admit("new", fingerprint))
    }

    @Test
    fun `record shape and shard use the full identity digest`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        assertEquals(InvocationAdmission.Admitted, InvocationFence(file).admit("call", fingerprint))
        val record = Json.parseToJsonElement(Files.readString(recordPath(file, callKey))).jsonObject
        assertEquals(setOf("schemaVersion", "key", "fingerprint", "phase"), record.keys)
        assertEquals(JsonPrimitive(2), record["schemaVersion"])
        assertEquals(JsonPrimitive(callKey), record["key"])
        assertEquals(JsonPrimitive(fingerprint), record["fingerprint"])
        assertEquals(JsonPrimitive("STARTED"), record["phase"])
        val layout = Json.parseToJsonElement(Files.readString(store(file).resolve("layout.json"))).jsonObject
        assertEquals(setOf("schemaVersion"), layout.keys)
        assertEquals(JsonPrimitive(2), layout["schemaVersion"])
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a held store lock rejects without beginning an invocation`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fence = InvocationFence(file)
        FileChannel.open(store(file).resolve(".lock"), WRITE).use { channel ->
            channel.lock().use {
                assertEquals(rejected(InvocationFenceFailure.STORE_BUSY), fence.admit("call", fingerprint))
                assertFalse(Files.exists(recordPath(file, callKey)))
            }
        }
        assertEquals(InvocationAdmission.Admitted, fence.admit("call", fingerprint))
    }

    @Test
    fun `symlinked record cannot redirect settlement`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fence = InvocationFence(file)
        assertEquals(InvocationAdmission.Admitted, fence.admit("call", fingerprint))
        val path = recordPath(file, callKey)
        val outside = root.resolve("protected")
        Files.move(path, outside)
        val before = Files.readAllBytes(outside)
        Files.createSymbolicLink(path, outside)
        assertEquals(
            rejected(InvocationFenceFailure.STORE_REJECTED),
            fence.finish("call", InvocationSettlement.COMPLETED),
        )
        assertTrue(before.contentEquals(Files.readAllBytes(outside)))
    }

    @Test
    fun `record identity mismatch fails closed without replacement`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fence = InvocationFence(file)
        fence.admit("call", fingerprint)
        val path = recordPath(file, callKey)
        val replaced =
            InvocationRecordDocument(
                schemaVersion = 2,
                key = "b".repeat(64),
                fingerprint = fingerprint,
                phase = InvocationPhase.COMPLETED,
            )
        privateWrite(path, Json.encodeToString(replaced))
        assertEquals(rejected(InvocationFenceFailure.STORE_REJECTED), fence.admit("call", fingerprint))
        assertEquals(Json.encodeToString(replaced), Files.readString(path))
    }

    private fun store(file: Path) = file.resolveSibling("${file.fileName}.d")

    private fun recordPath(file: Path, key: String) =
        store(file).resolve("records-v2").resolve(key.take(2)).resolve("$key.json")

    private fun rejected(failure: InvocationFenceFailure) = InvocationAdmission.Rejected(failure)

    private fun privateWrite(file: Path, content: String) {
        Files.writeString(file, content)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    private fun limit(value: Int) =
        (InvocationCapacityLimit.admit(value) as io.github.amichne.kast.kernel.Refinement.Refined).value

    companion object {
        private val fingerprint = "c".repeat(64)
        private const val callKey = "7edb360f06acaef2cc80dba16cf563f199d347db4443da04da0c8173e3f9e4ed"
    }
}
