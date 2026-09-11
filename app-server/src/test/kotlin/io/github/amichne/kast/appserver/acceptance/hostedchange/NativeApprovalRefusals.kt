package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

internal class NativeApprovalRefusals(
    private val peer: NativeChangePeer,
    private val source: Path,
    private val evidence: NativeChangeEvidence,
) {
    suspend fun run(arguments: JsonObject, preview: suspend (NativeApprovalPreview) -> Unit) {
        val before = Files.readAllBytes(source)
        for (decision in listOf(NativeDecision.DECLINE, NativeDecision.CANCEL, NativeDecision.MALFORMED)) {
            val case = "approval-${decision.name.lowercase()}"
            evidence.record(case, NativeCaseOutcome.UNQUALIFIED)
            val result =
                peer.call(
                    tool = "change_apply",
                    arguments = arguments,
                    decision = decision,
                    beforeApproval = preview,
                )
            val expected =
                when (decision) {
                    NativeDecision.DECLINE -> NativeExpectedRejection.APPROVAL_DECLINED
                    NativeDecision.CANCEL -> NativeExpectedRejection.APPROVAL_CANCELLED
                    NativeDecision.MALFORMED -> NativeExpectedRejection.APPROVAL_CONTROLLER_REJECTED
                    NativeDecision.ACCEPT -> throw NativeRejected(NativeFailure.INPUT_REJECTED)
                }
            evidence.expectRejection(case, expected, result)
            demand(Files.readAllBytes(source).contentEquals(before), NativeFailure.SOURCE_CHANGED)
            evidence.record(case, NativeCaseOutcome.PASSED)
        }
    }
}
