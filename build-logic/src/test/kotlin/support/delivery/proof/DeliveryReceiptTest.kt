package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class DeliveryProofTest {
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
    fun `same-head receipt replay preserves identity`(@TempDir repositoryRoot: Path) {
        writeDetachedHead(repositoryRoot)
        val expectation = expectation()
        val original = encodeProofReceiptDocument(issueProofReceipt(expectation, RECORDED_AT))
        val output = repositoryRoot.resolve("build/receipt.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, original)

        assertInstanceOf(
            ProofReceiptReconciliation.Reuse::class.java,
            reconcileProofReceipt(
                ExistingProofReceiptCandidate.Present(original),
                expectation,
            ),
        )

        val admitted = issueReceiptAtBoundary(
            repositoryRoot,
            AuthorityGitRevision(EXACT_HEAD),
            expectation,
            output,
        )

        assertEquals(original, Files.readString(output))
        assertEquals(issueProofReceipt(expectation, RECORDED_AT).receiptDigest, admitted.digest)
    }

    @Test
    fun `every nonadmitted candidate selects replacement and replacement admits`() {
        val expectation = expectation()
        val cases = listOf(
            ExistingProofReceiptCandidate.Missing to ProofReceiptReplacementReason.Missing,
            ExistingProofReceiptCandidate.Present("not-json") to rejectedReplacement(
                ProofReceiptFailure.MALFORMED_DOCUMENT,
            ),
            candidate(expectation(exactHead = "0".repeat(40))) to rejectedReplacement(
                ProofReceiptFailure.EXACT_HEAD_MISMATCH,
            ),
            candidate(
                expectation(dependencies = mapOf("KVP-002-COMPLETE" to "1".repeat(64))),
            ) to rejectedReplacement(ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH),
            candidate(expectation(commandDigest = "0".repeat(64))) to rejectedReplacement(
                ProofReceiptFailure.COMMAND_DIGEST_MISMATCH,
            ),
            candidate(expectation(outcome = "QUALIFIED")) to rejectedReplacement(
                ProofReceiptFailure.OBSERVATION_MISMATCH,
            ),
            candidate(expectation(artifactDigest = "0".repeat(64))) to rejectedReplacement(
                ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH,
            ),
        )

        cases.forEach { (candidate, expectedReason) ->
            assertEquals(
                ProofReceiptReconciliation.Replace(expectedReason),
                reconcileProofReceipt(candidate, expectation),
            )
            assertInstanceOf(
                ProofReceiptAdmission.Complete::class.java,
                admitProofReceipt(issueProofReceipt(expectation, RECORDED_AT), expectation),
            )
        }
    }

    @Test
    fun `changed expectation replaces regular stale receipt`(@TempDir repositoryRoot: Path) {
        writeDetachedHead(repositoryRoot)
        val staleExpectation = expectation()
        val currentExpectation = expectation(declaredInputDigest = "0".repeat(64))
        val stale = encodeProofReceiptDocument(issueProofReceipt(staleExpectation, RECORDED_AT))
        val output = repositoryRoot.resolve("build/receipt.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, stale)

        val admitted = issueReceiptAtBoundary(
            repositoryRoot,
            AuthorityGitRevision(EXACT_HEAD),
            currentExpectation,
            output,
        )
        val current = Files.readString(output)

        assertNotEquals(stale, current)
        assertEquals(admitted.digest, issueProofReceiptDocumentDigest(current, currentExpectation))
        assertEquals(
            ProofReceiptAdmission.Rejected(ProofReceiptFailure.DECLARED_INPUT_DIGEST_MISMATCH),
            admitProofReceipt(current, staleExpectation),
        )
    }

    @Test
    fun `unsafe existing receipt state fails closed`(@TempDir repositoryRoot: Path) {
        writeDetachedHead(repositoryRoot)
        val output = repositoryRoot.resolve("build/receipt.json")
        Files.createDirectories(output.parent)
        Files.createSymbolicLink(output, repositoryRoot.resolve("outside.json"))

        assertThrows(GradleException::class.java) {
            issueReceiptAtBoundary(
                repositoryRoot,
                AuthorityGitRevision(EXACT_HEAD),
                expectation(),
                output,
            )
        }
    }

    @Test
    fun `receipt expectation head must equal revalidated head`(@TempDir repositoryRoot: Path) {
        writeDetachedHead(repositoryRoot)

        assertThrows(GradleException::class.java) {
            issueReceiptAtBoundary(
                repositoryRoot,
                AuthorityGitRevision(EXACT_HEAD),
                expectation(exactHead = "0".repeat(40)),
                repositoryRoot.resolve("build/receipt.json"),
            )
        }
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

    private fun issueProofReceiptDocumentDigest(
        raw: String,
        expectation: ProofReceiptExpectation,
    ): ProofReceiptDigest = when (val admission = admitProofReceipt(raw, expectation)) {
        is ProofReceiptAdmission.Complete -> admission.receipt.digest
        is ProofReceiptAdmission.Rejected -> error("invalid issued receipt: ${admission.failure}")
    }

    private fun writeDetachedHead(repositoryRoot: Path) {
        Files.createDirectory(repositoryRoot.resolve(".git"))
        Files.writeString(repositoryRoot.resolve(".git/HEAD"), EXACT_HEAD)
    }

    private fun expectation(
        exactHead: String = EXACT_HEAD,
        dependencies: Map<String, String> = emptyMap(),
        declaredInputDigest: String = DECLARED_INPUT_DIGEST,
        commandDigest: String = COMMAND_DIGEST,
        outcome: String = "COMPLETE",
        artifactDigest: String = ARTIFACT_DIGEST,
    ): ProofReceiptExpectation = when (
        val parsed = ProofReceiptExpectation.parse(
            RECEIPT_ID,
            BASE_REVISION,
            exactHead,
            PROGRAM_FINGERPRINT,
            REQUIREMENT_FINGERPRINT,
            "KVP-001",
            "KVP-001-RED",
            dependencies,
            declaredInputDigest,
            commandDigest,
            mapOf("outcome" to outcome),
            mapOf(ARTIFACT_PATH to artifactDigest),
        )
    ) {
        is ProofReceiptExpectationResult.Complete -> parsed.expectation
        is ProofReceiptExpectationResult.Rejected -> error("invalid fixture: ${parsed.failure}")
    }

    private fun candidate(
        expectation: ProofReceiptExpectation,
    ): ExistingProofReceiptCandidate.Present = ExistingProofReceiptCandidate.Present(
        encodeProofReceiptDocument(issueProofReceipt(expectation, RECORDED_AT)),
    )

    private fun rejectedReplacement(
        failure: ProofReceiptFailure,
    ): ProofReceiptReplacementReason = ProofReceiptReplacementReason.Rejected(failure)

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

internal class DeliveryProofNegativeTest {
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
