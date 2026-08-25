package support.delivery

@JvmInline
internal value class AuthorityGitRevision internal constructor(val value: String)

@JvmInline
internal value class ProgramFingerprint internal constructor(val value: String)

@JvmInline
internal value class RequirementFingerprint internal constructor(val value: String)

@JvmInline
internal value class AuthoritySourceId internal constructor(val value: String)

@JvmInline
internal value class AuthoritySourcePath internal constructor(val value: String)

@JvmInline
internal value class AuthorityArtifactDigest internal constructor(val value: String)

@JvmInline
internal value class AuthorityArtifactPath internal constructor(val value: String)

internal enum class AuthorityContradiction {
    PROGRAM_TARGET_IS_BASE_REVISION_NOT_SELF_HASHING_COMMIT,
    DECLARED_SOURCE_INPUTS_MUST_EXIST,
    ORIGINAL_DELIVERY_AUTHORITY_BYTES_UNAVAILABLE,
    CHECKED_IN_RECEIPT_CANNOT_BIND_ITS_OWN_COMMIT,
    PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT,
    LEGACY_TWO_PROCESS_TERMINAL_CONFLICTS_WITH_IDE_HOSTED_TERMINAL,
}

internal enum class ObsoleteAuthorityAssumption {
    EXACTLY_TWO_RUNTIME_PROCESSES,
    PACKAGED_INDEXER_TERMINAL_ACCEPTANCE,
    FOREGROUND_IDE_CONTROLS_SEMANTIC_BACKEND,
}

internal enum class UnprovenAuthorityClaim {
    SUPPORTED_IDE_ENDPOINT_COMPATIBILITY,
    FORBIDDEN_EFFECT_ABSENCE,
    EPOCH_MOVEMENT_COVERAGE,
    INSTALLED_OPERATION_CHAIN,
    CLEAN_CHECKOUT_AND_HOME_ACCEPTANCE,
    EXACT_HEAD_CI,
    INDEPENDENT_REVIEW,
}

internal data class ProgramAuthorityDocument(
    val schemaVersion: Int,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val sourceArtifacts: List<AuthoritySourceDocument>,
    val contradictions: Set<AuthorityContradiction>,
    val obsoleteAssumptions: Set<ObsoleteAuthorityAssumption>,
    val unprovenClaims: Set<UnprovenAuthorityClaim>,
)

internal data class AuthoritySourceDocument(
    val id: String,
    val path: String,
    val sha256: String,
)

internal class ProgramAuthorityExpectation private constructor(
    val baseRevision: AuthorityGitRevision,
    val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
    val sourceDigests: Map<AuthoritySourceId, AuthorityArtifactDigest>,
    val allowedReads: Set<AuthoritySourcePath>,
) {
    companion object {
        /**
         * Proof transition: raw Gradle authority inputs -> `ProgramAuthorityExpectation`.
         *
         * Establishes full Git and SHA-256 identities, unique non-blank source IDs, and non-blank
         * allowed paths. Expected malformed input returns [ProgramAuthorityExpectationResult.Rejected].
         * Raw values may be extracted only by the Gradle task configuration boundary.
         */
        fun parse(
            baseRevision: String,
            exactHead: String,
            programFingerprint: String,
            requirementFingerprint: String,
            sourceDigests: Map<String, String>,
            allowedReads: List<String>,
        ): ProgramAuthorityExpectationResult {
            if (!baseRevision.isGitRevision() || !exactHead.isGitRevision()) {
                return ProgramAuthorityExpectationResult.Rejected(
                    AuthorityAdmissionFailure.MalformedGitRevision,
                )
            }
            if (!programFingerprint.isSha256() || !requirementFingerprint.isSha256()) {
                return ProgramAuthorityExpectationResult.Rejected(
                    AuthorityAdmissionFailure.MalformedFingerprint,
                )
            }
            if (
                sourceDigests.isEmpty() ||
                sourceDigests.any { (id, digest) -> id.isBlank() || !digest.isSha256() }
            ) {
                return ProgramAuthorityExpectationResult.Rejected(
                    AuthorityAdmissionFailure.MalformedSourceDeclaration,
                )
            }
            if (allowedReads.any(String::isBlank)) {
                return ProgramAuthorityExpectationResult.Rejected(
                    AuthorityAdmissionFailure.MalformedSourcePath,
                )
            }
            return ProgramAuthorityExpectationResult.Complete(
                ProgramAuthorityExpectation(
                    AuthorityGitRevision(baseRevision),
                    AuthorityGitRevision(exactHead),
                    ProgramFingerprint(programFingerprint),
                    RequirementFingerprint(requirementFingerprint),
                    sourceDigests.map { (id, digest) ->
                        AuthoritySourceId(id) to AuthorityArtifactDigest(digest)
                    }.toMap(),
                    allowedReads.mapTo(mutableSetOf(), ::AuthoritySourcePath),
                ),
            )
        }
    }
}

internal sealed interface ProgramAuthorityExpectationResult {
    data class Complete(
        val expectation: ProgramAuthorityExpectation,
    ) : ProgramAuthorityExpectationResult

    data class Rejected(
        val failure: AuthorityAdmissionFailure,
    ) : ProgramAuthorityExpectationResult
}

internal sealed interface AuthoritySourceObservation {
    data class Complete(
        val digest: AuthorityArtifactDigest,
    ) : AuthoritySourceObservation

    data class Rejected(
        val failure: AuthoritySourceFailure,
    ) : AuthoritySourceObservation
}

internal enum class AuthoritySourceFailure {
    INVALID_LIMIT,
    MISSING,
    NOT_REGULAR,
    SYMLINK,
    TOO_LARGE,
    READ_FAILED,
}

internal sealed interface AuthorityAdmissionFailure {
    data object MalformedDocument : AuthorityAdmissionFailure
    data object UnsupportedSchema : AuthorityAdmissionFailure
    data object MalformedGitRevision : AuthorityAdmissionFailure
    data object MalformedFingerprint : AuthorityAdmissionFailure
    data object MalformedSourceDeclaration : AuthorityAdmissionFailure
    data object MalformedSourcePath : AuthorityAdmissionFailure
    data object BaseRevisionMismatch : AuthorityAdmissionFailure
    data object ExactHeadMismatch : AuthorityAdmissionFailure
    data object ProgramFingerprintMismatch : AuthorityAdmissionFailure
    data object RequirementFingerprintMismatch : AuthorityAdmissionFailure
    data object SourceSetMismatch : AuthorityAdmissionFailure

    data class SourcePathNotAllowed(
        val sourceId: AuthoritySourceId,
    ) : AuthorityAdmissionFailure

    data class SourceUnavailable(
        val sourceId: AuthoritySourceId,
        val failure: AuthoritySourceFailure,
    ) : AuthorityAdmissionFailure

    data class SourceDigestMismatch(
        val sourceId: AuthoritySourceId,
    ) : AuthorityAdmissionFailure

    data object ContradictionSetIncomplete : AuthorityAdmissionFailure
    data object ObsoleteAssumptionSetIncomplete : AuthorityAdmissionFailure
    data object UnprovenClaimSetIncomplete : AuthorityAdmissionFailure
}

internal class AdmittedProgramAuthority internal constructor(
    val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
    val sourceDigests: Map<AuthoritySourceId, AuthorityArtifactDigest>,
    val contradictionProjection: String,
)

internal sealed interface ProgramAuthorityAdmission {
    data class Complete(
        val authority: AdmittedProgramAuthority,
    ) : ProgramAuthorityAdmission

    data class Rejected(
        val failure: AuthorityAdmissionFailure,
    ) : ProgramAuthorityAdmission
}

/**
 * Proof transition: parsed authority document plus an expectation and source observations ->
 * `AdmittedProgramAuthority`.
 *
 * Establishes exact revision and fingerprint identity, the complete declared source set and byte
 * digests, and exhaustive contradiction, obsolete-assumption, and unproven-claim sets. Every
 * expected failure returns [ProgramAuthorityAdmission.Rejected]. Raw paths remain inside the
 * Gradle authority task boundary.
 */
internal fun admitProgramAuthority(
    document: ProgramAuthorityDocument,
    expectation: ProgramAuthorityExpectation,
    observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation,
): ProgramAuthorityAdmission {
    if (document.schemaVersion != 1) {
        return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.UnsupportedSchema)
    }
    if (
        !document.baseRevision.isGitRevision() ||
        document.baseRevision != expectation.baseRevision.value
    ) {
        return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.BaseRevisionMismatch)
    }
    if (!document.exactHead.isGitRevision() || document.exactHead != expectation.exactHead.value) {
        return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.ExactHeadMismatch)
    }
    if (
        !document.programFingerprint.isSha256() ||
        document.programFingerprint != expectation.programFingerprint.value
    ) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.ProgramFingerprintMismatch,
        )
    }
    if (
        !document.requirementFingerprint.isSha256() ||
        document.requirementFingerprint != expectation.requirementFingerprint.value
    ) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.RequirementFingerprintMismatch,
        )
    }
    if (document.sourceArtifacts.map { it.id }.toSet().size != document.sourceArtifacts.size) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.MalformedSourceDeclaration,
        )
    }
    if (
        document.sourceArtifacts.map { AuthoritySourceId(it.id) }.toSet() !=
        expectation.sourceDigests.keys
    ) {
        return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourceSetMismatch)
    }
    for (source in document.sourceArtifacts) {
        val sourceId = AuthoritySourceId(source.id)
        val sourcePath = AuthoritySourcePath(source.path)
        val expectedDigest = expectation.sourceDigests.getValue(sourceId)
        if (sourcePath !in expectation.allowedReads) {
            return ProgramAuthorityAdmission.Rejected(
                AuthorityAdmissionFailure.SourcePathNotAllowed(sourceId),
            )
        }
        if (!source.sha256.isSha256() || source.sha256 != expectedDigest.value) {
            return ProgramAuthorityAdmission.Rejected(
                AuthorityAdmissionFailure.SourceDigestMismatch(sourceId),
            )
        }
        when (val observation = observeSource(sourcePath)) {
            is AuthoritySourceObservation.Complete -> if (observation.digest != expectedDigest) {
                return ProgramAuthorityAdmission.Rejected(
                    AuthorityAdmissionFailure.SourceDigestMismatch(sourceId),
                )
            }
            is AuthoritySourceObservation.Rejected -> {
                return ProgramAuthorityAdmission.Rejected(
                    AuthorityAdmissionFailure.SourceUnavailable(sourceId, observation.failure),
                )
            }
        }
    }
    if (document.contradictions != AuthorityContradiction.entries.toSet()) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.ContradictionSetIncomplete,
        )
    }
    if (document.obsoleteAssumptions != ObsoleteAuthorityAssumption.entries.toSet()) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete,
        )
    }
    if (document.unprovenClaims != UnprovenAuthorityClaim.entries.toSet()) {
        return ProgramAuthorityAdmission.Rejected(
            AuthorityAdmissionFailure.UnprovenClaimSetIncomplete,
        )
    }
    return ProgramAuthorityAdmission.Complete(
        AdmittedProgramAuthority(
            expectation.exactHead,
            expectation.programFingerprint,
            expectation.requirementFingerprint,
            expectation.sourceDigests,
            document.contradictionProjection(),
        ),
    )
}

internal fun ProgramAuthorityDocument.contradictionProjection(): String = buildString {
    appendLine("# VFS-passive authority contradictions")
    appendLine()
    appendLine("## Contradictions")
    contradictions.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
    appendLine()
    appendLine("## Obsolete assumptions")
    obsoleteAssumptions.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
    appendLine()
    appendLine("## Unproven claims")
    unprovenClaims.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
}

private fun String.isGitRevision(): Boolean = matches(Regex("[0-9a-f]{40}"))

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
