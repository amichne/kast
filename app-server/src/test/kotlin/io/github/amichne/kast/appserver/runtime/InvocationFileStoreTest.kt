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
    fun `completed history does not consume active capacity`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val fence = InvocationFence(file, maximumActive = 1)
        repeat(3) { ordinal ->
            assertEquals(InvocationAdmission.Admitted, fence.admit("call-$ordinal", fingerprint))
            assertEquals(rejected(InvocationFenceFailure.CAPACITY_EXCEEDED), fence.admit("other", fingerprint))
            assertEquals(InvocationAdmission.Admitted, fence.finish("call-$ordinal", InvocationSettlement.COMPLETED))
        }
        val reopened = InvocationFence(file, maximumActive = 1)
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
    fun `migration preserves every phase and original bytes before publishing layout`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val source =
            LegacyInvocationDocument(
                1,
                InvocationPhase.entries.associate { phase ->
                    InvocationFence.digest(phase.name) to LegacyInvocationRecord(fingerprint, phase)
                },
            )
        privateWrite(file, Json.encodeToString(source))
        val original = Files.readAllBytes(file)
        val observations = mutableListOf<InvocationMigrationObservation>()
        val fence = InvocationFence(file, observeMigration = observations::add)
        assertEquals(InvocationAdmission.Admitted, fence.initialization())
        assertTrue(original.contentEquals(Files.readAllBytes(file)))
        assertEquals(
            listOf(InvocationMigrationOutcome.Started, InvocationMigrationOutcome.Committed),
            observations.map { it.outcome },
        )
        for (phase in InvocationPhase.entries) {
            val expected =
                if (phase == InvocationPhase.COMPLETED) InvocationFenceFailure.ALREADY_COMPLETED
                else InvocationFenceFailure.OUTCOME_UNCERTAIN
            assertEquals(rejected(expected), fence.admit(phase.name, fingerprint))
            assertEquals(
                rejected(InvocationFenceFailure.TRANSITION_REJECTED),
                fence.finish(phase.name, InvocationSettlement.COMPLETED),
            )
        }
        observations.clear()
        assertEquals(
            InvocationAdmission.Admitted,
            InvocationFence(file, observeMigration = observations::add).initialization(),
        )
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `an incomplete migration remains rejected and retained`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val directory = store(file)
        Files.createDirectory(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val stage = Files.createDirectory(directory.resolve(".migration-interrupted"))
        val observations = mutableListOf<InvocationMigrationObservation>()
        val fence = InvocationFence(file, observeMigration = observations::add)
        assertEquals(rejected(InvocationFenceFailure.MIGRATION_REJECTED), fence.initialization())
        assertTrue(Files.exists(stage))
        assertFalse(Files.exists(directory.resolve("layout.json")))
        assertEquals(
            listOf(
                InvocationMigrationOutcome.Started,
                InvocationMigrationOutcome.Rejected(InvocationFenceFailure.MIGRATION_REJECTED),
            ),
            observations.map { it.outcome },
        )
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
        val replaced = InvocationRecordDocument(2, "b".repeat(64), fingerprint, InvocationPhase.COMPLETED)
        privateWrite(path, Json.encodeToString(replaced))
        assertEquals(rejected(InvocationFenceFailure.STORE_REJECTED), fence.admit("call", fingerprint))
        assertEquals(Json.encodeToString(replaced), Files.readString(path))
    }

    @Test
    fun `malformed duplicate legacy fields never publish migrated evidence`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        // Deliberately invalid duplicate fields: a decoder must not erase contradictory evidence.
        privateWrite(file, """{"schemaVersion":9,"schemaVersion":1,"records":{}}""")
        val observations = mutableListOf<InvocationMigrationObservation>()
        assertEquals(
            rejected(InvocationFenceFailure.STORE_REJECTED),
            InvocationFence(file, observeMigration = observations::add).initialization(),
        )
        assertFalse(Files.exists(store(file).resolve("layout.json")))
        assertEquals(
            InvocationMigrationOutcome.Rejected(InvocationFenceFailure.STORE_REJECTED),
            observations.last().outcome,
        )
    }

    @Test
    fun `migration observations have bounded closed wire shapes`() {
        val started =
            Json.parseToJsonElement(InvocationMigrationObservation(InvocationMigrationOutcome.Started).toJson())
                .jsonObject
        assertEquals(setOf("event", "outcome"), started.keys)
        assertEquals(JsonPrimitive("kast_invocation_migration"), started["event"])
        assertEquals(setOf("type"), started.getValue("outcome").jsonObject.keys)
        assertEquals(JsonPrimitive("started"), started.getValue("outcome").jsonObject["type"])
        val committed =
            Json.parseToJsonElement(InvocationMigrationObservation(InvocationMigrationOutcome.Committed).toJson())
                .jsonObject
        assertEquals(JsonPrimitive("committed"), committed.getValue("outcome").jsonObject["type"])
        for (failure in InvocationFenceFailure.entries) {
            val rejected =
                Json.parseToJsonElement(
                        InvocationMigrationObservation(InvocationMigrationOutcome.Rejected(failure)).toJson()
                    )
                    .jsonObject
                    .getValue("outcome")
                    .jsonObject
            assertEquals(setOf("type", "failure"), rejected.keys)
            assertEquals(JsonPrimitive("rejected"), rejected["type"])
            assertEquals(JsonPrimitive(failure.name), rejected["failure"])
        }
    }

    private fun store(file: Path) = file.resolveSibling("${file.fileName}.d")

    private fun recordPath(file: Path, key: String) =
        store(file).resolve("records-v2").resolve(key.take(2)).resolve("$key.json")

    private fun rejected(failure: InvocationFenceFailure) = InvocationAdmission.Rejected(failure)

    private fun privateWrite(file: Path, content: String) {
        Files.writeString(file, content)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    companion object {
        private val fingerprint = "c".repeat(64)
        private const val callKey = "7edb360f06acaef2cc80dba16cf563f199d347db4443da04da0c8173e3f9e4ed"
    }
}
