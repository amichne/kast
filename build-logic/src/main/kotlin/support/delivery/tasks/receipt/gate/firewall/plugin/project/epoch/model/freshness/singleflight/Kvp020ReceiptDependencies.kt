package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal enum class Kvp020DependencyMember { PROJECT_ADMISSION, FRESHNESS }

internal enum class Kvp020AuthorityField {
    REPOSITORY_ROOT,
    BASE_REVISION,
    EXACT_HEAD,
    PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT,
    SOURCE_DIGESTS,
}

internal sealed interface Kvp020DependencyFailure {
    data class HeadMismatch(val member: Kvp020DependencyMember) : Kvp020DependencyFailure
    data class AuthorityMismatch(val field: Kvp020AuthorityField) : Kvp020DependencyFailure
    data class PredecessorRejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020DependencyFailure
}

internal sealed interface Kvp020DependencyRefinement {
    data class Admitted(val context: Kvp020DependencyContexts) : Kvp020DependencyRefinement
    data class Rejected(val failure: Kvp020DependencyFailure) : Kvp020DependencyRefinement
}

private sealed interface Kvp020AuthorityComparison {
    data object Same : Kvp020AuthorityComparison
    data class Mismatch(val failure: Kvp020DependencyFailure.AuthorityMismatch) :
        Kvp020AuthorityComparison
}

internal class Kvp020DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val predecessors: Kvp020ReportPredecessors,
) {
    companion object {
        /**
         * Proof transition: `(AuthorityGitRevision, Kvp001ReceiptContext,
         * AdmittedProofReceipt, Kvp001ReceiptContext, AdmittedProofReceipt) ->
         * Kvp020DependencyRefinement`.
         *
         * Establishes one immutable authority snapshot and the exact KVP-014/KVP-019 completion
         * pair. Head, authority, and semantic identity mismatches remain closed
         * [Kvp020DependencyFailure] data.
         */
        fun refine(
            expectedHead: AuthorityGitRevision,
            projectBoundary: Kvp001ReceiptContext,
            projectCompletion: AdmittedProofReceipt,
            freshnessBoundary: Kvp001ReceiptContext,
            freshnessCompletion: AdmittedProofReceipt,
        ): Kvp020DependencyRefinement {
            if (projectBoundary.exactHead != expectedHead.value ||
                projectCompletion.exactHead != expectedHead
            ) return rejected(Kvp020DependencyFailure.HeadMismatch(
                Kvp020DependencyMember.PROJECT_ADMISSION,
            ))
            if (freshnessBoundary.exactHead != expectedHead.value ||
                freshnessCompletion.exactHead != expectedHead
            ) return rejected(Kvp020DependencyFailure.HeadMismatch(
                Kvp020DependencyMember.FRESHNESS,
            ))
            val projectSnapshot = projectBoundary.kvp020Snapshot()
            val freshnessSnapshot = freshnessBoundary.kvp020Snapshot()
            when (val comparison = compareAuthority(projectSnapshot, freshnessSnapshot)) {
                Kvp020AuthorityComparison.Same -> Unit
                is Kvp020AuthorityComparison.Mismatch -> return rejected(comparison.failure)
            }
            val project = when (val result = Kvp020PredecessorReceipt.fromAdmitted(
                Kvp020PredecessorReceiptId.KVP_014_COMPLETE,
                projectCompletion,
            )) {
                is Kvp020PredecessorRefinement.Admitted -> result.receipt
                is Kvp020PredecessorRefinement.Rejected -> return rejected(
                    Kvp020DependencyFailure.PredecessorRejected(result.failure),
                )
            }
            val freshness = when (val result = Kvp020PredecessorReceipt.fromAdmitted(
                Kvp020PredecessorReceiptId.KVP_019_COMPLETE,
                freshnessCompletion,
            )) {
                is Kvp020PredecessorRefinement.Admitted -> result.receipt
                is Kvp020PredecessorRefinement.Rejected -> return rejected(
                    Kvp020DependencyFailure.PredecessorRejected(result.failure),
                )
            }
            return when (val result = Kvp020ReportPredecessors.refine(
                listOf(project, freshness),
            )) {
                is Kvp020PredecessorSetRefinement.Admitted ->
                    Kvp020DependencyRefinement.Admitted(Kvp020DependencyContexts(
                        projectSnapshot,
                        result.predecessors,
                    ))
                is Kvp020PredecessorSetRefinement.Rejected -> rejected(
                    Kvp020DependencyFailure.PredecessorRejected(result.failure),
                )
            }
        }

        private fun compareAuthority(
            left: Kvp001ReceiptContext,
            right: Kvp001ReceiptContext,
        ): Kvp020AuthorityComparison = when {
            left.repositoryRoot != right.repositoryRoot -> mismatch(
                Kvp020AuthorityField.REPOSITORY_ROOT,
            )
            left.baseRevision != right.baseRevision -> mismatch(Kvp020AuthorityField.BASE_REVISION)
            left.exactHead != right.exactHead -> mismatch(Kvp020AuthorityField.EXACT_HEAD)
            left.programFingerprint != right.programFingerprint -> mismatch(
                Kvp020AuthorityField.PROGRAM_FINGERPRINT,
            )
            left.requirementFingerprint != right.requirementFingerprint -> mismatch(
                Kvp020AuthorityField.REQUIREMENT_FINGERPRINT,
            )
            left.sourceDigests != right.sourceDigests -> mismatch(Kvp020AuthorityField.SOURCE_DIGESTS)
            else -> Kvp020AuthorityComparison.Same
        }

        private fun mismatch(field: Kvp020AuthorityField) =
            Kvp020AuthorityComparison.Mismatch(
                Kvp020DependencyFailure.AuthorityMismatch(field),
            )

        private fun rejected(failure: Kvp020DependencyFailure) =
            Kvp020DependencyRefinement.Rejected(failure)
    }
}

private fun Kvp001ReceiptContext.kvp020Snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

internal sealed interface Kvp020PredecessorObservationFailure {
    data class ReceiptReadRejected(
        val id: Kvp020PredecessorReceiptId,
        val path: Path,
    ) : Kvp020PredecessorObservationFailure

    data class RefinementRejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020PredecessorObservationFailure
}

internal sealed interface Kvp020PredecessorObservation {
    data class Observed(val predecessors: Kvp020ReportPredecessors) :
        Kvp020PredecessorObservation
    data class Rejected(val failure: Kvp020PredecessorObservationFailure) :
        Kvp020PredecessorObservation
}

/**
 * Proof transition: `(Path, Path) -> Kvp020PredecessorObservation`.
 *
 * Establishes readable, generated-schema, self-digested KVP-014/KVP-019 completion receipts in
 * exact canonical order. Read, parse, identity, digest, or set failure remains closed
 * [Kvp020PredecessorObservationFailure] data. Raw file bytes are extracted only here at the
 * Gradle report-task boundary.
 */
internal fun observeKvp020ReportPredecessors(
    kvp014: Path,
    kvp019: Path,
): Kvp020PredecessorObservation {
    val project = when (val observed = readPredecessor(
        Kvp020PredecessorReceiptId.KVP_014_COMPLETE,
        kvp014,
    )) {
        is Kvp020RawReceiptObservation.Observed -> observed.raw
        is Kvp020RawReceiptObservation.Rejected -> return Kvp020PredecessorObservation.Rejected(
            observed.failure,
        )
    }
    val freshness = when (val observed = readPredecessor(
        Kvp020PredecessorReceiptId.KVP_019_COMPLETE,
        kvp019,
    )) {
        is Kvp020RawReceiptObservation.Observed -> observed.raw
        is Kvp020RawReceiptObservation.Rejected -> return Kvp020PredecessorObservation.Rejected(
            observed.failure,
        )
    }
    return when (val result = refineKvp020ReportPredecessors(project, freshness)) {
        is Kvp020PredecessorSetRefinement.Admitted ->
            Kvp020PredecessorObservation.Observed(result.predecessors)
        is Kvp020PredecessorSetRefinement.Rejected -> Kvp020PredecessorObservation.Rejected(
            Kvp020PredecessorObservationFailure.RefinementRejected(result.failure),
        )
    }
}

private sealed interface Kvp020RawReceiptObservation {
    data class Observed(val raw: String) : Kvp020RawReceiptObservation
    data class Rejected(val failure: Kvp020PredecessorObservationFailure.ReceiptReadRejected) :
        Kvp020RawReceiptObservation
}

private fun readPredecessor(
    id: Kvp020PredecessorReceiptId,
    path: Path,
): Kvp020RawReceiptObservation = try {
    Kvp020RawReceiptObservation.Observed(Files.readString(path))
} catch (_: IOException) {
    rejectedRead(id, path)
} catch (_: SecurityException) {
    rejectedRead(id, path)
}

private fun rejectedRead(id: Kvp020PredecessorReceiptId, path: Path) =
    Kvp020RawReceiptObservation.Rejected(
        Kvp020PredecessorObservationFailure.ReceiptReadRejected(id, path),
    )

internal sealed interface Kvp020SingleFlightReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp020SingleFlightReportFileFailure
    data class AdmissionRejected(val failure: Kvp020SingleFlightReportFailure) :
        Kvp020SingleFlightReportFileFailure
}

internal sealed interface Kvp020SingleFlightReportFileObservation {
    data class Observed(val canonical: String) : Kvp020SingleFlightReportFileObservation
    data class Rejected(val failure: Kvp020SingleFlightReportFileFailure) :
        Kvp020SingleFlightReportFileObservation
}

/**
 * Proof transition: `(Path, Kvp020ReportPredecessors) ->
 * Kvp020SingleFlightReportFileObservation`.
 * Establishes readable canonical report bytes admitted against the exact predecessor pair.
 * Read and admission failures remain closed [Kvp020SingleFlightReportFileFailure] data; raw report
 * bytes are extracted only here at the Gradle report-task boundary.
 */
internal fun observeKvp020SingleFlightReport(
    path: Path,
    predecessors: Kvp020ReportPredecessors,
): Kvp020SingleFlightReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp020SingleFlightReportFileObservation.Rejected(
            Kvp020SingleFlightReportFileFailure.ReadRejected(path),
        )
    } catch (_: SecurityException) {
        return Kvp020SingleFlightReportFileObservation.Rejected(
            Kvp020SingleFlightReportFileFailure.ReadRejected(path),
        )
    }
    return when (val admission = AdmittedKvp020SingleFlightReport.admit(raw, predecessors)) {
        is Kvp020SingleFlightReportAdmission.Admitted ->
            Kvp020SingleFlightReportFileObservation.Observed(admission.report.canonicalDocument)
        is Kvp020SingleFlightReportAdmission.Rejected ->
            Kvp020SingleFlightReportFileObservation.Rejected(
                Kvp020SingleFlightReportFileFailure.AdmissionRejected(admission.failure),
            )
    }
}

internal sealed interface Kvp020SingleFlightReportMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp020SingleFlightReportFailure,
    ) : Kvp020SingleFlightReportMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp020SingleFlightReportFailure,
        val observed: Kvp020SingleFlightReportFailure,
    ) : Kvp020SingleFlightReportMutationFailure
}

internal sealed interface Kvp020SingleFlightReportMutationVerification {
    data object Complete : Kvp020SingleFlightReportMutationVerification
    data class Rejected(val failure: Kvp020SingleFlightReportMutationFailure) :
        Kvp020SingleFlightReportMutationVerification
}

/**
 * Proof transition: `(String, Kvp020ReportPredecessors) ->
 * Kvp020SingleFlightReportMutationVerification`.
 * Establishes every fixed report corruption maps to its exact finite failure.
 */
internal fun verifyKvp020SingleFlightReportMutations(
    canonical: String,
    predecessors: Kvp020ReportPredecessors,
): Kvp020SingleFlightReportMutationVerification {
    val mutations = kvp020ReportMutations(canonical)
    mutations.forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp020SingleFlightReport.admit(
            mutation.raw,
            predecessors,
        )) {
            is Kvp020SingleFlightReportAdmission.Admitted ->
                return Kvp020SingleFlightReportMutationVerification.Rejected(
                    Kvp020SingleFlightReportMutationFailure.MutationAdmitted(
                        index,
                        mutation.expected,
                    ),
                )
            is Kvp020SingleFlightReportAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp020SingleFlightReportMutationVerification.Rejected(
                        Kvp020SingleFlightReportMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp020SingleFlightReportMutationVerification.Complete
}

private data class Kvp020ReportMutation(
    val raw: String,
    val expected: Kvp020SingleFlightReportFailure,
)

private fun mutation(raw: String, expected: Kvp020SingleFlightReportFailure) =
    Kvp020ReportMutation(raw, expected)

private fun kvp020ReportMutations(canonical: String) = listOf(
    mutation(canonical.replaceFirst("{", "["),
        Kvp020SingleFlightReportFailure.MALFORMED_DOCUMENT),
    mutation(canonical.dropLast(1),
        Kvp020SingleFlightReportFailure.NON_CANONICAL_DOCUMENT),
    mutation(canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp020SingleFlightReportFailure.SCHEMA_MISMATCH),
    mutation(canonical.replaceFirst("\"taskId\": \"KVP-020\"", "\"taskId\": \"KVP-021\""),
        Kvp020SingleFlightReportFailure.IDENTITY_MISMATCH),
    mutation(canonical.replaceFirst("\"CANONICAL_ROOT\"",
        "\"PROJECT_READ_EPOCH_COMPARISON_DOMAIN\""),
        Kvp020SingleFlightReportFailure.SCOPE_EVIDENCE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"IDLE\"", "\"ACTIVE\""),
        Kvp020SingleFlightReportFailure.STATE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"activePermitLimit\": 1", "\"activePermitLimit\": 2"),
        Kvp020SingleFlightReportFailure.ACTIVE_LIMIT_MISMATCH),
    mutation(canonical.replaceFirst("\"queuedRequestLimit\": 1", "\"queuedRequestLimit\": 2"),
        Kvp020SingleFlightReportFailure.QUEUE_LIMIT_MISMATCH),
    mutation(canonical.replaceFirst("\"ACTIVE_PERMIT_ISSUED\"", "\"REQUEST_QUEUED\""),
        Kvp020SingleFlightReportFailure.ADMISSION_CASE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"PROJECT_DISPOSED\"", "\"PLUGIN_UNLOADED\""),
        Kvp020SingleFlightReportFailure.RETIREMENT_CAUSE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"REQUEST_CANCELLED\"", "\"CLIENT_DISCONNECTED\""),
        Kvp020SingleFlightReportFailure.CANCELLATION_CAUSE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"terminalizationLimitPerAuthority\": 1",
        "\"terminalizationLimitPerAuthority\": 2"),
        Kvp020SingleFlightReportFailure.TERMINALIZATION_LIMIT_MISMATCH),
    mutation(canonical.replaceFirst("\"promotionLimitPerActiveTerminalization\": 1",
        "\"promotionLimitPerActiveTerminalization\": 2"),
        Kvp020SingleFlightReportFailure.PROMOTION_LIMIT_MISMATCH),
    mutation(canonical.replaceFirst("\"freshnessObservationCount\": 0",
        "\"freshnessObservationCount\": 1"),
        Kvp020SingleFlightReportFailure.FRESHNESS_OBSERVATION_MISMATCH),
    mutation(canonical.replaceFirst("\"semanticExecutionCount\": 0",
        "\"semanticExecutionCount\": 1"),
        Kvp020SingleFlightReportFailure.SEMANTIC_EXECUTION_MISMATCH),
    mutation(canonical.replaceFirst("\"VFS_PASSIVE_READ_CAPABILITY\"", "\"CANONICAL_ROOT\""),
        Kvp020SingleFlightReportFailure.RETAINED_EVIDENCE_SET_MISMATCH),
    mutation(canonical.replaceFirst("\"UNBOUNDED_CHANNEL\"",
        "\"GLOBAL_LOCK_ACROSS_PROJECTS\""),
        Kvp020SingleFlightReportFailure.FORBIDDEN_WORK_MISMATCH),
    mutation(canonical.replaceFirst("\"INTELLIJ_PROJECT\"", "\"CALLBACK\""),
        Kvp020SingleFlightReportFailure.FORBIDDEN_RETENTION_MISMATCH),
    mutation(canonical.replaceFirst("KVP-014-COMPLETE", "KVP-019-COMPLETE"),
        Kvp020SingleFlightReportFailure.PREDECESSOR_SET_MISMATCH),
    mutation(canonical.replaceFirst(Regex("\"sha256\": \"[0-9a-f]{64}\""),
        "\"sha256\": \"invalid\""),
        Kvp020SingleFlightReportFailure.PREDECESSOR_SET_MISMATCH),
)
