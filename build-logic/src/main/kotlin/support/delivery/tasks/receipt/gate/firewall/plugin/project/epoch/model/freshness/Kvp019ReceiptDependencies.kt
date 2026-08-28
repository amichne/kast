package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

internal enum class Kvp019DependencyMember { READ_EPOCH, HOSTED_PATH }
internal enum class Kvp019AuthorityField {
    REPOSITORY_ROOT,
    BASE_REVISION,
    EXACT_HEAD,
    PROGRAM_FINGERPRINT,
    REQUIREMENT_FINGERPRINT,
    SOURCE_DIGESTS,
}

internal sealed interface Kvp019DependencyFailure {
    data class HeadMismatch(val member: Kvp019DependencyMember) : Kvp019DependencyFailure
    data class AuthorityMismatch(val field: Kvp019AuthorityField) : Kvp019DependencyFailure
    data class PredecessorRejected(val failure: Kvp019ReportFailure) : Kvp019DependencyFailure
}

internal sealed interface Kvp019DependencyRefinement {
    data class Admitted(val context: Kvp019DependencyContexts) : Kvp019DependencyRefinement
    data class Rejected(val failure: Kvp019DependencyFailure) : Kvp019DependencyRefinement
}

private sealed interface Kvp019AuthorityComparison {
    data object Same : Kvp019AuthorityComparison
    data class Mismatch(val failure: Kvp019DependencyFailure.AuthorityMismatch) :
        Kvp019AuthorityComparison
}

internal class Kvp019DependencyContexts private constructor(
    val boundary: Kvp001ReceiptContext,
    val predecessors: Kvp019ReportPredecessors,
) {
    companion object {
        /**
         * Proof transition: `(AuthorityGitRevision, Kvp001ReceiptContext, AdmittedProofReceipt,
         * Kvp001ReceiptContext, AdmittedProofReceipt) -> Kvp019DependencyRefinement`.
         *
         * Establishes one immutable authority snapshot and the exact KVP-017/KVP-018 completion
         * pair. Head, authority, and semantic identity mismatches remain closed
         * [Kvp019DependencyFailure] data.
         */
        fun refine(
            expectedHead: AuthorityGitRevision,
            readEpochBoundary: Kvp001ReceiptContext,
            readEpochCompletion: AdmittedProofReceipt,
            hostedBoundary: Kvp001ReceiptContext,
            hostedCompletion: AdmittedProofReceipt,
        ): Kvp019DependencyRefinement {
            if (readEpochBoundary.exactHead != expectedHead.value ||
                readEpochCompletion.exactHead != expectedHead
            ) return rejected(Kvp019DependencyFailure.HeadMismatch(
                Kvp019DependencyMember.READ_EPOCH,
            ))
            if (hostedBoundary.exactHead != expectedHead.value ||
                hostedCompletion.exactHead != expectedHead
            ) return rejected(Kvp019DependencyFailure.HeadMismatch(
                Kvp019DependencyMember.HOSTED_PATH,
            ))
            val readEpochSnapshot = readEpochBoundary.kvp019Snapshot()
            val hostedSnapshot = hostedBoundary.kvp019Snapshot()
            when (val comparison = compareAuthority(readEpochSnapshot, hostedSnapshot)) {
                Kvp019AuthorityComparison.Same -> Unit
                is Kvp019AuthorityComparison.Mismatch -> return rejected(comparison.failure)
            }
            val readEpoch = when (val result = Kvp019PredecessorReceipt.fromAdmitted(
                Kvp019PredecessorReceiptId.KVP_017_COMPLETE,
                readEpochCompletion,
            )) {
                is Kvp019PredecessorRefinement.Admitted -> result.receipt
                is Kvp019PredecessorRefinement.Rejected -> return rejected(
                    Kvp019DependencyFailure.PredecessorRejected(result.failure),
                )
            }
            val hosted = when (val result = Kvp019PredecessorReceipt.fromAdmitted(
                Kvp019PredecessorReceiptId.KVP_018_COMPLETE,
                hostedCompletion,
            )) {
                is Kvp019PredecessorRefinement.Admitted -> result.receipt
                is Kvp019PredecessorRefinement.Rejected -> return rejected(
                    Kvp019DependencyFailure.PredecessorRejected(result.failure),
                )
            }
            val receipts = listOf(readEpoch, hosted)
            return when (val result = Kvp019ReportPredecessors.refine(receipts)) {
                is Kvp019PredecessorSetRefinement.Admitted ->
                    Kvp019DependencyRefinement.Admitted(Kvp019DependencyContexts(
                        readEpochSnapshot,
                        result.predecessors,
                    ))
                is Kvp019PredecessorSetRefinement.Rejected -> rejected(
                    Kvp019DependencyFailure.PredecessorRejected(result.failure),
                )
            }
        }

        private fun compareAuthority(
            left: Kvp001ReceiptContext,
            right: Kvp001ReceiptContext,
        ): Kvp019AuthorityComparison = when {
            left.repositoryRoot != right.repositoryRoot -> mismatch(
                Kvp019AuthorityField.REPOSITORY_ROOT,
            )
            left.baseRevision != right.baseRevision -> mismatch(Kvp019AuthorityField.BASE_REVISION)
            left.exactHead != right.exactHead -> mismatch(Kvp019AuthorityField.EXACT_HEAD)
            left.programFingerprint != right.programFingerprint -> mismatch(
                Kvp019AuthorityField.PROGRAM_FINGERPRINT,
            )
            left.requirementFingerprint != right.requirementFingerprint -> mismatch(
                Kvp019AuthorityField.REQUIREMENT_FINGERPRINT,
            )
            left.sourceDigests != right.sourceDigests -> mismatch(Kvp019AuthorityField.SOURCE_DIGESTS)
            else -> Kvp019AuthorityComparison.Same
        }

        private fun mismatch(field: Kvp019AuthorityField) =
            Kvp019AuthorityComparison.Mismatch(
                Kvp019DependencyFailure.AuthorityMismatch(field),
            )

        private fun rejected(failure: Kvp019DependencyFailure) =
            Kvp019DependencyRefinement.Rejected(failure)
    }
}

private fun Kvp001ReceiptContext.kvp019Snapshot() = copy(
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
    sourceDigests = sourceDigests.toMap(),
    redArtifactPaths = redArtifactPaths.toList(),
    greenArtifactPaths = greenArtifactPaths.toList(),
)

internal sealed interface Kvp019PredecessorObservationFailure {
    data class ReceiptReadRejected(
        val id: Kvp019PredecessorReceiptId,
        val path: Path,
    ) : Kvp019PredecessorObservationFailure

    data class RefinementRejected(val failure: Kvp019ReportFailure) :
        Kvp019PredecessorObservationFailure
}

internal sealed interface Kvp019PredecessorObservation {
    data class Observed(val predecessors: Kvp019ReportPredecessors) :
        Kvp019PredecessorObservation
    data class Rejected(val failure: Kvp019PredecessorObservationFailure) :
        Kvp019PredecessorObservation
}

/**
 * Proof transition: `(Path, Path) -> Kvp019PredecessorObservation`.
 *
 * Establishes readable, generated-schema, self-digested KVP-017/KVP-018 completion receipts in
 * exact canonical order. Read, parse, identity, digest, or set failure remains closed
 * [Kvp019PredecessorObservationFailure] data. Raw file bytes are extracted only here at the
 * Gradle report-task boundary.
 */
internal fun observeKvp019ReportPredecessors(
    kvp017: Path,
    kvp018: Path,
): Kvp019PredecessorObservation {
    val readEpoch = when (val observed = readPredecessor(
        Kvp019PredecessorReceiptId.KVP_017_COMPLETE,
        kvp017,
    )) {
        is Kvp019RawReceiptObservation.Observed -> observed.raw
        is Kvp019RawReceiptObservation.Rejected -> return Kvp019PredecessorObservation.Rejected(
            observed.failure,
        )
    }
    val hosted = when (val observed = readPredecessor(
        Kvp019PredecessorReceiptId.KVP_018_COMPLETE,
        kvp018,
    )) {
        is Kvp019RawReceiptObservation.Observed -> observed.raw
        is Kvp019RawReceiptObservation.Rejected -> return Kvp019PredecessorObservation.Rejected(
            observed.failure,
        )
    }
    return when (val result = refineKvp019ReportPredecessors(readEpoch, hosted)) {
        is Kvp019PredecessorSetRefinement.Admitted ->
            Kvp019PredecessorObservation.Observed(result.predecessors)
        is Kvp019PredecessorSetRefinement.Rejected -> Kvp019PredecessorObservation.Rejected(
            Kvp019PredecessorObservationFailure.RefinementRejected(result.failure),
        )
    }
}

private sealed interface Kvp019RawReceiptObservation {
    data class Observed(val raw: String) : Kvp019RawReceiptObservation
    data class Rejected(val failure: Kvp019PredecessorObservationFailure.ReceiptReadRejected) :
        Kvp019RawReceiptObservation
}

private fun readPredecessor(
    id: Kvp019PredecessorReceiptId,
    path: Path,
): Kvp019RawReceiptObservation = try {
    Kvp019RawReceiptObservation.Observed(Files.readString(path))
} catch (_: IOException) {
    Kvp019RawReceiptObservation.Rejected(
        Kvp019PredecessorObservationFailure.ReceiptReadRejected(id, path),
    )
} catch (_: SecurityException) {
    Kvp019RawReceiptObservation.Rejected(
        Kvp019PredecessorObservationFailure.ReceiptReadRejected(id, path),
    )
}

internal sealed interface Kvp019ReportFileFailure {
    data class ReadRejected(val path: Path) : Kvp019ReportFileFailure
    data class AdmissionRejected(val failure: Kvp019ReportFailure) : Kvp019ReportFileFailure
}

internal sealed interface Kvp019ReportFileObservation {
    data class Observed(val canonical: String) : Kvp019ReportFileObservation
    data class Rejected(val failure: Kvp019ReportFileFailure) : Kvp019ReportFileObservation
}

/**
 * Proof transition: `(Path, Kvp019ReportPredecessors) -> Kvp019ReportFileObservation`.
 * Establishes readable canonical report bytes admitted against the exact predecessor pair.
 * Read and admission failures remain closed [Kvp019ReportFileFailure] data; raw report bytes are
 * extracted only here at the Gradle report-task boundary.
 */
internal fun observeKvp019Report(
    path: Path,
    predecessors: Kvp019ReportPredecessors,
): Kvp019ReportFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp019ReportFileObservation.Rejected(Kvp019ReportFileFailure.ReadRejected(path))
    } catch (_: SecurityException) {
        return Kvp019ReportFileObservation.Rejected(Kvp019ReportFileFailure.ReadRejected(path))
    }
    return when (val admission = AdmittedKvp019VfsPassiveReport.admit(raw, predecessors)) {
        is Kvp019ReportAdmission.Admitted ->
            Kvp019ReportFileObservation.Observed(admission.report.canonicalDocument)
        is Kvp019ReportAdmission.Rejected -> Kvp019ReportFileObservation.Rejected(
            Kvp019ReportFileFailure.AdmissionRejected(admission.failure),
        )
    }
}

internal sealed interface Kvp019ReportMutationFailure {
    data class MutationAdmitted(val index: Int, val expected: Kvp019ReportFailure) :
        Kvp019ReportMutationFailure
    data class WrongFailure(
        val index: Int,
        val expected: Kvp019ReportFailure,
        val observed: Kvp019ReportFailure,
    ) : Kvp019ReportMutationFailure
}

internal sealed interface Kvp019ReportMutationVerification {
    data object Complete : Kvp019ReportMutationVerification
    data class Rejected(val failure: Kvp019ReportMutationFailure) :
        Kvp019ReportMutationVerification
}

/**
 * Proof transition: `(String, Kvp019ReportPredecessors) ->
 * Kvp019ReportMutationVerification`.
 * Establishes every fixed report corruption maps to its exact finite failure.
 */
internal fun verifyKvp019ReportMutations(
    canonical: String,
    predecessors: Kvp019ReportPredecessors,
): Kvp019ReportMutationVerification {
    val mutations = listOf(
        mutation(canonical.replaceFirst("{", "["), Kvp019ReportFailure.MALFORMED_DOCUMENT),
        mutation(canonical.dropLast(1), Kvp019ReportFailure.NON_CANONICAL_DOCUMENT),
        mutation(canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            Kvp019ReportFailure.SCHEMA_MISMATCH),
        mutation(canonical.replaceFirst("\"taskId\": \"KVP-019\"", "\"taskId\": \"KVP-020\""),
            Kvp019ReportFailure.IDENTITY_MISMATCH),
        mutation(canonical.replaceFirst("\"READ_EPOCH\"", "\"OPEN_PROJECT\""),
            Kvp019ReportFailure.MALFORMED_DOCUMENT),
        mutation(canonical.replaceFirst("\"ADMITTED_SAME_SOURCE_EQUAL_STATE\"",
            "\"REJECTED_MOVED_STATE\""), Kvp019ReportFailure.ADMISSION_CASE_SET_MISMATCH),
        mutation(canonical.replaceFirst("\"CANONICAL_ROOT\"", "\"ADMITTED_EPOCH\""),
            Kvp019ReportFailure.RETAINED_EVIDENCE_SET_MISMATCH),
        mutation(canonical.replaceFirst("\"freshnessObservationCountPerAdmission\": 1",
            "\"freshnessObservationCountPerAdmission\": 2"),
            Kvp019ReportFailure.OBSERVATION_COUNT_MISMATCH),
        mutation(canonical.replaceFirst("\"WRONG_THREAD\"", "\"PROJECT_NOT_OPEN\""),
            Kvp019ReportFailure.UNAVAILABLE_FAILURE_SET_MISMATCH),
        mutation(canonical.replaceFirst("\"unavailableObservationFailureCount\": 16",
            "\"unavailableObservationFailureCount\": 15"),
            Kvp019ReportFailure.UNAVAILABLE_FAILURE_COUNT_MISMATCH),
        mutation(canonical.replaceFirst("\"THREAD\"", "\"DISPOSAL\""),
            Kvp019ReportFailure.OBSERVATION_STAGE_SET_MISMATCH),
        mutation(canonical.replaceFirst("\"observedCount\": 0", "\"observedCount\": 1"),
            Kvp019ReportFailure.FORBIDDEN_WORK_MISMATCH),
        mutation(canonical.replaceFirst("\"VFS_REFRESH\"", "\"GRADLE_IMPORT\""),
            Kvp019ReportFailure.FORBIDDEN_WORK_MISMATCH),
        mutation(canonical.replaceFirst("KVP-017-COMPLETE", "KVP-018-COMPLETE"),
            Kvp019ReportFailure.PREDECESSOR_SET_MISMATCH),
        mutation(canonical.replaceFirst(Regex("\"sha256\": \"[0-9a-f]{64}\""),
            "\"sha256\": \"invalid\""), Kvp019ReportFailure.PREDECESSOR_SET_MISMATCH),
    )
    mutations.forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp019VfsPassiveReport.admit(
            mutation.raw,
            predecessors,
        )) {
            is Kvp019ReportAdmission.Admitted -> return Kvp019ReportMutationVerification.Rejected(
                Kvp019ReportMutationFailure.MutationAdmitted(index, mutation.expected),
            )
            is Kvp019ReportAdmission.Rejected -> if (admission.failure != mutation.expected) {
                return Kvp019ReportMutationVerification.Rejected(
                    Kvp019ReportMutationFailure.WrongFailure(
                        index,
                        mutation.expected,
                        admission.failure,
                    ),
                )
            }
        }
    }
    return Kvp019ReportMutationVerification.Complete
}

private data class Kvp019ReportMutation(
    val raw: String,
    val expected: Kvp019ReportFailure,
)

private fun mutation(raw: String, expected: Kvp019ReportFailure) =
    Kvp019ReportMutation(raw, expected)

abstract class Kvp019DependencyReceiptTaskBase : Kvp018ReceiptTaskBase() {
    @get:InputFile abstract val directHostedRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directHostedGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directHostedProofReportFile: RegularFileProperty
    @get:InputFile abstract val directHostedCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: configured KVP-017/KVP-018 inputs plus `AuthorityGitRevision` ->
     * `Kvp019DependencyContexts`.
     *
     * Re-admits both complete predecessor closures independently at one exact head. Raw receipt
     * extraction is permitted only here; every expected mismatch remains closed typed data until
     * this Gradle boundary renders it.
     */
    internal fun freshnessDependencyContexts(
        head: AuthorityGitRevision,
    ): Kvp019DependencyContexts {
        val readEpoch = readEpochContexts(head)
        val readEpochReport = readEpoch.reportProof()
        val readEpochRed = readEpoch.boundary.admit(
            directReadEpochRedReceiptFile.get().asFile.toPath(),
            readEpoch.redExpectation(),
        )
        val readEpochGreen = readEpoch.boundary.admit(
            directReadEpochGreenReceiptFile.get().asFile.toPath(),
            readEpoch.greenExpectation(readEpochRed, readEpochReport),
        )
        val readEpochCompletion = readEpoch.boundary.admit(
            directReadEpochCompletionReceiptFile.get().asFile.toPath(),
            readEpoch.completionExpectation(readEpochRed, readEpochGreen),
        )
        val hosted = hostedContexts(head)
        val hostedRed = hosted.boundary.admit(
            directHostedRedReceiptFile.get().asFile.toPath(),
            hosted.redExpectation(),
        )
        val hostedGreen = hosted.boundary.admit(
            directHostedGreenReceiptFile.get().asFile.toPath(),
            hosted.greenExpectation(hostedRed),
        )
        val hostedCompletion = hosted.boundary.admit(
            directHostedCompletionReceiptFile.get().asFile.toPath(),
            hosted.completionExpectation(hostedRed, hostedGreen),
        )
        return when (val result = Kvp019DependencyContexts.refine(
            head,
            readEpoch.boundary,
            readEpochCompletion,
            hosted.boundary,
            hostedCompletion,
        )) {
            is Kvp019DependencyRefinement.Admitted -> result.context
            is Kvp019DependencyRefinement.Rejected -> rejectReceipt(
                "KVP-019 dependency context",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
                result.failure.toString(),
            )
        }
    }
}
