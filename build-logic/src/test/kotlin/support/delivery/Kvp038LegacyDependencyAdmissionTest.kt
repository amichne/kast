package support.delivery

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class Kvp038LegacyDependencyAdmissionTest {
    @Test
    fun `unchanged legacy content survives a later program fingerprint`() {
        val fixture = legacyClosure()
        val currentFingerprint = KastVfsPassiveReusedIndexProgram.validated.projection()
            .getValue("programFingerprint") as String

        assertNotEquals(LEGACY_PROGRAM_FINGERPRINT, currentFingerprint)
        assertInstanceOf(
            Kvp038LegacyClosureAdmission.Complete::class.java,
            admitKvp008LegacyClosure(
                fixture.red,
                fixture.green,
                fixture.complete,
                fixture.report,
            ),
        )
    }

    @Test
    fun `mixed legacy fingerprints reject`() {
        val fixture = legacyClosure()
        val changed = fixture.green.copy(
            programFingerprint = ProgramFingerprint("2".repeat(64)),
        ).let { it.copy(receiptDigest = it.derivedDigest()) }

        assertInstanceOf(
            Kvp038LegacyClosureAdmission.Rejected::class.java,
            admitKvp008LegacyClosure(
                fixture.red,
                changed,
                fixture.complete,
                fixture.report,
            ),
        )
    }

    private fun legacyClosure(): LegacyClosureFixture {
        val report = "{\"outcome\":\"COMPLETE\"}\n"
        val red = receipt("KVP-008-RED-RECEIPT", "KVP-008-RED")
        val green = receipt(
            "KVP-008-GREEN-RECEIPT",
            "KVP-008-GREEN",
            dependencies = mapOf(red.receiptId.value to red.receiptDigest.value),
            artifacts = mapOf(REPORT_PATH to sha256(report).value),
        )
        val complete = receipt(
            "KVP-008-COMPLETE",
            "KVP-008-COMPLETE-GATE",
            dependencies = mapOf(
                red.receiptId.value to red.receiptDigest.value,
                green.receiptId.value to green.receiptDigest.value,
            ),
        )
        return LegacyClosureFixture(red, green, complete, report)
    }

    private fun receipt(
        receiptId: String,
        gateId: String,
        dependencies: Map<String, String> = emptyMap(),
        artifacts: Map<String, String> = emptyMap(),
    ): ProofReceiptDocument {
        val expectation = when (val parsed = ProofReceiptExpectation.parse(
            receiptId = receiptId,
            baseRevision = "a".repeat(40),
            exactHead = "b".repeat(40),
            programFingerprint = LEGACY_PROGRAM_FINGERPRINT,
            requirementFingerprint = "c".repeat(64),
            taskId = "KVP-008",
            gateId = gateId,
            dependencyReceiptDigests = dependencies,
            declaredInputDigest = "d".repeat(64),
            commandDigest = "e".repeat(64),
            observedProofValues = mapOf("outcome" to "COMPLETE"),
            artifactDigests = artifacts,
        )) {
            is ProofReceiptExpectationResult.Complete -> parsed.expectation
            is ProofReceiptExpectationResult.Rejected -> error("invalid fixture: ${parsed.failure}")
        }
        return issueProofReceipt(expectation, Instant.parse("2026-08-27T00:00:00Z"))
    }

    private data class LegacyClosureFixture(
        val red: ProofReceiptDocument,
        val green: ProofReceiptDocument,
        val complete: ProofReceiptDocument,
        val report: String,
    )

    private companion object {
        const val LEGACY_PROGRAM_FINGERPRINT =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val REPORT_PATH = "build/reports/delivery/KVP-008-derived-state.json"
    }
}
