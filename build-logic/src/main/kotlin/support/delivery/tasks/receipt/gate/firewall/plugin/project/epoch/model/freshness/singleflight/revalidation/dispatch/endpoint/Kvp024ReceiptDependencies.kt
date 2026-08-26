package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

@Serializable
internal enum class Kvp024PredecessorReceiptId(
    val receiptId: String,
    val taskId: String,
    val gateId: String,
) {
    @SerialName("KVP-013-COMPLETE")
    KVP_013_COMPLETE("KVP-013-COMPLETE", "KVP-013", "KVP-013-COMPLETE-GATE"),

    @SerialName("KVP-023-COMPLETE")
    KVP_023_COMPLETE("KVP-023-COMPLETE", "KVP-023", "KVP-023-COMPLETE-GATE"),
}

@Serializable
internal data class Kvp024PredecessorDocument(
    val receiptId: Kvp024PredecessorReceiptId,
    val sha256: String,
)

internal class Kvp024PredecessorReceipt private constructor(
    val id: Kvp024PredecessorReceiptId,
    val digest: String,
    val baseRevision: AuthorityGitRevision,
    val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
) {
    internal fun document() = Kvp024PredecessorDocument(id, digest)

    companion object {
        /**
         * Proof transition: `(Kvp024PredecessorReceiptId, String) ->
         * Kvp024PredecessorRefinement`.
         *
         * Establishes one exact self-digested predecessor completion identity and preserves its
         * authority fields for the live-head refinement. Raw receipt JSON remains at Gradle
         * report and receipt boundaries.
         */
        fun decode(
            expected: Kvp024PredecessorReceiptId,
            raw: String,
        ): Kvp024PredecessorRefinement = when (val decoded = decodeProofReceiptDocument(raw)) {
            is ProofReceiptDocumentResult.Rejected -> predecessorRejected()
            is ProofReceiptDocumentResult.Complete -> {
                val document = decoded.document
                if (document.receiptId.value != expected.receiptId ||
                    document.taskId.value != expected.taskId ||
                    document.gateId.value != expected.gateId ||
                    document.receiptDigest != document.derivedDigest()
                ) predecessorRejected()
                else Kvp024PredecessorRefinement.Admitted(
                    Kvp024PredecessorReceipt(
                        expected,
                        document.receiptDigest.value,
                        document.baseRevision,
                        document.exactHead,
                        document.programFingerprint,
                        document.requirementFingerprint,
                    ),
                )
            }
        }

        fun fromAdmitted(
            expected: Kvp024PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
            boundary: Kvp001ReceiptContext,
        ): Kvp024PredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp024PredecessorRefinement.Admitted(
            Kvp024PredecessorReceipt(
                expected,
                receipt.digest.value,
                AuthorityGitRevision(boundary.baseRevision),
                receipt.exactHead,
                ProgramFingerprint(boundary.programFingerprint),
                RequirementFingerprint(boundary.requirementFingerprint),
            ),
        ) else predecessorRejected()
    }
}

internal sealed interface Kvp024PredecessorRefinement {
    data class Admitted(val receipt: Kvp024PredecessorReceipt) : Kvp024PredecessorRefinement
    data class Rejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024PredecessorRefinement
}

internal class Kvp024ReportPredecessors private constructor(
    private val ordered: List<Kvp024PredecessorReceipt>,
) {
    internal fun documents() = ordered.map(Kvp024PredecessorReceipt::document)
    internal fun digestMap() = ordered.associate { it.id.receiptId to it.digest }

    companion object {
        fun refine(receipts: List<Kvp024PredecessorReceipt>): Kvp024PredecessorSetRefinement =
            if (receipts.map(Kvp024PredecessorReceipt::id) ==
                Kvp024PredecessorReceiptId.entries
            ) Kvp024PredecessorSetRefinement.Admitted(Kvp024ReportPredecessors(receipts.toList()))
            else Kvp024PredecessorSetRefinement.Rejected(
                Kvp024EndpointPublicationReportFailure.PREDECESSOR_SET_MISMATCH,
            )
    }
}

internal sealed interface Kvp024PredecessorSetRefinement {
    data class Admitted(val predecessors: Kvp024ReportPredecessors) :
        Kvp024PredecessorSetRefinement
    data class Rejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024PredecessorSetRefinement
}

internal sealed interface Kvp024PredecessorObservationFailure {
    data class ReceiptReadRejected(val id: Kvp024PredecessorReceiptId, val path: Path) :
        Kvp024PredecessorObservationFailure
    data class RefinementRejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024PredecessorObservationFailure
}

internal sealed interface Kvp024PredecessorObservation {
    data class Observed(val predecessors: Kvp024ReportPredecessors) :
        Kvp024PredecessorObservation
    data class Rejected(val failure: Kvp024PredecessorObservationFailure) :
        Kvp024PredecessorObservation
}

/** Establishes readable exact KVP-013/KVP-023 receipts in canonical order. */
internal fun observeKvp024ReportPredecessors(
    kvp013: Path,
    kvp023: Path,
): Kvp024PredecessorObservation {
    val paths = listOf(kvp013, kvp023)
    val receipts = mutableListOf<Kvp024PredecessorReceipt>()
    Kvp024PredecessorReceiptId.entries.zip(paths).forEach { (id, path) ->
        val raw = try {
            Files.readString(path)
        } catch (_: IOException) {
            return predecessorReadRejected(id, path)
        } catch (_: SecurityException) {
            return predecessorReadRejected(id, path)
        }
        when (val result = Kvp024PredecessorReceipt.decode(id, raw)) {
            is Kvp024PredecessorRefinement.Admitted -> receipts += result.receipt
            is Kvp024PredecessorRefinement.Rejected -> return Kvp024PredecessorObservation.Rejected(
                Kvp024PredecessorObservationFailure.RefinementRejected(result.failure),
            )
        }
    }
    return when (val result = Kvp024ReportPredecessors.refine(receipts)) {
        is Kvp024PredecessorSetRefinement.Admitted ->
            Kvp024PredecessorObservation.Observed(result.predecessors)
        is Kvp024PredecessorSetRefinement.Rejected -> Kvp024PredecessorObservation.Rejected(
            Kvp024PredecessorObservationFailure.RefinementRejected(result.failure),
        )
    }
}

internal enum class Kvp024DependencyMember { ENDPOINT_DESCRIPTOR, READ_RUNTIME }
internal enum class Kvp024AuthorityField {
    BASE_REVISION,
    EXACT_HEAD,
    PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT,
}

internal sealed interface Kvp024DependencyFailure {
    data class AuthorityMismatch(
        val member: Kvp024DependencyMember,
        val field: Kvp024AuthorityField,
    ) : Kvp024DependencyFailure
    data class PredecessorRejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024DependencyFailure
}

internal sealed interface Kvp024DependencyRefinement {
    data class Admitted(val context: Kvp024DependencyContexts) : Kvp024DependencyRefinement
    data class Rejected(val failure: Kvp024DependencyFailure) : Kvp024DependencyRefinement
}

internal class Kvp024DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val predecessors: Kvp024ReportPredecessors,
) {
    internal fun digestMap() = predecessors.digestMap()

    companion object {
        /** Refines exact KVP-013/KVP-023 completions into one live authority snapshot. */
        fun refine(
            head: AuthorityGitRevision,
            boundary: Kvp001ReceiptContext,
            endpoint: Kvp024PredecessorReceipt,
            runtime: Kvp024PredecessorReceipt,
        ): Kvp024DependencyRefinement {
            listOf(
                Kvp024DependencyMember.ENDPOINT_DESCRIPTOR to endpoint,
                Kvp024DependencyMember.READ_RUNTIME to runtime,
            ).forEach { (member, receipt) ->
                compareAuthority(head, boundary, receipt)?.let { field ->
                    return dependencyRejected(Kvp024DependencyFailure.AuthorityMismatch(
                        member,
                        field,
                    ))
                }
            }
            return when (val result = Kvp024ReportPredecessors.refine(
                listOf(endpoint, runtime),
            )) {
                is Kvp024PredecessorSetRefinement.Admitted -> Kvp024DependencyRefinement.Admitted(
                    Kvp024DependencyContexts(boundary, result.predecessors),
                )
                is Kvp024PredecessorSetRefinement.Rejected -> dependencyRejected(
                    Kvp024DependencyFailure.PredecessorRejected(result.failure),
                )
            }
        }

        private fun compareAuthority(
            head: AuthorityGitRevision,
            boundary: Kvp001ReceiptContext,
            receipt: Kvp024PredecessorReceipt,
        ): Kvp024AuthorityField? = when {
            receipt.baseRevision.value != boundary.baseRevision -> Kvp024AuthorityField.BASE_REVISION
            receipt.exactHead != head || boundary.exactHead != head.value ->
                Kvp024AuthorityField.EXACT_HEAD
            receipt.programFingerprint.value != boundary.programFingerprint ->
                Kvp024AuthorityField.PROGRAM_FINGERPRINT
            receipt.requirementFingerprint.value != boundary.requirementFingerprint ->
                Kvp024AuthorityField.REQUIREMENT_FINGERPRINT
            else -> null
        }
    }
}

abstract class Kvp024DependencyReceiptTaskBase : Kvp023ReceiptTaskBase() {
    @get:InputFile abstract val directEndpointCompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val directDispatchRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directDispatchGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directDispatchProofReportFile: RegularFileProperty
    @get:InputFile abstract val directDispatchRedGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val directDispatchGreenGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val directDispatchCompletionReceiptFile: RegularFileProperty

    /** Re-admits KVP-023 and binds both direct completions at one exact head. */
    internal fun endpointDependencyContexts(head: AuthorityGitRevision): Kvp024DependencyContexts {
        val dispatch = dispatchContexts(head)
        val dispatchRed = dispatch.boundary.admit(
            directDispatchRedReceiptFile.path(),
            dispatch.redExpectation(dispatch.redGateProof()),
        )
        val dispatchGreen = dispatch.boundary.admit(
            directDispatchGreenReceiptFile.path(),
            dispatch.greenExpectation(dispatchRed, dispatch.greenGateProof()),
        )
        val dispatchCompletion = dispatch.boundary.admit(
            directDispatchCompletionReceiptFile.path(),
            dispatch.completionExpectation(dispatchRed, dispatchGreen),
        )
        val endpoint = readPredecessor(
            Kvp024PredecessorReceiptId.KVP_013_COMPLETE,
            directEndpointCompletionReceiptFile.path(),
        )
        val runtime = when (val result = Kvp024PredecessorReceipt.fromAdmitted(
            Kvp024PredecessorReceiptId.KVP_023_COMPLETE,
            dispatchCompletion,
            dispatch.boundary,
        )) {
            is Kvp024PredecessorRefinement.Admitted -> result.receipt
            is Kvp024PredecessorRefinement.Rejected -> rejectReceipt(
                "KVP-024 KVP-023 predecessor",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.name,
            )
        }
        return when (val result = Kvp024DependencyContexts.refine(
            head,
            dispatch.boundary,
            endpoint,
            runtime,
        )) {
            is Kvp024DependencyRefinement.Admitted -> result.context
            is Kvp024DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-024 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.toString(),
            )
        }
    }

    private fun readPredecessor(id: Kvp024PredecessorReceiptId, path: Path) = try {
        when (val result = Kvp024PredecessorReceipt.decode(id, Files.readString(path))) {
            is Kvp024PredecessorRefinement.Admitted -> result.receipt
            is Kvp024PredecessorRefinement.Rejected -> rejectReceipt(
                "KVP-024 ${id.receiptId} predecessor",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.name,
            )
        }
    } catch (_: IOException) {
        rejectReceipt(
            "KVP-024 ${id.receiptId} predecessor",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            path.toString(),
        )
    } catch (_: SecurityException) {
        rejectReceipt(
            "KVP-024 ${id.receiptId} predecessor",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            path.toString(),
        )
    }
}

internal sealed interface Kvp024ReportFileObservation {
    data class Observed(val report: AdmittedKvp024EndpointPublicationReport) :
        Kvp024ReportFileObservation
    data class Rejected(val failure: Kvp024ReportFileFailure) : Kvp024ReportFileObservation
}

internal sealed interface Kvp024ReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp024ReportFileFailure
    data class AdmissionRejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024ReportFileFailure
}

internal fun observeKvp024EndpointPublicationReport(
    path: Path,
    predecessors: Kvp024ReportPredecessors,
): Kvp024ReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp024ReportFileObservation.Rejected(Kvp024ReportFileFailure.ReadRejected(path))
    } catch (_: SecurityException) {
        return Kvp024ReportFileObservation.Rejected(Kvp024ReportFileFailure.ReadRejected(path))
    }
    return when (val admission = AdmittedKvp024EndpointPublicationReport.admit(raw, predecessors)) {
        is Kvp024EndpointPublicationReportAdmission.Admitted ->
            Kvp024ReportFileObservation.Observed(admission.report)
        is Kvp024EndpointPublicationReportAdmission.Rejected -> Kvp024ReportFileObservation.Rejected(
            Kvp024ReportFileFailure.AdmissionRejected(admission.failure),
        )
    }
}

private fun RegularFileProperty.path() = get().asFile.toPath()
private fun predecessorRejected() = Kvp024PredecessorRefinement.Rejected(
    Kvp024EndpointPublicationReportFailure.PREDECESSOR_RECEIPT_REJECTED,
)
private fun predecessorReadRejected(id: Kvp024PredecessorReceiptId, path: Path) =
    Kvp024PredecessorObservation.Rejected(
        Kvp024PredecessorObservationFailure.ReceiptReadRejected(id, path),
    )
private fun dependencyRejected(failure: Kvp024DependencyFailure) =
    Kvp024DependencyRefinement.Rejected(failure)
