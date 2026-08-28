package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

@Serializable
internal enum class Kvp022PredecessorReceiptId(
    val receiptId: String,
    val taskId: String,
    val gateId: String,
) {
    @SerialName("KVP-021-COMPLETE")
    KVP_021_COMPLETE("KVP-021-COMPLETE", "KVP-021", "KVP-021-COMPLETE-GATE"),
}

@Serializable
internal data class Kvp022PredecessorDocument(
    val receiptId: Kvp022PredecessorReceiptId,
    val sha256: String,
)

internal class Kvp022ReportPredecessor private constructor(
    val id: Kvp022PredecessorReceiptId,
    val digest: String,
) {
    internal fun document() = Kvp022PredecessorDocument(id, digest)
    internal fun digestMap() = mapOf(id.receiptId to digest)

    companion object {
        /**
         * Proof transition: `(Kvp022PredecessorReceiptId, String) ->
         * Kvp022ReportPredecessorRefinement`.
         *
         * Establishes exact self-digested KVP-021 completion identity. Malformed, forged, or
         * mismatched receipt bytes remain closed KVP-022 report failure data.
         */
        fun decode(
            expected: Kvp022PredecessorReceiptId,
            raw: String,
        ): Kvp022ReportPredecessorRefinement = when (val decoded =
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
                else Kvp022ReportPredecessorRefinement.Admitted(
                    Kvp022ReportPredecessor(expected, document.receiptDigest.value),
                )
            }
        }

        /**
         * Proof transition: `(Kvp022PredecessorReceiptId, AdmittedProofReceipt) ->
         * Kvp022ReportPredecessorRefinement`.
         *
         * Preserves one exact admitted completion digest only for the expected identity.
         */
        fun fromAdmitted(
            expected: Kvp022PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): Kvp022ReportPredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp022ReportPredecessorRefinement.Admitted(
            Kvp022ReportPredecessor(expected, receipt.digest.value),
        ) else predecessorRejected()
    }
}

internal sealed interface Kvp022ReportPredecessorRefinement {
    data class Admitted(val predecessor: Kvp022ReportPredecessor) :
        Kvp022ReportPredecessorRefinement

    data class Rejected(val failure: Kvp022EpochRevalidationReportFailure) :
        Kvp022ReportPredecessorRefinement
}

internal sealed interface Kvp022ReportPredecessorObservationFailure {
    data class ReceiptReadRejected(val path: Path) : Kvp022ReportPredecessorObservationFailure
    data class RefinementRejected(val failure: Kvp022EpochRevalidationReportFailure) :
        Kvp022ReportPredecessorObservationFailure
}

internal sealed interface Kvp022ReportPredecessorObservation {
    data class Observed(val predecessor: Kvp022ReportPredecessor) :
        Kvp022ReportPredecessorObservation

    data class Rejected(val failure: Kvp022ReportPredecessorObservationFailure) :
        Kvp022ReportPredecessorObservation
}

/**
 * Proof transition: `Path -> Kvp022ReportPredecessorObservation`.
 *
 * Establishes readable self-digested KVP-021 completion identity. Raw receipt bytes are extracted
 * only at this Gradle report boundary.
 */
internal fun observeKvp022ReportPredecessor(
    path: Path,
): Kvp022ReportPredecessorObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return predecessorReadRejected(path)
    } catch (_: SecurityException) {
        return predecessorReadRejected(path)
    }
    return when (val result = Kvp022ReportPredecessor.decode(
        Kvp022PredecessorReceiptId.KVP_021_COMPLETE,
        raw,
    )) {
        is Kvp022ReportPredecessorRefinement.Admitted ->
            Kvp022ReportPredecessorObservation.Observed(result.predecessor)
        is Kvp022ReportPredecessorRefinement.Rejected ->
            Kvp022ReportPredecessorObservation.Rejected(
                Kvp022ReportPredecessorObservationFailure.RefinementRejected(result.failure),
            )
    }
}

internal enum class Kvp022DependencyFailure {
    HEAD_MISMATCH,
    PREDECESSOR_IDENTITY_MISMATCH,
}

internal sealed interface Kvp022DependencyRefinement {
    data class Admitted(val context: Kvp022DependencyContexts) : Kvp022DependencyRefinement
    data class Rejected(val failure: Kvp022DependencyFailure) : Kvp022DependencyRefinement
}

internal class Kvp022DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val reportPredecessor: Kvp022ReportPredecessor,
) {
    internal fun digestMap() = reportPredecessor.digestMap()

    companion object {
        /**
         * Proof transition: exact-head KVP-021 context and completion ->
         * `Kvp022DependencyRefinement`.
         *
         * Establishes one exact authority snapshot and sole direct completion identity.
         */
        fun refine(
            expectedHead: AuthorityGitRevision,
            boundary: Kvp001ReceiptContext,
            completion: AdmittedProofReceipt,
        ): Kvp022DependencyRefinement {
            if (boundary.exactHead != expectedHead.value || completion.exactHead != expectedHead) {
                return dependencyRejected(Kvp022DependencyFailure.HEAD_MISMATCH)
            }
            val predecessor = when (val result = Kvp022ReportPredecessor.fromAdmitted(
                Kvp022PredecessorReceiptId.KVP_021_COMPLETE,
                completion,
            )) {
                is Kvp022ReportPredecessorRefinement.Admitted -> result.predecessor
                is Kvp022ReportPredecessorRefinement.Rejected -> return dependencyRejected(
                    Kvp022DependencyFailure.PREDECESSOR_IDENTITY_MISMATCH,
                )
            }
            return Kvp022DependencyRefinement.Admitted(
                Kvp022DependencyContexts(boundary.kvp022Snapshot(), predecessor),
            )
        }
    }
}

abstract class Kvp022DependencyReceiptTaskBase : Kvp021ReceiptTaskBase() {
    @get:InputFile abstract val directCancellableRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCancellableGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directCancellableProofReportFile: RegularFileProperty
    @get:InputFile abstract val directCancellableCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: configured complete KVP-021 closure plus `AuthorityGitRevision` ->
     * `Kvp022DependencyContexts`.
     *
     * Re-admits the complete sole predecessor closure independently at one exact head.
     */
    internal fun revalidationDependencyContexts(
        head: AuthorityGitRevision,
    ): Kvp022DependencyContexts {
        val cancellable = cancellableContexts(head)
        val red = cancellable.boundary.admit(
            directCancellableRedReceiptFile.get().asFile.toPath(),
            cancellable.redExpectation(cancellable.redGateProof()),
        )
        val green = cancellable.boundary.admit(
            directCancellableGreenReceiptFile.get().asFile.toPath(),
            cancellable.greenExpectation(red, cancellable.greenGateProof()),
        )
        val completion = cancellable.boundary.admit(
            directCancellableCompletionReceiptFile.get().asFile.toPath(),
            cancellable.completionExpectation(red, green),
        )
        return when (val result = Kvp022DependencyContexts.refine(
            head,
            cancellable.boundary,
            completion,
        )) {
            is Kvp022DependencyRefinement.Admitted -> result.context
            is Kvp022DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-022 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.name,
            )
        }
    }
}

internal sealed interface Kvp022ReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp022ReportFileFailure
    data class AdmissionRejected(val failure: Kvp022EpochRevalidationReportFailure) :
        Kvp022ReportFileFailure
}

internal sealed interface Kvp022ReportFileObservation {
    data class Observed(val report: AdmittedKvp022EpochRevalidationReport) :
        Kvp022ReportFileObservation

    data class Rejected(val failure: Kvp022ReportFileFailure) : Kvp022ReportFileObservation
}

/**
 * Proof transition: `(Path, Kvp022ReportPredecessor) -> Kvp022ReportFileObservation`.
 *
 * Establishes readable canonical KVP-022 report bytes. Raw report text is extracted only here.
 */
internal fun observeKvp022EpochRevalidationReport(
    path: Path,
    predecessor: Kvp022ReportPredecessor,
): Kvp022ReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp022ReportFileObservation.Rejected(Kvp022ReportFileFailure.ReadRejected(path))
    } catch (_: SecurityException) {
        return Kvp022ReportFileObservation.Rejected(Kvp022ReportFileFailure.ReadRejected(path))
    }
    return when (val admission = AdmittedKvp022EpochRevalidationReport.admit(raw, predecessor)) {
        is Kvp022EpochRevalidationReportAdmission.Admitted ->
            Kvp022ReportFileObservation.Observed(admission.report)
        is Kvp022EpochRevalidationReportAdmission.Rejected ->
            Kvp022ReportFileObservation.Rejected(
                Kvp022ReportFileFailure.AdmissionRejected(admission.failure),
            )
    }
}

private fun Kvp001ReceiptContext.kvp022Snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

private fun predecessorReadRejected(path: Path) =
    Kvp022ReportPredecessorObservation.Rejected(
        Kvp022ReportPredecessorObservationFailure.ReceiptReadRejected(path),
    )

private fun predecessorRejected() = Kvp022ReportPredecessorRefinement.Rejected(
    Kvp022EpochRevalidationReportFailure.PREDECESSOR_RECEIPT_REJECTED,
)

private fun dependencyRejected(failure: Kvp022DependencyFailure) =
    Kvp022DependencyRefinement.Rejected(failure)
