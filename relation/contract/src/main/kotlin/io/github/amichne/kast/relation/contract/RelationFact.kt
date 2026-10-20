package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.charset.StandardCharsets

enum class RelationEndpointResolutionFailure {
    FILE_OUTSIDE_EXACT_SCOPE
}

/** Exact compiler-grounded endpoint detached from every live IntelliJ and K2 object. */
sealed interface RelationEndpoint {
    val lease: SemanticReadAuthority
    val scope: SymbolSearchScope
    val constraints: SymbolDiscoveryConstraints
    val file: SymbolDiscoveryFileIdentity
    val range: ExactDeclarationTextRange
    val name: SymbolDiscoveryCandidateName
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity
    val kind: CompilerSymbolKind
    val signature: CanonicalCompilerSignature
    val compilerIdentity: CompilerSymbolIdentity
    val fingerprint: RelationEndpointFingerprint
    val valueIdentity: ValueDeclarationIdentity
    val valueExecutionOwner: ValueExecutionOwner

    /** A selected subject and a resolved endpoint can carry the same full current proof. */
    fun hasSameProofAs(other: RelationEndpoint): Boolean =
        lease == other.lease &&
            scope == other.scope &&
            constraints == other.constraints &&
            file == other.file &&
            range == other.range &&
            name == other.name &&
            qualifiedIdentity == other.qualifiedIdentity &&
            kind == other.kind &&
            signature == other.signature &&
            compilerIdentity == other.compilerIdentity

    @ConsistentCopyVisibility
    data class Subject internal constructor(val selector: SymbolSelector) : RelationEndpoint {
        override val lease: SemanticReadAuthority = selector.lease
        override val scope: SymbolSearchScope = selector.scope
        override val constraints: SymbolDiscoveryConstraints = selector.constraints
        override val file: SymbolDiscoveryFileIdentity = selector.file
        override val range: ExactDeclarationTextRange = selector.range
        override val name: SymbolDiscoveryCandidateName = selector.name
        override val qualifiedIdentity: ExactDeclarationQualifiedIdentity = selector.qualifiedIdentity
        override val kind: CompilerSymbolKind = selector.kind
        override val signature: CanonicalCompilerSignature = selector.signature
        override val compilerIdentity: CompilerSymbolIdentity = SymbolDescription.from(selector).compilerIdentity
        override val fingerprint: RelationEndpointFingerprint =
            RelationEndpointFingerprint.established(selector.fingerprint.value)
        override val valueIdentity = ValueDeclarationIdentity(compilerIdentity, file, range)
        override val valueExecutionOwner = ValueExecutionOwner.establishDeclaration(this)
    }

    @ConsistentCopyVisibility
    data class Resolved
    private constructor(
        override val lease: SemanticReadAuthority,
        override val scope: SymbolSearchScope,
        override val constraints: SymbolDiscoveryConstraints,
        val evidence: CompilerGroundedSymbolEvidence,
        override val fingerprint: RelationEndpointFingerprint,
    ) : RelationEndpoint {
        override val file: SymbolDiscoveryFileIdentity = evidence.file
        override val range: ExactDeclarationTextRange = evidence.range
        override val name: SymbolDiscoveryCandidateName = evidence.name
        override val qualifiedIdentity: ExactDeclarationQualifiedIdentity = evidence.qualifiedIdentity
        override val kind: CompilerSymbolKind = evidence.kind
        override val signature: CanonicalCompilerSignature = evidence.signature
        override val compilerIdentity: CompilerSymbolIdentity = evidence.compilerIdentity
        override val valueIdentity = ValueDeclarationIdentity(compilerIdentity, file, range)
        override val valueExecutionOwner = ValueExecutionOwner.establishDeclaration(this)

        companion object {
            internal fun create(
                lease: SemanticReadAuthority,
                scope: SymbolSearchScope,
                evidence: CompilerGroundedSymbolEvidence,
                constraints: SymbolDiscoveryConstraints,
            ): Resolved =
                Resolved(
                    lease,
                    scope,
                    constraints,
                    evidence,
                    relationEndpointFingerprint(lease, scope, evidence, constraints),
                )
        }
    }

    companion object {
        /**
         * Proof transition: `SymbolSelector -> RelationEndpoint.Subject`.
         *
         * Preserves the selector's exact compiler-grounded root, authority, scope, declaration, and opaque identity
         * without extracting a primitive subject.
         */
        fun subject(selector: SymbolSelector): Subject = Subject(selector)

        /**
         * Proof transition: `(SemanticReadAuthority, SymbolSearchScope, CompilerGroundedSymbolEvidence) ->
         * Refinement<RelationEndpoint.Resolved, RelationEndpointResolutionFailure>`.
         *
         * Establishes a detached compiler-grounded endpoint bound to the subject lease and scope.
         * [RelationEndpointResolutionFailure] is the closed expected failure. Raw PSI/K2 values may enter only through
         * compiler evidence at the request-local native adapter boundary.
         */
        fun resolve(
            lease: SemanticReadAuthority,
            scope: SymbolSearchScope,
            evidence: CompilerGroundedSymbolEvidence,
            constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        ): Refinement<Resolved, RelationEndpointResolutionFailure> {
            if (scope is SymbolSearchScope.ExactFile && evidence.file.stableValue != scope.file.value) {
                return Refinement.Rejected(RelationEndpointResolutionFailure.FILE_OUTSIDE_EXACT_SCOPE)
            }
            return Refinement.Refined(Resolved.create(lease, scope, evidence, constraints))
        }
    }
}

enum class RelationEndpointRevalidationFailure {
    DECLARATION_MOVED_OR_CHANGED
}

/** Proof that current compiler evidence is identical to one exact relation endpoint. */
class RevalidatedRelationEndpoint private constructor(val endpoint: RelationEndpoint) {
    companion object {
        /**
         * Proof transition: `(RelationEndpoint, CompilerGroundedSymbolEvidence) -> Refinement<
         * RevalidatedRelationEndpoint, RelationEndpointRevalidationFailure>`.
         *
         * Establishes identical detached file, range, name, qualified identity, kind, and compiler identity under the
         * endpoint's retained root, authority, and scope. [RelationEndpointRevalidationFailure] is the closed expected
         * failure. Raw compiler evidence may enter only from the request-local native relation adapter.
         */
        fun validate(
            endpoint: RelationEndpoint,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<RevalidatedRelationEndpoint, RelationEndpointRevalidationFailure> =
            if (
                endpoint.file == evidence.file &&
                    endpoint.range == evidence.range &&
                    endpoint.name == evidence.name &&
                    endpoint.qualifiedIdentity == evidence.qualifiedIdentity &&
                    endpoint.kind == evidence.kind &&
                    endpoint.signature == evidence.signature &&
                    endpoint.compilerIdentity == evidence.compilerIdentity
            ) {
                Refinement.Refined(RevalidatedRelationEndpoint(endpoint))
            } else {
                Refinement.Rejected(RelationEndpointRevalidationFailure.DECLARATION_MOVED_OR_CHANGED)
            }
    }
}

enum class RelationOccurrenceFailure {
    INVALID_RANGE
}

@ConsistentCopyVisibility
data class RelationOccurrence
private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        fun fromSource(
            file: SymbolDiscoveryFileIdentity,
            range: ExactDeclarationTextRange,
        ): RelationOccurrence = RelationOccurrence(file, range)

        /**
         * Proof transition: `(SymbolDiscoveryFileIdentity, Int, Int) -> Refinement< RelationOccurrence,
         * RelationOccurrenceFailure>`.
         *
         * Establishes an exact detached file and non-empty absolute source range. [RelationOccurrenceFailure] is the
         * closed expected failure. Raw offsets may enter only from a request-local K2-confirmed PSI occurrence.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<RelationOccurrence, RelationOccurrenceFailure> =
            when (val range = ExactDeclarationTextRange.parse(rawStartInclusive, rawEndExclusive)) {
                is Refinement.Refined -> Refinement.Refined(RelationOccurrence(file, range.value))
                is Refinement.Rejected -> Refinement.Rejected(RelationOccurrenceFailure.INVALID_RANGE)
            }
    }
}

/** Compiler authority plus source-root provenance for one exact occurrence. */
enum class RelationProvenance {
    K2_AUTHORED_SOURCE,
    K2_GENERATED_SOURCE,
    K2_PROJECT_LIBRARY,
}

/** Coverage of the individual edge, distinct from enumeration coverage of a result page. */
enum class RelationFactCoverage {
    EXACT_COMPILER_CONFIRMED
}

enum class RelationFactFailure {
    ENDPOINT_LEASE_MISMATCH,
    ENDPOINT_SCOPE_MISMATCH,
    SUBJECT_ORIENTATION_MISMATCH,
}

@ConsistentCopyVisibility
data class RelationFact
private constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val source: RelationEndpoint,
    val target: RelationEndpoint,
    val occurrence: RelationOccurrence,
    val authority: SemanticReadIdentity,
    val provenance: RelationProvenance,
    val coverage: RelationFactCoverage,
) : Comparable<RelationFact> {
    override fun compareTo(other: RelationFact): Int = RELATION_FACT_ORDER.compare(this, other)

    fun canonicalProjection(): String = buildString {
        appendFactField(meaning.canonicalOrder().toString())
        appendFactField(source.fingerprint.value)
        appendFactField(target.fingerprint.value)
        appendFactField(occurrence.file.stableValue)
        appendFactField(occurrence.range.startInclusive.toString())
        appendFactField(occurrence.range.endExclusive.toString())
        appendFactField(authority.revisionKey.value)
        appendFactField(provenance.name)
        appendFactField(coverage.name)
    }

    companion object {
        /**
         * Proof transition: `(RelationRequest, RelationEndpoint, RelationEndpoint, RelationOccurrence,
         * RelationProvenance) -> Refinement<RelationFact, RelationFactFailure>`.
         *
         * Establishes one exact compiler-confirmed edge with closed orientation, subject lease and scope, exact
         * occurrence, authority, and provenance. [RelationFactFailure] is the closed expected failure. Native values
         * may enter only through already-detached endpoints and occurrence evidence.
         */
        fun create(
            request: RelationRequest,
            source: RelationEndpoint,
            target: RelationEndpoint,
            occurrence: RelationOccurrence,
            provenance: RelationProvenance,
        ): Refinement<RelationFact, RelationFactFailure> {
            if (source.lease != request.subject.lease || target.lease != request.subject.lease) {
                return Refinement.Rejected(RelationFactFailure.ENDPOINT_LEASE_MISMATCH)
            }
            if (!request.admitsEndpoint(source) || !request.admitsEndpoint(target)) {
                return Refinement.Rejected(RelationFactFailure.ENDPOINT_SCOPE_MISMATCH)
            }
            val oriented =
                when (request.meaning) {
                    RelationMeaning.Callees -> source === request.subject
                    RelationMeaning.References,
                    RelationMeaning.Callers,
                    RelationMeaning.Implementations,
                    RelationMeaning.Inheritors,
                    RelationMeaning.Overrides,
                    RelationMeaning.TypeUses -> target === request.subject
                }
            if (!oriented) {
                return Refinement.Rejected(RelationFactFailure.SUBJECT_ORIENTATION_MISMATCH)
            }
            return Refinement.Refined(
                RelationFact(
                    subject = request.subject,
                    meaning = request.meaning,
                    source = source,
                    target = target,
                    occurrence = occurrence,
                    authority = request.subject.lease.identity,
                    provenance = provenance,
                    coverage = RelationFactCoverage.EXACT_COMPILER_CONFIRMED,
                )
            )
        }

        private val RELATION_FACT_ORDER =
            compareBy<RelationFact>(
                { it.meaning.canonicalOrder() },
                { it.source.fingerprint.value },
                { it.target.fingerprint.value },
                { it.occurrence.file.stableValue },
                { it.occurrence.range.startInclusive },
                { it.occurrence.range.endExclusive },
            )
    }
}

private fun RelationMeaning.canonicalOrder(): Int =
    when (this) {
        RelationMeaning.References -> 0
        RelationMeaning.Callers -> 1
        RelationMeaning.Callees -> 2
        RelationMeaning.Implementations -> 3
        RelationMeaning.Inheritors -> 4
        RelationMeaning.Overrides -> 5
        RelationMeaning.TypeUses -> 6
    }

internal fun StringBuilder.appendFactField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
