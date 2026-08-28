package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

internal enum class Kvp021DependencyMember { FRESHNESS, SINGLE_FLIGHT }

internal enum class Kvp021AuthorityField {
    REPOSITORY_ROOT,
    BASE_REVISION,
    EXACT_HEAD,
    PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT,
    SOURCE_DIGESTS,
}

internal sealed interface Kvp021DependencyFailure {
    data class HeadMismatch(val member: Kvp021DependencyMember) : Kvp021DependencyFailure
    data class AuthorityMismatch(val field: Kvp021AuthorityField) : Kvp021DependencyFailure
    data class ReceiptIdentityMismatch(val id: Kvp021DirectPredecessorReceiptId) :
        Kvp021DependencyFailure
}

internal sealed interface Kvp021DependencyRefinement {
    data class Admitted(val context: Kvp021DependencyContexts) : Kvp021DependencyRefinement
    data class Rejected(val failure: Kvp021DependencyFailure) : Kvp021DependencyRefinement
}

private sealed interface Kvp021AuthorityComparison {
    data object Same : Kvp021AuthorityComparison
    data class Mismatch(val failure: Kvp021DependencyFailure.AuthorityMismatch) :
        Kvp021AuthorityComparison
}

internal class Kvp021DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val reportPredecessors: Kvp021ReportPredecessors,
) {
    internal fun digestMap() = reportPredecessors.digestMap()

    companion object {
        /**
         * Proof transition: exact-head KVP-019/KVP-020 contexts and completions ->
         * `Kvp021DependencyRefinement`.
         *
         * Establishes one authority snapshot and both exact direct completion identities in
         * canonical order. Expected head, authority, or identity mismatches remain closed
         * [Kvp021DependencyFailure] data.
         */
        fun refine(
            expectedHead: AuthorityGitRevision,
            freshnessBoundary: Kvp001ReceiptContext,
            freshnessCompletion: AdmittedProofReceipt,
            singleFlightBoundary: Kvp001ReceiptContext,
            singleFlightCompletion: AdmittedProofReceipt,
        ): Kvp021DependencyRefinement {
            if (freshnessBoundary.exactHead != expectedHead.value ||
                freshnessCompletion.exactHead != expectedHead
            ) return dependencyRejected(
                Kvp021DependencyFailure.HeadMismatch(Kvp021DependencyMember.FRESHNESS),
            )
            if (singleFlightBoundary.exactHead != expectedHead.value ||
                singleFlightCompletion.exactHead != expectedHead
            ) return dependencyRejected(
                Kvp021DependencyFailure.HeadMismatch(Kvp021DependencyMember.SINGLE_FLIGHT),
            )
            val freshnessSnapshot = freshnessBoundary.kvp021Snapshot()
            val singleFlightSnapshot = singleFlightBoundary.kvp021Snapshot()
            when (val comparison = compareAuthority(freshnessSnapshot, singleFlightSnapshot)) {
                Kvp021AuthorityComparison.Same -> Unit
                is Kvp021AuthorityComparison.Mismatch -> return dependencyRejected(
                    comparison.failure,
                )
            }
            val freshnessReceipt = when (val result =
                Kvp021ReportPredecessorReceipt.fromAdmitted(
                    Kvp021DirectPredecessorReceiptId.KVP_019_COMPLETE,
                    freshnessCompletion,
                )
            ) {
                is Kvp021ReportPredecessorRefinement.Admitted -> result.receipt
                is Kvp021ReportPredecessorRefinement.Rejected -> return dependencyRejected(
                    Kvp021DependencyFailure.ReceiptIdentityMismatch(
                        Kvp021DirectPredecessorReceiptId.KVP_019_COMPLETE,
                    ),
                )
            }
            val singleFlightReceipt = when (val result =
                Kvp021ReportPredecessorReceipt.fromAdmitted(
                    Kvp021DirectPredecessorReceiptId.KVP_020_COMPLETE,
                    singleFlightCompletion,
                )
            ) {
                is Kvp021ReportPredecessorRefinement.Admitted -> result.receipt
                is Kvp021ReportPredecessorRefinement.Rejected -> return dependencyRejected(
                    Kvp021DependencyFailure.ReceiptIdentityMismatch(
                        Kvp021DirectPredecessorReceiptId.KVP_020_COMPLETE,
                    ),
                )
            }
            return when (val result = Kvp021ReportPredecessors.refine(
                listOf(freshnessReceipt, singleFlightReceipt),
            )) {
                is Kvp021ReportPredecessorSetRefinement.Admitted ->
                    Kvp021DependencyRefinement.Admitted(
                        Kvp021DependencyContexts(freshnessSnapshot, result.predecessors),
                    )
                is Kvp021ReportPredecessorSetRefinement.Rejected -> dependencyRejected(
                    Kvp021DependencyFailure.ReceiptIdentityMismatch(
                        Kvp021DirectPredecessorReceiptId.KVP_019_COMPLETE,
                    ),
                )
            }
        }

        private fun compareAuthority(
            left: Kvp001ReceiptContext,
            right: Kvp001ReceiptContext,
        ): Kvp021AuthorityComparison = when {
            left.repositoryRoot != right.repositoryRoot -> mismatch(
                Kvp021AuthorityField.REPOSITORY_ROOT,
            )
            left.baseRevision != right.baseRevision -> mismatch(Kvp021AuthorityField.BASE_REVISION)
            left.exactHead != right.exactHead -> mismatch(Kvp021AuthorityField.EXACT_HEAD)
            left.programFingerprint != right.programFingerprint -> mismatch(
                Kvp021AuthorityField.PROGRAM_FINGERPRINT,
            )
            left.requirementFingerprint != right.requirementFingerprint -> mismatch(
                Kvp021AuthorityField.REQUIREMENT_FINGERPRINT,
            )
            left.sourceDigests != right.sourceDigests -> mismatch(
                Kvp021AuthorityField.SOURCE_DIGESTS,
            )
            else -> Kvp021AuthorityComparison.Same
        }

        private fun mismatch(field: Kvp021AuthorityField) =
            Kvp021AuthorityComparison.Mismatch(
                Kvp021DependencyFailure.AuthorityMismatch(field),
            )
    }
}

abstract class Kvp021DependencyReceiptTaskBase : Kvp020ReceiptTaskBase() {
    @get:InputFile abstract val directSingleFlightRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directSingleFlightGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directSingleFlightProofReportFile: RegularFileProperty
    @get:InputFile abstract val directSingleFlightCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: configured KVP-019/KVP-020 closure inputs plus `AuthorityGitRevision` ->
     * `Kvp021DependencyContexts`.
     *
     * Re-admits both complete direct predecessor closures independently at one exact head. Raw
     * receipt extraction remains at this Gradle boundary; expected mismatch is finite typed data.
     */
    internal fun cancellableDependencyContexts(
        head: AuthorityGitRevision,
    ): Kvp021DependencyContexts {
        val freshness = freshnessContexts(head)
        val freshnessRed = freshness.boundary.admit(
            directFreshnessRedReceiptFile.get().asFile.toPath(),
            freshness.redExpectation(),
        )
        val freshnessGreen = freshness.boundary.admit(
            directFreshnessGreenReceiptFile.get().asFile.toPath(),
            freshness.greenExpectation(freshnessRed),
        )
        val freshnessCompletion = freshness.boundary.admit(
            directFreshnessCompletionReceiptFile.get().asFile.toPath(),
            freshness.completionExpectation(freshnessRed, freshnessGreen),
        )
        val singleFlight = singleFlightContexts(head)
        val singleFlightRed = singleFlight.boundary.admit(
            directSingleFlightRedReceiptFile.get().asFile.toPath(),
            singleFlight.redExpectation(),
        )
        val singleFlightGreen = singleFlight.boundary.admit(
            directSingleFlightGreenReceiptFile.get().asFile.toPath(),
            singleFlight.greenExpectation(singleFlightRed),
        )
        val singleFlightCompletion = singleFlight.boundary.admit(
            directSingleFlightCompletionReceiptFile.get().asFile.toPath(),
            singleFlight.completionExpectation(singleFlightRed, singleFlightGreen),
        )
        return when (val result = Kvp021DependencyContexts.refine(
            head,
            freshness.boundary,
            freshnessCompletion,
            singleFlight.boundary,
            singleFlightCompletion,
        )) {
            is Kvp021DependencyRefinement.Admitted -> result.context
            is Kvp021DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-021 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.toString(),
            )
        }
    }
}

internal sealed interface Kvp021ReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp021ReportFileFailure
    data class AdmissionRejected(val failure: Kvp021CancellableReadReportFailure) :
        Kvp021ReportFileFailure
}

internal sealed interface Kvp021ReportFileObservation {
    data class Observed(val report: AdmittedKvp021CancellableReadReport) :
        Kvp021ReportFileObservation

    data class Rejected(val failure: Kvp021ReportFileFailure) : Kvp021ReportFileObservation
}

/**
 * Proof transition: `(Path, Kvp021ReportPredecessors) -> Kvp021ReportFileObservation`.
 *
 * Establishes readable canonical KVP-021 report bytes. Read and admission failures remain closed
 * [Kvp021ReportFileFailure] data; raw report bytes are extracted only here.
 */
internal fun observeKvp021CancellableReadReport(
    path: Path,
    predecessors: Kvp021ReportPredecessors,
): Kvp021ReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp021ReportFileObservation.Rejected(Kvp021ReportFileFailure.ReadRejected(path))
    } catch (_: SecurityException) {
        return Kvp021ReportFileObservation.Rejected(Kvp021ReportFileFailure.ReadRejected(path))
    }
    return when (val admission = AdmittedKvp021CancellableReadReport.admit(raw, predecessors)) {
        is Kvp021CancellableReadReportAdmission.Admitted ->
            Kvp021ReportFileObservation.Observed(admission.report)
        is Kvp021CancellableReadReportAdmission.Rejected -> Kvp021ReportFileObservation.Rejected(
            Kvp021ReportFileFailure.AdmissionRejected(admission.failure),
        )
    }
}

private fun Kvp001ReceiptContext.kvp021Snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

private fun dependencyRejected(failure: Kvp021DependencyFailure) =
    Kvp021DependencyRefinement.Rejected(failure)
