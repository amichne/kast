package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class Kvp021DirectPredecessorReceiptId(
    val receiptId: String,
    val taskId: String,
    val gateId: String,
) {
    @SerialName("KVP-019-COMPLETE")
    KVP_019_COMPLETE("KVP-019-COMPLETE", "KVP-019", "KVP-019-COMPLETE-GATE"),

    @SerialName("KVP-020-COMPLETE")
    KVP_020_COMPLETE("KVP-020-COMPLETE", "KVP-020", "KVP-020-COMPLETE-GATE"),
}

@Serializable
internal data class Kvp021PredecessorDocument(
    val receiptId: Kvp021DirectPredecessorReceiptId,
    val sha256: String,
)

internal class Kvp021ReportPredecessorReceipt private constructor(
    val id: Kvp021DirectPredecessorReceiptId,
    val digest: String,
) {
    companion object {
        /**
         * Proof transition: `(Kvp021DirectPredecessorReceiptId, String) ->
         * Kvp021ReportPredecessorRefinement`.
         *
         * Establishes the exact completion identity and self-derived receipt digest. Malformed,
         * forged, or mismatched receipt bytes remain closed KVP-021 report failure data.
         */
        fun decode(
            expected: Kvp021DirectPredecessorReceiptId,
            raw: String,
        ): Kvp021ReportPredecessorRefinement = when (val decoded =
            decodeProofReceiptDocument(raw)
        ) {
            is ProofReceiptDocumentResult.Rejected -> predecessorRejected()
            is ProofReceiptDocumentResult.Complete -> {
                val document = decoded.document
                if (document.receiptId.value != expected.receiptId ||
                    document.taskId.value != expected.taskId ||
                    document.gateId.value != expected.gateId ||
                    document.receiptDigest != document.derivedDigest()
                ) predecessorRejected()
                else Kvp021ReportPredecessorRefinement.Admitted(
                    Kvp021ReportPredecessorReceipt(expected, document.receiptDigest.value),
                )
            }
        }

        /**
         * Proof transition: `(Kvp021DirectPredecessorReceiptId, AdmittedProofReceipt) ->
         * Kvp021ReportPredecessorRefinement`.
         *
         * Preserves one exact admitted completion digest only for the expected identity.
         */
        fun fromAdmitted(
            expected: Kvp021DirectPredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): Kvp021ReportPredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp021ReportPredecessorRefinement.Admitted(
            Kvp021ReportPredecessorReceipt(expected, receipt.digest.value),
        ) else predecessorRejected()
    }
}

internal sealed interface Kvp021ReportPredecessorRefinement {
    data class Admitted(val receipt: Kvp021ReportPredecessorReceipt) :
        Kvp021ReportPredecessorRefinement

    data class Rejected(val failure: Kvp021CancellableReadReportFailure) :
        Kvp021ReportPredecessorRefinement
}

internal class Kvp021ReportPredecessors private constructor(
    private val receipts: List<Kvp021ReportPredecessorReceipt>,
) {
    internal fun documents() = receipts.map { Kvp021PredecessorDocument(it.id, it.digest) }
    internal fun digestMap() = receipts.associate { it.id.receiptId to it.digest }

    companion object {
        /**
         * Proof transition: `List<Kvp021ReportPredecessorReceipt> ->
         * Kvp021ReportPredecessorSetRefinement`.
         *
         * Establishes exactly KVP-019 then KVP-020 completion evidence in canonical order.
         */
        fun refine(
            receipts: List<Kvp021ReportPredecessorReceipt>,
        ): Kvp021ReportPredecessorSetRefinement {
            val ordered = receipts.sortedBy { it.id.ordinal }
            return if (
                ordered.map { it.id } == Kvp021DirectPredecessorReceiptId.entries &&
                receipts.map { it.id }.toSet().size ==
                Kvp021DirectPredecessorReceiptId.entries.size
            ) Kvp021ReportPredecessorSetRefinement.Admitted(
                Kvp021ReportPredecessors(ordered),
            ) else Kvp021ReportPredecessorSetRefinement.Rejected(
                Kvp021CancellableReadReportFailure.PREDECESSOR_SET_MISMATCH,
            )
        }
    }
}

internal sealed interface Kvp021ReportPredecessorSetRefinement {
    data class Admitted(val predecessors: Kvp021ReportPredecessors) :
        Kvp021ReportPredecessorSetRefinement

    data class Rejected(val failure: Kvp021CancellableReadReportFailure) :
        Kvp021ReportPredecessorSetRefinement
}

internal sealed interface Kvp021ReportPredecessorObservationFailure {
    data class ReceiptReadRejected(
        val id: Kvp021DirectPredecessorReceiptId,
        val path: Path,
    ) : Kvp021ReportPredecessorObservationFailure

    data class RefinementRejected(val failure: Kvp021CancellableReadReportFailure) :
        Kvp021ReportPredecessorObservationFailure
}

internal sealed interface Kvp021ReportPredecessorObservation {
    data class Observed(val predecessors: Kvp021ReportPredecessors) :
        Kvp021ReportPredecessorObservation

    data class Rejected(val failure: Kvp021ReportPredecessorObservationFailure) :
        Kvp021ReportPredecessorObservation
}

/**
 * Proof transition: `(Path, Path) -> Kvp021ReportPredecessorObservation`.
 *
 * Establishes readable, self-digested KVP-019/KVP-020 completion receipts in exact order. Raw
 * receipt bytes are extracted only at this Gradle report boundary.
 */
internal fun observeKvp021ReportPredecessors(
    kvp019: Path,
    kvp020: Path,
): Kvp021ReportPredecessorObservation {
    val freshness = when (val result = readKvp021Predecessor(
        Kvp021DirectPredecessorReceiptId.KVP_019_COMPLETE,
        kvp019,
    )) {
        is Kvp021RawPredecessorObservation.Observed -> result.raw
        is Kvp021RawPredecessorObservation.Rejected ->
            return Kvp021ReportPredecessorObservation.Rejected(result.failure)
    }
    val singleFlight = when (val result = readKvp021Predecessor(
        Kvp021DirectPredecessorReceiptId.KVP_020_COMPLETE,
        kvp020,
    )) {
        is Kvp021RawPredecessorObservation.Observed -> result.raw
        is Kvp021RawPredecessorObservation.Rejected ->
            return Kvp021ReportPredecessorObservation.Rejected(result.failure)
    }
    return refineKvp021ReportPredecessors(freshness, singleFlight)
}

private fun refineKvp021ReportPredecessors(
    freshness: String,
    singleFlight: String,
): Kvp021ReportPredecessorObservation {
    val kvp019 = when (val result = Kvp021ReportPredecessorReceipt.decode(
        Kvp021DirectPredecessorReceiptId.KVP_019_COMPLETE,
        freshness,
    )) {
        is Kvp021ReportPredecessorRefinement.Admitted -> result.receipt
        is Kvp021ReportPredecessorRefinement.Rejected -> return predecessorRefinementRejected(
            result.failure,
        )
    }
    val kvp020 = when (val result = Kvp021ReportPredecessorReceipt.decode(
        Kvp021DirectPredecessorReceiptId.KVP_020_COMPLETE,
        singleFlight,
    )) {
        is Kvp021ReportPredecessorRefinement.Admitted -> result.receipt
        is Kvp021ReportPredecessorRefinement.Rejected -> return predecessorRefinementRejected(
            result.failure,
        )
    }
    return when (val result = Kvp021ReportPredecessors.refine(listOf(kvp019, kvp020))) {
        is Kvp021ReportPredecessorSetRefinement.Admitted ->
            Kvp021ReportPredecessorObservation.Observed(result.predecessors)
        is Kvp021ReportPredecessorSetRefinement.Rejected -> predecessorRefinementRejected(
            result.failure,
        )
    }
}

private sealed interface Kvp021RawPredecessorObservation {
    data class Observed(val raw: String) : Kvp021RawPredecessorObservation
    data class Rejected(
        val failure: Kvp021ReportPredecessorObservationFailure.ReceiptReadRejected,
    ) : Kvp021RawPredecessorObservation
}

private fun readKvp021Predecessor(
    id: Kvp021DirectPredecessorReceiptId,
    path: Path,
): Kvp021RawPredecessorObservation = try {
    Kvp021RawPredecessorObservation.Observed(Files.readString(path))
} catch (_: IOException) {
    predecessorReadRejected(id, path)
} catch (_: SecurityException) {
    predecessorReadRejected(id, path)
}

private fun predecessorReadRejected(
    id: Kvp021DirectPredecessorReceiptId,
    path: Path,
) = Kvp021RawPredecessorObservation.Rejected(
    Kvp021ReportPredecessorObservationFailure.ReceiptReadRejected(id, path),
)

private fun predecessorRefinementRejected(failure: Kvp021CancellableReadReportFailure) =
    Kvp021ReportPredecessorObservation.Rejected(
        Kvp021ReportPredecessorObservationFailure.RefinementRejected(failure),
    )

private fun predecessorRejected() = Kvp021ReportPredecessorRefinement.Rejected(
    Kvp021CancellableReadReportFailure.PREDECESSOR_RECEIPT_REJECTED,
)
