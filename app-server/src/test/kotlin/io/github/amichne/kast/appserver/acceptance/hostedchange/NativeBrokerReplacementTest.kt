package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.InvocationAdmission
import io.github.amichne.kast.appserver.runtime.InvocationFence
import io.github.amichne.kast.appserver.runtime.InvocationFenceFailure
import io.github.amichne.kast.appserver.runtime.InvocationPhase
import io.github.amichne.kast.appserver.runtime.InvocationRecordDocument
import io.github.amichne.kast.appserver.runtime.InvocationSettlement
import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionFailure
import io.github.amichne.kast.appserver.runtime.toolFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeBrokerReplacementTest {
    @Test
    fun `workspace failures retain exact finite identity and independent observed shape`() {
        val request = Json.encodeToJsonElement(RequestId.serializer(), RequestId(61)).jsonObject
        for (failure in WorkspaceExecutionFailure.entries) {
            val raw = toolFailure(request, failure.name)
            val observed = protocolObservation(raw)
            assertEquals(setOf("sha256", "failure", "canonicalRejection", "workspaceFailure"), observed.keys)
            assertEquals(JsonPrimitive(sha256(raw.toByteArray())), observed["sha256"])
            assertEquals(JsonPrimitive(failure.name), observed["failure"])
            assertEquals(JsonPrimitive(failure.name), observed["workspaceFailure"])
            assertEquals(JsonPrimitive("NONE"), observed["canonicalRejection"])
        }
        val unknown = protocolObservation(toolFailure(request, "UNRECOGNIZED_FAILURE"))
        assertEquals(JsonPrimitive("UNCLASSIFIED"), unknown["failure"])
    }

    @Test
    fun `post save evidence encodes recovery rejection retained stores and exact rollback`() {
        val evidence =
            NativePostSaveObservation(
                sourcePreimageSha256 = "before",
                sourcePostimageSha256 = "after",
                brokerFailure = WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED,
                retryInvocationCount = 0,
                invocationJournalSha256 = "journal",
                threadStoreSha256 = "threads",
                brokerReplacement = NativeBrokerReplacement.STORES_RETAINED,
                recoveryState = NativeObservedChangeState.ROLLED_BACK,
            )
        assertEquals(
            Json.parseToJsonElement(
                """
                {"sourcePreimageSha256":"before","sourcePostimageSha256":"after",
                "brokerFailure":"WORKSPACE_RECOVERY_REQUIRED","retryInvocationCount":0,
                "invocationJournalSha256":"journal","threadStoreSha256":"threads",
                "brokerReplacement":"STORES_RETAINED","recoveryState":"ROLLED_BACK"}
                """
            ),
            Json.encodeToJsonElement(NativePostSaveObservation.serializer(), evidence),
        )
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<NativeBrokerReplacement>("\"UNKNOWN\"")
        }
    }

    @Test
    fun `replacement retains uncertain journal evidence while new records accumulate`(@TempDir root: Path) {
        stores(root, InvocationPhase.UNCERTAIN)
        val retained = NativeBrokerStoreSnapshot.capture(root)
        val reopened = InvocationFence(root.toRealPath().resolve("invocations.json"))
        assertEquals(InvocationAdmission.Admitted, reopened.initialization())
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
            reopened.admit("uncertain-call", "c".repeat(64)),
        )
        retained.requireUnchanged(root)
        assertEquals(InvocationAdmission.Admitted, reopened.admit("appended-call", "c".repeat(64)))
        assertEquals(InvocationAdmission.Admitted, reopened.finish("appended-call", InvocationSettlement.COMPLETED))
        retained.requireRecordsRetained(root)
        assertThrows(NativeRejected::class.java) { retained.requireUnchanged(root) }
        stores(root, InvocationPhase.COMPLETED)
        assertThrows(NativeRejected::class.java) { retained.requireRecordsRetained(root) }
        assertThrows(NativeRejected::class.java) { NativeBrokerStoreSnapshot.capture(root) }
        NativeBrokerStoreSnapshot.capture(root, NativeBrokerRetentionExpectation.SETTLED).requireUnchanged(root)
        stores(root, InvocationPhase.STARTED)
        assertThrows(NativeRejected::class.java) {
            NativeBrokerStoreSnapshot.capture(root, NativeBrokerRetentionExpectation.SETTLED)
        }
    }

    @Test
    fun `changed thread store cannot pass replacement proof`(@TempDir root: Path) {
        stores(root, InvocationPhase.UNCERTAIN)
        val retained = NativeBrokerStoreSnapshot.capture(root)
        Files.writeString(
            root.resolve("threads.json.d/layout.json"),
            Json.encodeToString(io.github.amichne.kast.appserver.protocol.ThreadStoreLayout(4)),
        )
        assertThrows(NativeRejected::class.java) { retained.requireUnchanged(root) }
    }

    @Test
    fun `unload replacement proves the new invocation remains uncertain despite older uncertain records`(
        @TempDir root: Path
    ) {
        stores(root, InvocationPhase.UNCERTAIN)
        val beforeUnload = NativeBrokerStoreSnapshot.capture(root)
        assertThrows(NativeRejected::class.java) { beforeUnload.requireNewUncertainInvocationSince(beforeUnload) }
        val unloadKey = InvocationFence.digest("unload-call")
        writeRecord(root, unloadKey, InvocationPhase.UNCERTAIN)
        val retained = NativeBrokerStoreSnapshot.capture(root)
        retained.requireNewUncertainInvocationSince(beforeUnload)
        val reopened = InvocationFence(root.toRealPath().resolve("invocations.json"))
        assertEquals(InvocationAdmission.Admitted, reopened.initialization())
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
            reopened.admit("unload-call", "c".repeat(64)),
        )
        retained.requireUnchanged(root)
        NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
        for (phase in listOf(InvocationPhase.COMPLETED, InvocationPhase.STARTED)) {
            writeRecord(root, unloadKey, phase)
            assertThrows(NativeRejected::class.java) {
                NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
            }
        }
        writeRecord(root, unloadKey, InvocationPhase.UNCERTAIN)
        writeRecord(root, "d".repeat(64), InvocationPhase.UNCERTAIN)
        assertThrows(NativeRejected::class.java) {
            NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
        }
    }

    private fun stores(root: Path, phase: InvocationPhase) {
        val file = root.toRealPath().resolve("invocations.json")
        assertEquals(InvocationAdmission.Admitted, InvocationFence(file).initialization())
        writeRecord(root, InvocationFence.digest("uncertain-call"), phase)
        assertEquals(
            true,
            io.github.amichne.kast.appserver.protocol.FileThreadCatalogStore.open(
                root.toRealPath().resolve("threads.json")
            ) is io.github.amichne.kast.appserver.protocol.FileThreadCatalogStoreOpen.Opened,
        )
    }

    private fun writeRecord(root: Path, key: String, phase: InvocationPhase) {
        val shard = root.resolve("invocations.json.d/records-v2").resolve(key.take(2))
        Files.createDirectories(shard)
        Files.setPosixFilePermissions(shard, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val path = shard.resolve("$key.json")
        Files.writeString(path, Json.encodeToString(InvocationRecordDocument(2, key, "c".repeat(64), phase)))
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }

    @Serializable private data class RequestId(val id: Int)
}
