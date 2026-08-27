package support.delivery

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class Kvp032LegacyKvp009WitnessTest {
    @Test
    fun `changed KVP 009 dependency digest rejects`() {
        val changed = receipt(KVP009_CHANGED_DIGEST)

        assertInstanceOf(
            Kvp032LegacyKvp009WitnessAdmission.Rejected::class.java,
            admitKvp009ViaKvp010Witness(encodeProofReceiptDocument(changed)),
        )
    }

    @Test
    fun `pinned KVP 010 carries the admitted KVP 009 dependency`() {
        val admitted = assertInstanceOf(
            Kvp032LegacyKvp009WitnessAdmission.Complete::class.java,
            admitKvp009ViaKvp010Witness(encodeProofReceiptDocument(receipt(KVP009_DIGEST))),
        )

        assertEquals(KVP009_DIGEST, admitted.witness.dependencyDigest.value)
    }

    private fun receipt(kvp009Digest: String): ProofReceiptDocument {
        val expectation = when (val result = ProofReceiptExpectation.parse(
            receiptId = "KVP-010-COMPLETE",
            baseRevision = "78262728313c90bb847e73425dc1a76d704397db",
            exactHead = "22a314af3570687877e1627c5175287a5cf3b618",
            programFingerprint =
                "31fcef0d003e673781fe38c8aa52e9ad3c4aadec4a888764bbe17645abaf8888",
            requirementFingerprint = LEGACY_PREFIX_REQUIREMENT_FINGERPRINT,
            taskId = "KVP-010",
            gateId = "KVP-010-COMPLETE-GATE",
            dependencyReceiptDigests = linkedMapOf(
                "KVP-009-COMPLETE" to kvp009Digest,
                "KVP-010-GREEN-RECEIPT" to
                    "45d83ea3b78bd28f05412f4a53e503e9a67845d34607878449456efd4f17fe85",
                "KVP-010-RED-RECEIPT" to
                    "034113e88b6e4c1a93a9fd08943d83dbfff1239ad95109f8b55fa527625f2803",
            ),
            declaredInputDigest =
                "9e23a5ac4571b2e2e0fabfd80a07cbd2bbaa54d96973569a11e2218051d3a921",
            commandDigest =
                "6cef19479ff9475ad7e289e7e7ecc650604564c534388865a2778cf248f85f62",
            observedProofValues = linkedMapOf(
                "admittedDependencyReceiptCount" to "1",
                "admittedGateReceiptCount" to "2",
            ),
            artifactDigests = emptyMap(),
        )) {
            is ProofReceiptExpectationResult.Complete -> result.expectation
            is ProofReceiptExpectationResult.Rejected -> error("invalid fixture: ${result.failure}")
        }
        return issueProofReceipt(expectation, Instant.parse("2026-08-27T07:34:44.542720Z"))
    }

    private companion object {
        const val KVP009_DIGEST =
            "64efc0e33344ccc55f2436a6dab19e828d52d3f25a9e839ab905600e894da7ea"
        const val KVP009_CHANGED_DIGEST =
            "54efc0e33344ccc55f2436a6dab19e828d52d3f25a9e839ab905600e894da7ea"
    }
}
