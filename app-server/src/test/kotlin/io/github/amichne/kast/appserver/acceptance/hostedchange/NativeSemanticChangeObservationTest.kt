package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeSemanticChangeObservationTest {
    @Test
    fun `verified change retains finite state and discards receipt and source payloads`() {
        val result =
            processObservation(
                BrokerProcessExecution.Completed(
                    0,
                    """{"operation":"change.apply","status":"complete","state":"verified",
                "receiptIdentity":"private-receipt","changes":"private-source"}""",
                    "",
                )
            )
        val change = result.getValue("change").jsonObject
        assertEquals(JsonPrimitive("VERIFIED"), change["state"])
        assertEquals(JsonPrimitive("COMPLETE"), change["status"])
        assertFalse(result.toString().contains("private-"))
    }

    @Test
    fun `qualified outcomes retain exact existing recovery and verification enums`() {
        val recovery = observe("recovery_required", "write-outcome-unknown")
        assertEquals(NativeObservedChangeState.RECOVERY_REQUIRED, recovery.state)
        assertEquals(
            NativeObservedChangeReason.ApplyRecovery(ChangeApplyRecoveryReason.WRITE_OUTCOME_UNKNOWN),
            recovery.reason,
        )
        val unverified = observe("applied_unverified", "verification-unavailable")
        assertEquals(
            NativeObservedChangeReason.ApplyUnverified(ChangeApplyUnverifiedReason.VERIFICATION_UNAVAILABLE),
            unverified.reason,
        )
        assertEquals(NativeObservedChangeReason.Unclassified, observe("recovery_required", "private-reason").reason)
        assertEquals(
            NativeObservedChangeState.UNCLASSIFIED,
            observe("recovery-required", "write-outcome-unknown").state,
        )
    }

    @Test
    fun `concurrent result file retains both outcomes before workflow assertions`(@TempDir root: Path) {
        val qualified =
            NativeToolResult.Document(
                Json.parseToJsonElement(
                        """{"operation":"change.apply","status":"qualified","state":"recovery_required",
            "reason":"write-outcome-unknown"}"""
                    )
                    .jsonObject,
                NativeToolSuccess.SUCCEEDED,
            )
        val host =
            NativeToolResult.Document(
                Json.parseToJsonElement("""{"type":"HOST_REJECTED","failure":"APPROVAL_REJECTED"}""").jsonObject,
                NativeToolSuccess.FAILED,
            )
        val evidence = NativeChangeEvidence(root.resolve("report.json"))
        evidence.concurrentResults(listOf(qualified, host))
        val raw = Files.readString(root.resolve("concurrent-results.private.json"))
        val observed = Json.decodeFromString(NativeConcurrentObservation.serializer(), raw)
        assertEquals(2, observed.results.size)
        val second = observed.results[1] as NativeConcurrentResultObservation.Document
        assertEquals(
            NativeObservedChangeReason.HostRejection(NativeObservedHostFailure.APPROVAL_REJECTED),
            second.change.reason,
        )
    }

    private fun observe(state: String, reason: String): NativeSemanticChangeObservation =
        nativeSemanticChangeObservation(
            Json.parseToJsonElement(
                    """{"operation":"change.apply","status":"qualified","state":"$state","reason":"$reason"}"""
                )
                .jsonObject
        )
}
