package support.delivery

internal sealed interface ProgramAuthorityGenerationFailure {
    data class CandidatePathNotAllowed(
        val path: AuthoritySourcePath,
    ) : ProgramAuthorityGenerationFailure

    data class DuplicateCandidatePath(
        val path: AuthoritySourcePath,
    ) : ProgramAuthorityGenerationFailure

    data class SourceUnavailable(
        val path: AuthoritySourcePath,
        val failure: AuthoritySourceFailure,
    ) : ProgramAuthorityGenerationFailure

    data class UnexpectedDigest(
        val path: AuthoritySourcePath,
        val digest: AuthorityArtifactDigest,
    ) : ProgramAuthorityGenerationFailure

    data class AmbiguousDeclaredDigest(
        val digest: AuthorityArtifactDigest,
    ) : ProgramAuthorityGenerationFailure

    data class DuplicateSourceMatch(
        val sourceId: AuthoritySourceId,
    ) : ProgramAuthorityGenerationFailure

    data class MissingDeclaredSource(
        val sourceId: AuthoritySourceId,
    ) : ProgramAuthorityGenerationFailure
}

internal class GeneratedProgramAuthority private constructor(
    val document: ProgramAuthorityDocument,
) {
    internal companion object {
        /**
         * Proof transition: `ProgramAuthorityExpectation` plus candidate source observations ->
         * `GeneratedProgramAuthority`.
         *
         * Establishes a one-to-one source-ID-to-path mapping from exact declared SHA-256 evidence
         * and carries every closed policy set. Expected failure remains
         * [ProgramAuthorityGeneration.Rejected]. Raw paths remain at the Gradle task boundary.
         */
        fun refine(
            expectation: ProgramAuthorityExpectation,
            candidatePaths: List<AuthoritySourcePath>,
            observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation,
        ): ProgramAuthorityGeneration {
            candidatePaths.sortedBy { it.value }.firstOrNull {
                it !in expectation.allowedReads
            }?.let {
                return ProgramAuthorityGeneration.Rejected(
                    ProgramAuthorityGenerationFailure.CandidatePathNotAllowed(it),
                )
            }
            candidatePaths.duplicatePath()?.let {
                return ProgramAuthorityGeneration.Rejected(
                    ProgramAuthorityGenerationFailure.DuplicateCandidatePath(it),
                )
            }
            expectation.sourceDigests.ambiguousDigest()?.let {
                return ProgramAuthorityGeneration.Rejected(
                    ProgramAuthorityGenerationFailure.AmbiguousDeclaredDigest(it),
                )
            }

            val sourceDocuments = mutableMapOf<AuthoritySourceId, AuthoritySourceDocument>()
            for (path in candidatePaths.sortedBy { it.value }) {
                val digest = when (val observation = observeSource(path)) {
                    is AuthoritySourceObservation.Complete -> observation.digest
                    is AuthoritySourceObservation.Rejected -> {
                        return ProgramAuthorityGeneration.Rejected(
                            ProgramAuthorityGenerationFailure.SourceUnavailable(
                                path,
                                observation.failure,
                            ),
                        )
                    }
                }
                val sourceId = expectation.sourceDigests.entries
                    .singleOrNull { it.value == digest }
                    ?.key
                    ?: return ProgramAuthorityGeneration.Rejected(
                        ProgramAuthorityGenerationFailure.UnexpectedDigest(path, digest),
                    )
                if (sourceId in sourceDocuments) {
                    return ProgramAuthorityGeneration.Rejected(
                        ProgramAuthorityGenerationFailure.DuplicateSourceMatch(sourceId),
                    )
                }
                sourceDocuments[sourceId] = AuthoritySourceDocument(
                    sourceId.value,
                    path.value,
                    digest.value,
                )
            }

            expectation.sourceDigests.keys.sortedBy { it.value }
                .firstOrNull { it !in sourceDocuments }
                ?.let {
                    return ProgramAuthorityGeneration.Rejected(
                        ProgramAuthorityGenerationFailure.MissingDeclaredSource(it),
                    )
                }

            return ProgramAuthorityGeneration.Complete(
                GeneratedProgramAuthority(
                    ProgramAuthorityDocument(
                        schemaVersion = 1,
                        baseRevision = expectation.baseRevision.value,
                        exactHead = expectation.exactHead.value,
                        programFingerprint = expectation.programFingerprint.value,
                        requirementFingerprint = expectation.requirementFingerprint.value,
                        sourceArtifacts = sourceDocuments.entries.sortedBy { it.key.value }
                            .map { it.value },
                        contradictions = AuthorityContradiction.entries.toSet(),
                        obsoleteAssumptions = ObsoleteAuthorityAssumption.entries.toSet(),
                        unprovenClaims = UnprovenAuthorityClaim.entries.toSet(),
                    ),
                ),
            )
        }
    }
}

internal sealed interface ProgramAuthorityGeneration {
    data class Complete(
        val authority: GeneratedProgramAuthority,
    ) : ProgramAuthorityGeneration

    data class Rejected(
        val failure: ProgramAuthorityGenerationFailure,
    ) : ProgramAuthorityGeneration
}

/**
 * Proof transition: `ProgramAuthorityExpectation` plus candidate source observations ->
 * `GeneratedProgramAuthority`.
 *
 * Establishes a one-to-one source-ID-to-path mapping from exact declared SHA-256 evidence and
 * carries the complete contradiction, obsolete-assumption, and unproven-claim sets. Missing,
 * duplicate, undeclared, unavailable, or ambiguous evidence returns
 * [ProgramAuthorityGeneration.Rejected]. Raw path extraction is permitted only at the Gradle
 * authority task boundary.
 */
internal fun generateProgramAuthority(
    expectation: ProgramAuthorityExpectation,
    candidatePaths: List<AuthoritySourcePath>,
    observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation,
): ProgramAuthorityGeneration = GeneratedProgramAuthority.refine(
    expectation,
    candidatePaths,
    observeSource,
)

private fun List<AuthoritySourcePath>.duplicatePath(): AuthoritySourcePath? =
    groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .minByOrNull { it.value }

private fun Map<AuthoritySourceId, AuthorityArtifactDigest>.ambiguousDigest(): AuthorityArtifactDigest? =
    entries
        .groupBy { it.value }
        .filterValues { it.size > 1 }
        .keys
        .minByOrNull { it.value }
