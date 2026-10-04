package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Native imported-model membership is separate from the compiler identity of the resolved target. */
enum class RelationScopeMembershipAuthority {
    IMPORTED_MODEL_NATIVE_SCOPE
}

enum class RelationScopeExclusionReason {
    SOURCE_DOMAIN,
    LIBRARY_POLICY,
}

enum class RelationScopeExclusionFailure {
    MEANING_MISMATCH,
    OCCURRENCE_OWNER_MISMATCH,
    LIBRARY_POLICY_MISMATCH,
    TARGET_INSIDE_EXACT_DOMAIN,
}

/** A resolved call leaves the declared domain; this is evidence, not an unresolved in-domain obligation. */
@ConsistentCopyVisibility
data class RelationScopeExclusion
private constructor(
    val subject: RelationEndpointFingerprint,
    val basis: SemanticReadAuthority,
    val requestedDomain: RelationSearchBoundary,
    val effectiveDomain: RelationScopeFingerprint,
    val effectiveScope: SymbolSearchScope,
    val effectiveConstraints: SymbolDiscoveryConstraints,
    val occurrence: RelationOccurrence,
    val target: CompilerGroundedSymbolEvidence,
    val reason: RelationScopeExclusionReason,
) : Comparable<RelationScopeExclusion> {
    val membershipAuthority: RelationScopeMembershipAuthority =
        RelationScopeMembershipAuthority.IMPORTED_MODEL_NATIVE_SCOPE

    override fun compareTo(other: RelationScopeExclusion): Int =
        canonicalProjection().compareTo(other.canonicalProjection())

    fun belongsTo(request: RelationRequest): Boolean =
        subject == request.subject.fingerprint &&
            basis == request.subject.lease &&
            requestedDomain == request.boundary &&
            effectiveDomain == request.scopeFingerprint &&
            effectiveScope == request.searchScope &&
            effectiveConstraints == request.searchConstraints &&
            request.meaning == RelationMeaning.Callees &&
            occurrence.file == request.subject.file

    fun canonicalProjection(): String = buildString {
        append(subject.value)
        append('\u0000')
        append(effectiveDomain.value)
        append('\u0000')
        append(occurrence.file.stableValue)
        append('\u0000')
        append(occurrence.range.startInclusive)
        append('\u0000')
        append(occurrence.range.endExclusive)
        append('\u0000')
        append(target.file.stableValue)
        append('\u0000')
        append(target.range.startInclusive)
        append('\u0000')
        append(target.range.endExclusive)
        append('\u0000')
        append(target.name.value)
        append('\u0000')
        append(target.compilerIdentity.value)
        append('\u0000')
        append(target.signature.canonicalEncoding().value)
        append('\u0000')
        append(reason.name)
    }

    companion object {
        /** Native boundary supplies observed membership only after K2 has produced the exact target evidence. */
        fun fromNativeBoundary(
            request: RelationRequest,
            occurrence: RelationOccurrence,
            target: CompilerGroundedSymbolEvidence,
            reason: RelationScopeExclusionReason,
        ): Refinement<RelationScopeExclusion, RelationScopeExclusionFailure> =
            when {
                request.meaning != RelationMeaning.Callees ->
                    Refinement.Rejected(RelationScopeExclusionFailure.MEANING_MISMATCH)
                occurrence.file != request.subject.file ->
                    Refinement.Rejected(RelationScopeExclusionFailure.OCCURRENCE_OWNER_MISMATCH)
                reason == RelationScopeExclusionReason.SOURCE_DOMAIN &&
                    request.searchScope is SymbolSearchScope.ExactFile &&
                    request.searchConstraints.directory == null &&
                    target.file.stableValue == request.searchScope.file.value ->
                    Refinement.Rejected(RelationScopeExclusionFailure.TARGET_INSIDE_EXACT_DOMAIN)
                reason == RelationScopeExclusionReason.LIBRARY_POLICY &&
                    (request.searchScope as? SymbolSearchScope.Workspace)?.libraries == SymbolLibraryPolicy.INCLUDE ->
                    Refinement.Rejected(RelationScopeExclusionFailure.LIBRARY_POLICY_MISMATCH)
                else ->
                    Refinement.Refined(
                        RelationScopeExclusion(
                            request.subject.fingerprint,
                            request.subject.lease,
                            request.boundary,
                            request.scopeFingerprint,
                            request.searchScope,
                            request.searchConstraints,
                            occurrence,
                            target,
                            reason,
                        )
                    )
            }
    }
}
