package support.delivery

import java.nio.file.Path
import java.time.Instant

/**
 * Proof transition: exact observed head plus `TaskProofReceiptExpectation` and optional existing
 * evidence -> `AdmittedTaskProofReceipt`.
 *
 * Reuses an existing receipt only when its complete content closure admits. Content-scoped replay
 * preserves the original observed head; exact-head replay additionally requires the current head.
 * Missing or contract-rejected regular evidence is replaced atomically and re-admitted. Unsafe
 * filesystem state fails closed at this Gradle boundary.
 */
internal fun issueTaskProofReceiptAtBoundary(
    repositoryRoot: Path,
    observedHead: DeliveryGeneration,
    expectation: TaskProofReceiptExpectation,
    output: Path,
): AdmittedTaskProofReceipt {
    revalidateExactHead(repositoryRoot, AuthorityGitRevision(observedHead.value))
    when (val existing = readBoundaryFile(output, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is BoundaryFileRead.Complete -> when (val admitted = admitTaskProofReceipt(
            existing.bytes.toString(Charsets.UTF_8),
            expectation,
            observedHead,
        )) {
            is TaskProofReceiptAdmission.Complete -> {
                revalidateExactHead(repositoryRoot, AuthorityGitRevision(observedHead.value))
                return admitted.receipt
            }
            is TaskProofReceiptAdmission.Rejected -> Unit
        }
        is BoundaryFileRead.Rejected -> when (existing.failure) {
            AuthoritySourceFailure.MISSING -> Unit
            else -> rejectTaskProofReceipt(
                "existing task proof receipt",
                TaskProofReceiptFailure.MALFORMED_DOCUMENT,
            )
        }
    }
    val document = issueTaskProofReceipt(expectation, observedHead, Instant.now())
    writeTextAtomically(output, encodeTaskProofReceipt(document))
    val raw = when (val read = readBoundaryFile(output, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
        is BoundaryFileRead.Rejected -> rejectTaskProofReceipt(
            "written task proof receipt",
            TaskProofReceiptFailure.MALFORMED_DOCUMENT,
        )
    }
    return when (val admitted = admitTaskProofReceipt(raw, expectation, observedHead)) {
        is TaskProofReceiptAdmission.Complete -> {
            revalidateExactHead(repositoryRoot, AuthorityGitRevision(observedHead.value))
            admitted.receipt
        }
        is TaskProofReceiptAdmission.Rejected -> rejectTaskProofReceipt(
            "written task proof receipt",
            admitted.failure,
        )
    }
}

private fun rejectTaskProofReceipt(
    boundary: String,
    failure: TaskProofReceiptFailure,
): Nothing = throw org.gradle.api.GradleException("$boundary rejected: $failure")
