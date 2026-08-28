package support.architecture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import support.delivery.AdmittedProofReceipt
import support.delivery.ProofReceiptDocumentResult
import support.delivery.ProofReceiptFailure
import support.delivery.decodeProofReceiptDocument
import support.delivery.derivedDigest

@Serializable
internal enum class Kvp018PredecessorReceiptId {
    @SerialName("KVP-016-COMPLETE")
    KVP_016_COMPLETE,

    @SerialName("KVP-017-COMPLETE")
    KVP_017_COMPLETE,
}

internal class Kvp018PredecessorReceiptArtifact private constructor(
    val id: Kvp018PredecessorReceiptId,
    val sha256: String,
) {
    internal companion object {
        /**
         * Proof transition: `(Kvp018PredecessorReceiptId, String) ->
         * Kvp018PredecessorArtifactRefinement`.
         *
         * Establishes a generated-schema receipt whose semantic ID matches the required
         * predecessor and whose digest is self-derived from its complete canonical document.
         * Malformed, mismatched, or forged receipts remain closed
         * [Kvp018PredecessorReceiptFailure] data. Raw JSON enters only at the Gradle receipt-read
         * boundary.
         */
        fun decode(
            expectedId: Kvp018PredecessorReceiptId,
            rawDocument: String,
        ): Kvp018PredecessorArtifactRefinement = when (
            val decoded = decodeProofReceiptDocument(rawDocument)
        ) {
            is ProofReceiptDocumentResult.Rejected ->
                Kvp018PredecessorArtifactRefinement.Rejected(
                    Kvp018PredecessorReceiptFailure.MalformedReceipt(
                        expectedId,
                        decoded.failure,
                    ),
                )
            is ProofReceiptDocumentResult.Complete -> when {
                decoded.document.receiptId.value != expectedId.receiptIdValue ->
                    Kvp018PredecessorArtifactRefinement.Rejected(
                        Kvp018PredecessorReceiptFailure.ReceiptIdentityMismatch(
                            expectedId,
                            decoded.document.receiptId.value,
                        ),
                    )
                decoded.document.taskId.value != expectedId.taskIdValue ->
                    Kvp018PredecessorArtifactRefinement.Rejected(
                        Kvp018PredecessorReceiptFailure.ReceiptTaskMismatch(
                            expectedId,
                            decoded.document.taskId.value,
                        ),
                    )
                decoded.document.gateId.value != expectedId.gateIdValue ->
                    Kvp018PredecessorArtifactRefinement.Rejected(
                        Kvp018PredecessorReceiptFailure.ReceiptGateMismatch(
                            expectedId,
                            decoded.document.gateId.value,
                        ),
                    )
                decoded.document.receiptDigest != decoded.document.derivedDigest() ->
                    Kvp018PredecessorArtifactRefinement.Rejected(
                        Kvp018PredecessorReceiptFailure.ReceiptDigestMismatch(expectedId),
                    )
                else -> Kvp018PredecessorArtifactRefinement.Admitted(
                    Kvp018PredecessorReceiptArtifact(
                        expectedId,
                        decoded.document.receiptDigest.value,
                    ),
                )
            }
        }

        /**
         * Proof transition: `(Kvp018PredecessorReceiptId, AdmittedProofReceipt) ->
         * Kvp018PredecessorArtifactRefinement`.
         *
         * Preserves an already admitted receipt only when its completion receipt, task, and gate
         * identities all match the required predecessor. Identity mismatches remain closed
         * [Kvp018PredecessorReceiptFailure] data. Receipt primitives are extracted only here at
         * the delivery-to-architecture proof boundary.
         */
        fun fromAdmitted(
            expectedId: Kvp018PredecessorReceiptId,
            admitted: AdmittedProofReceipt,
        ): Kvp018PredecessorArtifactRefinement = when {
            admitted.receiptId.value != expectedId.receiptIdValue ->
                Kvp018PredecessorArtifactRefinement.Rejected(
                    Kvp018PredecessorReceiptFailure.ReceiptIdentityMismatch(
                        expectedId,
                        admitted.receiptId.value,
                    ),
                )
            admitted.taskId.value != expectedId.taskIdValue ->
                Kvp018PredecessorArtifactRefinement.Rejected(
                    Kvp018PredecessorReceiptFailure.ReceiptTaskMismatch(
                        expectedId,
                        admitted.taskId.value,
                    ),
                )
            admitted.gateId.value != expectedId.gateIdValue ->
                Kvp018PredecessorArtifactRefinement.Rejected(
                    Kvp018PredecessorReceiptFailure.ReceiptGateMismatch(
                        expectedId,
                        admitted.gateId.value,
                    ),
                )
            else -> Kvp018PredecessorArtifactRefinement.Admitted(
                Kvp018PredecessorReceiptArtifact(expectedId, admitted.digest.value),
            )
        }
    }
}

internal val Kvp018PredecessorReceiptId.receiptIdValue: String
    get() = when (this) {
        Kvp018PredecessorReceiptId.KVP_016_COMPLETE -> "KVP-016-COMPLETE"
        Kvp018PredecessorReceiptId.KVP_017_COMPLETE -> "KVP-017-COMPLETE"
    }
internal val Kvp018PredecessorReceiptId.taskIdValue: String
    get() = receiptIdValue.removeSuffix("-COMPLETE")
internal val Kvp018PredecessorReceiptId.gateIdValue: String
    get() = "$taskIdValue-COMPLETE-GATE"

internal sealed interface Kvp018PredecessorReceiptFailure {
    data class MemberSetMismatch(val observed: List<Kvp018PredecessorReceiptId>) :
        Kvp018PredecessorReceiptFailure
    data class MalformedReceipt(
        val id: Kvp018PredecessorReceiptId,
        val failure: ProofReceiptFailure,
    ) : Kvp018PredecessorReceiptFailure
    data class ReceiptIdentityMismatch(
        val expected: Kvp018PredecessorReceiptId,
        val observed: String,
    ) : Kvp018PredecessorReceiptFailure
    data class ReceiptTaskMismatch(
        val expected: Kvp018PredecessorReceiptId,
        val observed: String,
    ) : Kvp018PredecessorReceiptFailure
    data class ReceiptGateMismatch(
        val expected: Kvp018PredecessorReceiptId,
        val observed: String,
    ) : Kvp018PredecessorReceiptFailure
    data class ReceiptDigestMismatch(val id: Kvp018PredecessorReceiptId) :
        Kvp018PredecessorReceiptFailure
}

internal sealed interface Kvp018PredecessorArtifactRefinement {
    data class Admitted(val artifact: Kvp018PredecessorReceiptArtifact) :
        Kvp018PredecessorArtifactRefinement
    data class Rejected(val failure: Kvp018PredecessorReceiptFailure) :
        Kvp018PredecessorArtifactRefinement
}

internal class Kvp018PredecessorReceipts private constructor(
    private val kvp016: Kvp018PredecessorReceiptArtifact,
    private val kvp017: Kvp018PredecessorReceiptArtifact,
) {
    internal fun artifacts(): List<Kvp018PredecessorReceiptArtifact> = listOf(kvp016, kvp017)

    internal companion object {
        /**
         * Proof transition: `List<Kvp018PredecessorReceiptArtifact> ->
         * Kvp018PredecessorReceiptRefinement`.
         *
         * Preserves exactly one semantically admitted KVP-016 and KVP-017 completion receipt in
         * canonical order. Missing, duplicate, or extra members remain closed
         * [Kvp018PredecessorReceiptFailure] data. Raw receipt JSON is not accepted here.
         */
        fun refine(
            admitted: List<Kvp018PredecessorReceiptArtifact>,
        ): Kvp018PredecessorReceiptRefinement {
            val expected = Kvp018PredecessorReceiptId.entries.toList()
            val observed = admitted.map(Kvp018PredecessorReceiptArtifact::id)
            if (observed.sortedBy(Kvp018PredecessorReceiptId::ordinal) != expected ||
                observed.toSet().size != expected.size
            ) {
                return Kvp018PredecessorReceiptRefinement.Rejected(
                    Kvp018PredecessorReceiptFailure.MemberSetMismatch(observed),
                )
            }
            val ordered = admitted.sortedBy { it.id.ordinal }
            return Kvp018PredecessorReceiptRefinement.Admitted(
                Kvp018PredecessorReceipts(ordered[0], ordered[1]),
            )
        }
    }
}

internal sealed interface Kvp018PredecessorReceiptRefinement {
    data class Admitted(val receipts: Kvp018PredecessorReceipts) :
        Kvp018PredecessorReceiptRefinement
    data class Rejected(val failure: Kvp018PredecessorReceiptFailure) :
        Kvp018PredecessorReceiptRefinement
}
