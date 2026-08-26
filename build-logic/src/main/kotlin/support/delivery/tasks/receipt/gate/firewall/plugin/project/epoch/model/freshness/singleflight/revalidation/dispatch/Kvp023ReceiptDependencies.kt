package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

@Serializable
internal enum class Kvp023PredecessorReceiptId(
    val receiptId: String,
    val taskId: String,
    val gateId: String,
) {
    @SerialName("KVP-009-COMPLETE")
    KVP_009_COMPLETE("KVP-009-COMPLETE", "KVP-009", "KVP-009-COMPLETE-GATE"),

    @SerialName("KVP-016-COMPLETE")
    KVP_016_COMPLETE("KVP-016-COMPLETE", "KVP-016", "KVP-016-COMPLETE-GATE"),

    @SerialName("KVP-022-COMPLETE")
    KVP_022_COMPLETE("KVP-022-COMPLETE", "KVP-022", "KVP-022-COMPLETE-GATE"),
}

@Serializable
internal data class Kvp023PredecessorDocument(
    val receiptId: Kvp023PredecessorReceiptId,
    val sha256: String,
)

internal class Kvp023PredecessorReceipt private constructor(
    val id: Kvp023PredecessorReceiptId,
    val digest: String,
) {
    internal fun document() = Kvp023PredecessorDocument(id, digest)

    companion object {
        /**
         * Proof transition: `(Kvp023PredecessorReceiptId, String) ->
         * Kvp023PredecessorRefinement`.
         *
         * Establishes one exact self-digested predecessor completion identity.
         */
        fun decode(
            expected: Kvp023PredecessorReceiptId,
            raw: String,
        ): Kvp023PredecessorRefinement = when (val decoded = decodeProofReceiptDocument(raw)) {
            is ProofReceiptDocumentResult.Rejected -> predecessorRejected()
            is ProofReceiptDocumentResult.Complete -> {
                val document = decoded.document
                if (document.receiptId.value != expected.receiptId ||
                    document.taskId.value != expected.taskId ||
                    document.gateId.value != expected.gateId ||
                    document.receiptDigest != document.derivedDigest()
                ) predecessorRejected()
                else Kvp023PredecessorRefinement.Admitted(
                    Kvp023PredecessorReceipt(expected, document.receiptDigest.value),
                )
            }
        }

        fun fromAdmitted(
            expected: Kvp023PredecessorReceiptId,
            receipt: AdmittedProofReceipt,
        ): Kvp023PredecessorRefinement = if (
            receipt.receiptId.value == expected.receiptId &&
            receipt.taskId.value == expected.taskId &&
            receipt.gateId.value == expected.gateId
        ) Kvp023PredecessorRefinement.Admitted(
            Kvp023PredecessorReceipt(expected, receipt.digest.value),
        ) else predecessorRejected()
    }
}

internal sealed interface Kvp023PredecessorRefinement {
    data class Admitted(val receipt: Kvp023PredecessorReceipt) : Kvp023PredecessorRefinement
    data class Rejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023PredecessorRefinement
}

internal class Kvp023ReportPredecessors private constructor(
    private val ordered: List<Kvp023PredecessorReceipt>,
) {
    internal fun documents() = ordered.map(Kvp023PredecessorReceipt::document)
    internal fun digestMap() = ordered.associate { it.id.receiptId to it.digest }

    companion object {
        fun refine(receipts: List<Kvp023PredecessorReceipt>): Kvp023PredecessorSetRefinement =
            if (receipts.map(Kvp023PredecessorReceipt::id) ==
                Kvp023PredecessorReceiptId.entries
            ) Kvp023PredecessorSetRefinement.Admitted(Kvp023ReportPredecessors(receipts.toList()))
            else Kvp023PredecessorSetRefinement.Rejected(
                Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_SET_MISMATCH,
            )
    }
}

internal sealed interface Kvp023PredecessorSetRefinement {
    data class Admitted(val predecessors: Kvp023ReportPredecessors) :
        Kvp023PredecessorSetRefinement
    data class Rejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023PredecessorSetRefinement
}

internal sealed interface Kvp023PredecessorObservationFailure {
    data class ReceiptReadRejected(val id: Kvp023PredecessorReceiptId, val path: Path) :
        Kvp023PredecessorObservationFailure
    data class RefinementRejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023PredecessorObservationFailure
}

internal sealed interface Kvp023PredecessorObservation {
    data class Observed(val predecessors: Kvp023ReportPredecessors) :
        Kvp023PredecessorObservation
    data class Rejected(val failure: Kvp023PredecessorObservationFailure) :
        Kvp023PredecessorObservation
}

/** Establishes readable exact KVP-009/KVP-016/KVP-022 receipts in canonical order. */
internal fun observeKvp023ReportPredecessors(
    kvp009: Path,
    kvp016: Path,
    kvp022: Path,
): Kvp023PredecessorObservation {
    val paths = listOf(kvp009, kvp016, kvp022)
    val receipts = mutableListOf<Kvp023PredecessorReceipt>()
    Kvp023PredecessorReceiptId.entries.zip(paths).forEach { (id, path) ->
        val raw = try {
            Files.readString(path)
        } catch (_: IOException) {
            return predecessorReadRejected(id, path)
        } catch (_: SecurityException) {
            return predecessorReadRejected(id, path)
        }
        when (val result = Kvp023PredecessorReceipt.decode(id, raw)) {
            is Kvp023PredecessorRefinement.Admitted -> receipts += result.receipt
            is Kvp023PredecessorRefinement.Rejected -> return Kvp023PredecessorObservation.Rejected(
                Kvp023PredecessorObservationFailure.RefinementRejected(result.failure),
            )
        }
    }
    return when (val result = Kvp023ReportPredecessors.refine(receipts)) {
        is Kvp023PredecessorSetRefinement.Admitted ->
            Kvp023PredecessorObservation.Observed(result.predecessors)
        is Kvp023PredecessorSetRefinement.Rejected -> Kvp023PredecessorObservation.Rejected(
            Kvp023PredecessorObservationFailure.RefinementRejected(result.failure),
        )
    }
}

internal enum class Kvp023DependencyMember { FIREWALL, DETACHED_MODEL, REVALIDATION }
internal enum class Kvp023AuthorityField {
    REPOSITORY_ROOT,
    BASE_REVISION,
    EXACT_HEAD,
    PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT,
    SOURCE_DIGESTS,
}

internal sealed interface Kvp023DependencyFailure {
    data class HeadMismatch(val member: Kvp023DependencyMember) : Kvp023DependencyFailure
    data class AuthorityMismatch(val field: Kvp023AuthorityField) : Kvp023DependencyFailure
    data class PredecessorRejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023DependencyFailure
}

internal sealed interface Kvp023DependencyRefinement {
    data class Admitted(val context: Kvp023DependencyContexts) : Kvp023DependencyRefinement
    data class Rejected(val failure: Kvp023DependencyFailure) : Kvp023DependencyRefinement
}

internal class Kvp023DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val predecessors: Kvp023ReportPredecessors,
) {
    internal fun digestMap() = predecessors.digestMap()

    companion object {
        /** Refines three direct exact-head predecessor closures into one authority snapshot. */
        fun refine(
            head: AuthorityGitRevision,
            firewallBoundary: Kvp001ReceiptContext,
            firewall: AdmittedProofReceipt,
            detachedBoundary: Kvp001ReceiptContext,
            detached: Kvp023PredecessorReceipt,
            revalidationBoundary: Kvp001ReceiptContext,
            revalidation: AdmittedProofReceipt,
        ): Kvp023DependencyRefinement {
            listOf(
                Kvp023DependencyMember.FIREWALL to firewallBoundary,
                Kvp023DependencyMember.DETACHED_MODEL to detachedBoundary,
                Kvp023DependencyMember.REVALIDATION to revalidationBoundary,
            ).forEach { (member, boundary) ->
                val receiptHead = when (member) {
                    Kvp023DependencyMember.FIREWALL -> firewall.exactHead
                    Kvp023DependencyMember.DETACHED_MODEL -> head
                    Kvp023DependencyMember.REVALIDATION -> revalidation.exactHead
                }
                if (boundary.exactHead != head.value || receiptHead != head) {
                    return dependencyRejected(Kvp023DependencyFailure.HeadMismatch(member))
                }
            }
            val canonical = firewallBoundary.kvp023Snapshot()
            listOf(detachedBoundary, revalidationBoundary).forEach { other ->
                compareAuthority(canonical, other.kvp023Snapshot())?.let {
                    return dependencyRejected(Kvp023DependencyFailure.AuthorityMismatch(it))
                }
            }
            val admitted = listOf(
                Kvp023PredecessorReceiptId.KVP_009_COMPLETE to firewall,
                Kvp023PredecessorReceiptId.KVP_022_COMPLETE to revalidation,
            ).map { (id, receipt) ->
                when (val result = Kvp023PredecessorReceipt.fromAdmitted(id, receipt)) {
                    is Kvp023PredecessorRefinement.Admitted -> result.receipt
                    is Kvp023PredecessorRefinement.Rejected -> return dependencyRejected(
                        Kvp023DependencyFailure.PredecessorRejected(result.failure),
                    )
                }
            }.toMutableList()
            if (detached.id != Kvp023PredecessorReceiptId.KVP_016_COMPLETE) {
                return dependencyRejected(Kvp023DependencyFailure.PredecessorRejected(
                    Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_RECEIPT_REJECTED,
                ))
            }
            admitted.add(1, detached)
            return when (val result = Kvp023ReportPredecessors.refine(admitted)) {
                is Kvp023PredecessorSetRefinement.Admitted -> Kvp023DependencyRefinement.Admitted(
                    Kvp023DependencyContexts(canonical, result.predecessors),
                )
                is Kvp023PredecessorSetRefinement.Rejected -> dependencyRejected(
                    Kvp023DependencyFailure.PredecessorRejected(result.failure),
                )
            }
        }

        private fun compareAuthority(
            left: Kvp001ReceiptContext,
            right: Kvp001ReceiptContext,
        ): Kvp023AuthorityField? = when {
            left.repositoryRoot != right.repositoryRoot -> Kvp023AuthorityField.REPOSITORY_ROOT
            left.baseRevision != right.baseRevision -> Kvp023AuthorityField.BASE_REVISION
            left.exactHead != right.exactHead -> Kvp023AuthorityField.EXACT_HEAD
            left.programFingerprint != right.programFingerprint ->
                Kvp023AuthorityField.PROGRAM_FINGERPRINT
            left.requirementFingerprint != right.requirementFingerprint ->
                Kvp023AuthorityField.REQUIREMENT_FINGERPRINT
            left.sourceDigests != right.sourceDigests -> Kvp023AuthorityField.SOURCE_DIGESTS
            else -> null
        }
    }
}

abstract class Kvp023DependencyReceiptTaskBase : Kvp022ReceiptTaskBase() {
    @get:InputFile abstract val directRevalidationRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directRevalidationGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directRevalidationProofReportFile: RegularFileProperty
    @get:InputFile abstract val directRevalidationCompletionReceiptFile: RegularFileProperty

    /** Re-admits all three complete direct predecessor closures at one exact head. */
    internal fun dispatchDependencyContexts(head: AuthorityGitRevision): Kvp023DependencyContexts {
        val firewall = firewallContexts(head)
        val firewallProof = firewall.reportProof()
        val firewallRed = firewall.boundary.admit(
            directFirewallRedReceiptFile.path(), firewall.redExpectation(firewallProof),
        )
        val firewallGreen = firewall.boundary.admit(
            directFirewallGreenReceiptFile.path(),
            firewall.greenExpectation(firewallRed, firewallProof),
        )
        val firewallCompletion = firewall.boundary.admit(
            directFirewallCompletionReceiptFile.path(),
            firewall.completionExpectation(firewallRed, firewallGreen),
        )
        val detachedClosure = dependencyContexts(head)
        val detachedArtifact = detachedClosure.predecessors.artifacts().single {
            it.id == support.architecture.Kvp018PredecessorReceiptId.KVP_016_COMPLETE
        }
        val detachedReceipt = when (val result = Kvp023PredecessorReceipt.decode(
            Kvp023PredecessorReceiptId.KVP_016_COMPLETE,
            readDirectDetachedCompletion(),
        )) {
            is Kvp023PredecessorRefinement.Admitted -> result.receipt
            is Kvp023PredecessorRefinement.Rejected -> rejectReceipt(
                "KVP-023 KVP-016 predecessor",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.name,
            )
        }
        if (detachedReceipt.digest != detachedArtifact.sha256) rejectReceipt(
            "KVP-023 KVP-016 predecessor digest",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
        )
        val revalidation = revalidationContexts(head)
        val revalidationRed = revalidation.boundary.admit(
            directRevalidationRedReceiptFile.path(),
            revalidation.redExpectation(revalidation.redGateProof()),
        )
        val revalidationGreen = revalidation.boundary.admit(
            directRevalidationGreenReceiptFile.path(),
            revalidation.greenExpectation(revalidationRed, revalidation.greenGateProof()),
        )
        val revalidationCompletion = revalidation.boundary.admit(
            directRevalidationCompletionReceiptFile.path(),
            revalidation.completionExpectation(revalidationRed, revalidationGreen),
        )
        return when (val result = Kvp023DependencyContexts.refine(
            head,
            firewall.boundary,
            firewallCompletion,
            detachedClosure.boundary,
            detachedReceipt,
            revalidation.boundary,
            revalidationCompletion,
        )) {
            is Kvp023DependencyRefinement.Admitted -> result.context
            is Kvp023DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-023 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.toString(),
            )
        }
    }

    private fun readDirectDetachedCompletion(): String = try {
        Files.readString(directDetachedCompletionReceiptFile.path())
    } catch (_: IOException) {
        rejectReceipt(
            "KVP-023 KVP-016 predecessor",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            directDetachedCompletionReceiptFile.path().toString(),
        )
    } catch (_: SecurityException) {
        rejectReceipt(
            "KVP-023 KVP-016 predecessor",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            directDetachedCompletionReceiptFile.path().toString(),
        )
    }
}

internal sealed interface Kvp023ReportFileObservation {
    data class Observed(val report: AdmittedKvp023ReadOnlyGraphReport) :
        Kvp023ReportFileObservation
    data class Rejected(val failure: Kvp023ReportFileFailure) : Kvp023ReportFileObservation
}

internal sealed interface Kvp023ReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp023ReportFileFailure
    data class AdmissionRejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023ReportFileFailure
}

internal fun observeKvp023ReadOnlyGraphReport(
    path: Path,
    predecessors: Kvp023ReportPredecessors,
): Kvp023ReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp023ReportFileObservation.Rejected(Kvp023ReportFileFailure.ReadRejected(path))
    } catch (_: SecurityException) {
        return Kvp023ReportFileObservation.Rejected(Kvp023ReportFileFailure.ReadRejected(path))
    }
    return when (val admission = AdmittedKvp023ReadOnlyGraphReport.admit(raw, predecessors)) {
        is Kvp023ReadOnlyGraphReportAdmission.Admitted ->
            Kvp023ReportFileObservation.Observed(admission.report)
        is Kvp023ReadOnlyGraphReportAdmission.Rejected -> Kvp023ReportFileObservation.Rejected(
            Kvp023ReportFileFailure.AdmissionRejected(admission.failure),
        )
    }
}

private fun Kvp001ReceiptContext.kvp023Snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

private fun RegularFileProperty.path() = get().asFile.toPath()
private fun predecessorRejected() = Kvp023PredecessorRefinement.Rejected(
    Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_RECEIPT_REJECTED,
)
private fun predecessorReadRejected(id: Kvp023PredecessorReceiptId, path: Path) =
    Kvp023PredecessorObservation.Rejected(
        Kvp023PredecessorObservationFailure.ReceiptReadRejected(id, path),
    )
private fun dependencyRejected(failure: Kvp023DependencyFailure) =
    Kvp023DependencyRefinement.Rejected(failure)
