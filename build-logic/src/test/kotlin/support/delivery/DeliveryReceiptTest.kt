package support.delivery

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DeliveryProofTest {
    @Test
    fun `exact receipt refines to admitted proof`() {
        val expectation = expectation()
        val document = issueProofReceipt(expectation, RECORDED_AT)

        val result = admitProofReceipt(encodeProofReceiptDocument(document), expectation)

        val complete = assertInstanceOf(ProofReceiptAdmission.Complete::class.java, result)
        assertEquals(RECEIPT_ID, complete.receipt.receiptId.value)
        assertEquals(EXACT_HEAD, complete.receipt.exactHead.value)
        assertEquals(document.receiptDigest, complete.receipt.digest)
    }

    @Test
    fun `every changed bound field invalidates receipt`() {
        val expectation = expectation()
        val document = issueProofReceipt(expectation, RECORDED_AT)
        val changedDigest = ProofReceiptDigest("1".repeat(64))
        val cases = listOf(
            document.copy(receiptId = ProofReceiptId("KVP-002-RED-RECEIPT")) to
                ProofReceiptFailure.RECEIPT_ID_MISMATCH,
            document.copy(baseRevision = AuthorityGitRevision("0".repeat(40))) to
                ProofReceiptFailure.BASE_REVISION_MISMATCH,
            document.copy(exactHead = AuthorityGitRevision("0".repeat(40))) to
                ProofReceiptFailure.EXACT_HEAD_MISMATCH,
            document.copy(programFingerprint = ProgramFingerprint("0".repeat(64))) to
                ProofReceiptFailure.PROGRAM_FINGERPRINT_MISMATCH,
            document.copy(requirementFingerprint = RequirementFingerprint("0".repeat(64))) to
                ProofReceiptFailure.REQUIREMENT_FINGERPRINT_MISMATCH,
            document.copy(taskId = TaskId("KVP-002")) to ProofReceiptFailure.TASK_ID_MISMATCH,
            document.copy(gateId = ProofGateId("KVP-002-RED")) to
                ProofReceiptFailure.GATE_ID_MISMATCH,
            document.copy(
                dependencyReceiptDigests = mapOf(
                    ProofReceiptId("KVP-002-COMPLETE") to changedDigest,
                ),
            ) to ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH,
            document.copy(declaredInputDigest = DeclaredInputDigest("0".repeat(64))) to
                ProofReceiptFailure.DECLARED_INPUT_DIGEST_MISMATCH,
            document.copy(commandDigest = ProofCommandDigest("0".repeat(64))) to
                ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
            document.copy(
                observedProofValues = mapOf(
                    ProofObservationName("outcome") to ProofObservationValue("QUALIFIED"),
                ),
            ) to ProofReceiptFailure.OBSERVATION_MISMATCH,
            document.copy(
                artifactDigests = mapOf(
                    ProofArtifactPath(ARTIFACT_PATH) to AuthorityArtifactDigest("0".repeat(64)),
                ),
            ) to ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH,
            document.copy(recordedAtUtc = ProofRecordedAt("2026-08-25T00:00:01Z")) to
                ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH,
            document.copy(receiptDigest = ProofReceiptDigest("0".repeat(64))) to
                ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH,
        )

        cases.forEach { (changed, expectedFailure) ->
            assertEquals(
                ProofReceiptAdmission.Rejected(expectedFailure),
                admitProofReceipt(changed, expectation),
            )
        }
    }

    @Test
    fun `recomputed digest cannot admit changed dependency`() {
        val expectation = expectation()
        val original = issueProofReceipt(expectation, RECORDED_AT)
        val changed = original.copy(
            dependencyReceiptDigests = mapOf(
                ProofReceiptId("KVP-002-COMPLETE") to ProofReceiptDigest("1".repeat(64)),
            ),
        ).let { it.copy(receiptDigest = it.derivedDigest()) }

        assertEquals(
            ProofReceiptAdmission.Rejected(
                ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH,
            ),
            admitProofReceipt(changed, expectation),
        )
    }

    @Test
    fun `unknown fields and noncanonical timestamps reject at JSON boundary`() {
        val document = issueProofReceipt(expectation(), RECORDED_AT)
        val raw = encodeProofReceiptDocument(document)

        assertEquals(
            ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.MALFORMED_DOCUMENT),
            decodeProofReceiptDocument(raw.replaceFirst("{", "{\"status\":\"PASS\",")),
        )
        assertEquals(
            ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.MALFORMED_RECORDED_AT),
            decodeProofReceiptDocument(
                raw.replace("2026-08-25T00:00:00Z", "2026-08-25T00:00:00+00:00"),
            ),
        )
    }

    @Test
    fun `authority gate reports refine only with exact closed observations`() {
        val negative = AuthorityNegativeProofDocument(
            1,
            "KVP-001-RED",
            AuthorityNegativeCase.entries,
        )
        val negativeRaw = authorityEvidenceJson.encodeToString(
            AuthorityNegativeProofDocument.serializer(),
            negative,
        )
        assertInstanceOf(
            AuthorityGateProofObservation.NegativeComplete::class.java,
            observeAuthorityNegativeProof(negativeRaw),
        )
        assertEquals(
            AuthorityGateProofObservation.Rejected(
                AuthorityGateProofFailure.REJECTED_CASE_SET_MISMATCH,
            ),
            observeAuthorityNegativeProof(
                authorityEvidenceJson.encodeToString(
                    AuthorityNegativeProofDocument.serializer(),
                    negative.copy(rejectedCases = negative.rejectedCases.dropLast(1)),
                ),
            ),
        )

        val sourceDigests = mapOf("deliveryAuthority" to ARTIFACT_DIGEST)
        val verification = AuthorityVerificationDocument(
            1,
            "KVP-001-GREEN",
            BASE_REVISION,
            EXACT_HEAD,
            PROGRAM_FINGERPRINT,
            REQUIREMENT_FINGERPRINT,
            sourceDigests,
        )
        val verificationRaw = authorityEvidenceJson.encodeToString(
            AuthorityVerificationDocument.serializer(),
            verification,
        )
        assertInstanceOf(
            AuthorityGateProofObservation.VerificationComplete::class.java,
            observeAuthorityVerificationProof(
                verificationRaw,
                BASE_REVISION,
                EXACT_HEAD,
                PROGRAM_FINGERPRINT,
                REQUIREMENT_FINGERPRINT,
                sourceDigests,
            ),
        )
        assertEquals(
            AuthorityGateProofObservation.Rejected(
                AuthorityGateProofFailure.SOURCE_DIGESTS_MISMATCH,
            ),
            observeAuthorityVerificationProof(
                verificationRaw,
                BASE_REVISION,
                EXACT_HEAD,
                PROGRAM_FINGERPRINT,
                REQUIREMENT_FINGERPRINT,
                emptyMap(),
            ),
        )
    }

    private fun expectation(): ProofReceiptExpectation = when (
        val parsed = ProofReceiptExpectation.parse(
            RECEIPT_ID,
            BASE_REVISION,
            EXACT_HEAD,
            PROGRAM_FINGERPRINT,
            REQUIREMENT_FINGERPRINT,
            "KVP-001",
            "KVP-001-RED",
            emptyMap(),
            DECLARED_INPUT_DIGEST,
            COMMAND_DIGEST,
            mapOf("outcome" to "COMPLETE"),
            mapOf(ARTIFACT_PATH to ARTIFACT_DIGEST),
        )
    ) {
        is ProofReceiptExpectationResult.Complete -> parsed.expectation
        is ProofReceiptExpectationResult.Rejected -> error("invalid fixture: ${parsed.failure}")
    }

    private companion object {
        val RECORDED_AT: Instant = Instant.parse("2026-08-25T00:00:00Z")
        const val RECEIPT_ID = "KVP-001-RED-RECEIPT"
        const val BASE_REVISION = "78262728313c90bb847e73425dc1a76d704397db"
        const val EXACT_HEAD = "cf4dd72741fb9484bed4aceef72435e09b65d40a"
        const val PROGRAM_FINGERPRINT = "4e848e480c104cd13abcf57a691091b8204cf7c82f8ad0e893f94315e2fccced"
        const val REQUIREMENT_FINGERPRINT = "de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"
        const val DECLARED_INPUT_DIGEST = "1b49daf7820267dbb4f89e6372c5b2cc1cf5457988c07396eb8438347ed6058b"
        const val COMMAND_DIGEST = "1a147dad9103719637430979dce68bfe2d50fa6fe998e7a4ea44963e0732c369"
        const val ARTIFACT_PATH = "build/reports/delivery/KVP-001-authority-negative.json"
        const val ARTIFACT_DIGEST = "7dc43f4fbf0592686e75b5dc2bc1f42da96d5fe53a18817c6938065a2ecab727"
    }
}

class DeliveryProofNegativeTest {
    @Test
    fun `every bound receipt mutation rejects with its exact finite failure`() {
        val proof = assertInstanceOf(
            DeliveryProofResult.Complete::class.java,
            deriveDeliveryProof(),
        ).proof

        assertEquals(DeliveryProofInvalidation.entries.toSet(), proof.invalidations.keys)
        assertEquals(
            ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH,
            proof.invalidations.getValue(DeliveryProofInvalidation.FORGED_DIGEST),
        )
        assertEquals(
            ProofReceiptFailure.EXACT_HEAD_MISMATCH,
            proof.invalidations.getValue(DeliveryProofInvalidation.EXACT_HEAD),
        )
        assertEquals(
            ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH,
            proof.invalidations.getValue(DeliveryProofInvalidation.DEPENDENCY_RECEIPT),
        )
        assertEquals(
            ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH,
            proof.invalidations.getValue(DeliveryProofInvalidation.ARTIFACT),
        )
    }
}
