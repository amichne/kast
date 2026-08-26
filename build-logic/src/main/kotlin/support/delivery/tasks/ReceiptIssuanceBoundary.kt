package support.delivery

import java.nio.file.Path
import java.time.Instant

/**
 * Proof transition: exact-head `ProofReceiptExpectation` plus optional existing receipt bytes ->
 * stable `AdmittedProofReceipt`.
 *
 * Reuses a fully admitted existing receipt so same-head replay preserves its identity. Missing or
 * contract-rejected regular evidence is atomically replaced, read back, and admitted. Unsafe file
 * states fail closed. The expectation head must equal the revalidated repository head before
 * either result is returned. Expected failures remain finite [ProofReceiptFailure] values until
 * rendered at this Gradle boundary.
 */
internal fun issueReceiptAtBoundary(
    repositoryRoot: Path,
    exactHead: AuthorityGitRevision,
    expectation: ProofReceiptExpectation,
    output: Path,
): AdmittedProofReceipt {
    if (expectation.exactHead != exactHead) {
        rejectReceipt("receipt issuance head", ProofReceiptFailure.EXACT_HEAD_MISMATCH)
    }
    revalidateExactHead(repositoryRoot, exactHead)
    val candidate = when (val existing = readBoundaryFile(output, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is BoundaryFileRead.Complete -> ExistingProofReceiptCandidate.Present(
            existing.bytes.toString(Charsets.UTF_8),
        )
        is BoundaryFileRead.Rejected -> when (existing.failure) {
            AuthoritySourceFailure.MISSING -> ExistingProofReceiptCandidate.Missing
            else -> rejectReceipt(
                "existing receipt",
                ProofReceiptFailure.MALFORMED_DOCUMENT,
                existing.failure.name,
            )
        }
    }
    when (val reconciliation = reconcileProofReceipt(candidate, expectation)) {
        is ProofReceiptReconciliation.Reuse -> {
            revalidateExactHead(repositoryRoot, exactHead)
            return reconciliation.receipt
        }
        is ProofReceiptReconciliation.Replace -> Unit
    }
    val document = issueProofReceipt(expectation, Instant.now())
    writeTextAtomically(output, encodeProofReceiptDocument(document))
    val raw = when (val read = readBoundaryFile(output, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
        is BoundaryFileRead.Rejected -> rejectReceipt(
            "written receipt",
            ProofReceiptFailure.MALFORMED_DOCUMENT,
            read.failure.name,
        )
    }
    return when (val admission = admitProofReceipt(raw, expectation)) {
        is ProofReceiptAdmission.Complete -> {
            revalidateExactHead(repositoryRoot, exactHead)
            admission.receipt
        }
        is ProofReceiptAdmission.Rejected -> rejectReceipt("written receipt", admission.failure)
    }
}
