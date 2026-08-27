package support.delivery

import java.nio.file.Path

internal sealed interface LegacyReceiptPrefixFileAdmission {
    data class Complete(val prefix: AdmittedLegacyReceiptPrefix) :
        LegacyReceiptPrefixFileAdmission
    data class Rejected(val failure: LegacyReceiptPrefixFailure) :
        LegacyReceiptPrefixFileAdmission
}

/**
 * Proof transition: raw legacy KVP-024 receipt bytes -> `LegacyReceiptPrefixFileAdmission`.
 *
 * Generated v1 decoding establishes the closed document shape; pinned prefix admission preserves
 * exactly the already-admitted frontier digest and historical head. Expected malformed or
 * mismatched evidence remains finite [LegacyReceiptPrefixFailure]. Raw bytes exist only here.
 */
internal fun admitLegacyKvp024Prefix(raw: String): LegacyReceiptPrefixFileAdmission {
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return LegacyReceiptPrefixFileAdmission.Rejected(
            LegacyReceiptPrefixFailure.MALFORMED_RECEIPT,
        )
    }
    return when (val admitted = admitLegacyKvp024Prefix(document)) {
        is LegacyReceiptPrefixAdmission.Complete ->
            LegacyReceiptPrefixFileAdmission.Complete(admitted.prefix)
        is LegacyReceiptPrefixAdmission.Rejected ->
            LegacyReceiptPrefixFileAdmission.Rejected(admitted.failure)
    }
}

/**
 * Proof transition: legacy receipt `Path` -> `LegacyReceiptPrefixFileAdmission`.
 *
 * Establishes a bounded regular-file read followed by exact v1 prefix admission. Expected read or
 * document failure remains finite rejection; raw path access is permitted only at this Gradle
 * boundary.
 */
internal fun admitLegacyKvp024Prefix(path: Path): LegacyReceiptPrefixFileAdmission =
    when (val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is BoundaryFileRead.Complete -> admitLegacyKvp024Prefix(
            read.bytes.toString(Charsets.UTF_8),
        )
        is BoundaryFileRead.Rejected -> LegacyReceiptPrefixFileAdmission.Rejected(
            LegacyReceiptPrefixFailure.MALFORMED_RECEIPT,
        )
    }
