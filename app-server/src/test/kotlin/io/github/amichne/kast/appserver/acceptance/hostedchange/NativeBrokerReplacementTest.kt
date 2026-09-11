package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.InvocationAdmission
import io.github.amichne.kast.appserver.runtime.InvocationFence
import io.github.amichne.kast.appserver.runtime.InvocationFenceFailure
import io.github.amichne.kast.appserver.runtime.InvocationPhase
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
        val reopened = InvocationFence(root.resolve("invocations.json"))
        assertEquals(InvocationAdmission.Admitted, reopened.initialization())
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
            reopened.admit("uncertain-call", "c".repeat(64)),
        )
        retained.requireUnchanged(root)
        val initial = journal(InvocationPhase.UNCERTAIN)
        val appended = initial.copy(records = initial.records + ("b".repeat(64) to record(InvocationPhase.COMPLETED)))
        Files.writeString(root.resolve("invocations.json"), Json.encodeToString(appended))
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
        Files.writeString(root.resolve("threads.json"), "deliberately invalid thread catalog")
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
        val afterUnload =
            journal(InvocationPhase.UNCERTAIN)
                .copy(
                    records =
                        journal(InvocationPhase.UNCERTAIN).records + (unloadKey to record(InvocationPhase.UNCERTAIN))
                )
        Files.writeString(root.resolve("invocations.json"), Json.encodeToString(afterUnload))
        val retained = NativeBrokerStoreSnapshot.capture(root)
        retained.requireNewUncertainInvocationSince(beforeUnload)
        val reopened = InvocationFence(root.resolve("invocations.json"))
        assertEquals(InvocationAdmission.Admitted, reopened.initialization())
        assertEquals(
            InvocationAdmission.Rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
            reopened.admit("unload-call", "c".repeat(64)),
        )
        retained.requireUnchanged(root)
        NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
        for (phase in listOf(InvocationPhase.COMPLETED, InvocationPhase.STARTED)) {
            Files.writeString(
                root.resolve("invocations.json"),
                Json.encodeToString(afterUnload.copy(records = afterUnload.records + (unloadKey to record(phase)))),
            )
            assertThrows(NativeRejected::class.java) {
                NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
            }
        }
        val extra =
            afterUnload.copy(records = afterUnload.records + ("d".repeat(64) to record(InvocationPhase.UNCERTAIN)))
        Files.writeString(root.resolve("invocations.json"), Json.encodeToString(extra))
        assertThrows(NativeRejected::class.java) {
            NativeBrokerStoreSnapshot.capture(root).requireNewUncertainInvocationSince(beforeUnload)
        }
    }

    private fun stores(root: Path, phase: InvocationPhase) {
        Files.writeString(root.resolve("invocations.json"), Json.encodeToString(journal(phase)))
        Files.writeString(root.resolve("threads.json"), Json.encodeToString(EmptyThreadCatalog(2, emptyList())))
    }

    private fun journal(phase: InvocationPhase) =
        NativeRetainedInvocationJournal(1, mapOf(InvocationFence.digest("uncertain-call") to record(phase)))

    private fun record(phase: InvocationPhase) = NativeRetainedInvocation("c".repeat(64), phase)

    @Serializable private data class RequestId(val id: Int)

    @Serializable private data class EmptyThreadCatalog(val version: Int, val bindings: List<String>)
}
