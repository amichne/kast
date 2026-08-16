package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val RELATION_ENDPOINT_FINGERPRINT_LENGTH = 64

@JvmInline
value class RelationEndpointFingerprint internal constructor(
    val value: String,
) {
    init {
        require(
            value.length == RELATION_ENDPOINT_FINGERPRINT_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' },
        )
    }
}

enum class RelationEndpointResolutionFailure {
    FILE_OUTSIDE_EXACT_SCOPE,
}

/** Exact compiler-grounded endpoint detached from every live IntelliJ and K2 object. */
sealed interface RelationEndpoint {
    val lease: SemanticReadLease
    val scope: SymbolSearchScope
    val file: SymbolDiscoveryFileIdentity
    val range: ExactDeclarationTextRange
    val name: SymbolDiscoveryCandidateName
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity
    val kind: CompilerSymbolKind
    val fingerprint: RelationEndpointFingerprint

    @ConsistentCopyVisibility
    data class Subject internal constructor(
        val selector: SymbolSelector,
    ) : RelationEndpoint {
        override val lease: SemanticReadLease = selector.lease
        override val scope: SymbolSearchScope = selector.scope
        override val file: SymbolDiscoveryFileIdentity = selector.file
        override val range: ExactDeclarationTextRange = selector.range
        override val name: SymbolDiscoveryCandidateName = selector.name
        override val qualifiedIdentity: ExactDeclarationQualifiedIdentity = selector.qualifiedIdentity
        override val kind: CompilerSymbolKind = selector.kind
        override val fingerprint: RelationEndpointFingerprint =
            RelationEndpointFingerprint(selector.fingerprint.value)
    }

    @ConsistentCopyVisibility
    data class Resolved private constructor(
        override val lease: SemanticReadLease,
        override val scope: SymbolSearchScope,
        val evidence: CompilerGroundedSymbolEvidence,
        override val fingerprint: RelationEndpointFingerprint,
    ) : RelationEndpoint {
        override val file: SymbolDiscoveryFileIdentity = evidence.file
        override val range: ExactDeclarationTextRange = evidence.range
        override val name: SymbolDiscoveryCandidateName = evidence.name
        override val qualifiedIdentity: ExactDeclarationQualifiedIdentity = evidence.qualifiedIdentity
        override val kind: CompilerSymbolKind = evidence.kind

        companion object {
            internal fun create(
                lease: SemanticReadLease,
                scope: SymbolSearchScope,
                evidence: CompilerGroundedSymbolEvidence,
            ): Resolved = Resolved(
                lease,
                scope,
                evidence,
                relationEndpointFingerprint(lease, scope, evidence),
            )
        }
    }

    companion object {
        /**
         * Proof transition: `SymbolSelector -> RelationEndpoint.Subject`.
         *
         * Preserves the selector's exact compiler-grounded root, generation, scope, declaration,
         * and opaque identity without extracting a primitive subject.
         */
        fun subject(selector: SymbolSelector): Subject = Subject(selector)

        /**
         * Proof transition: `(SemanticReadLease, SymbolSearchScope,
         * CompilerGroundedSymbolEvidence) -> Refinement<RelationEndpoint.Resolved,
         * RelationEndpointResolutionFailure>`.
         *
         * Establishes a detached compiler-grounded endpoint bound to the subject lease and scope.
         * [RelationEndpointResolutionFailure] is the closed expected failure. Raw PSI/K2 values
         * may enter only through compiler evidence at the request-local native adapter boundary.
         */
        fun resolve(
            lease: SemanticReadLease,
            scope: SymbolSearchScope,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<Resolved, RelationEndpointResolutionFailure> {
            if (
                scope is SymbolSearchScope.ExactFile &&
                evidence.file.stableValue != scope.file.value
            ) {
                return Refinement.Rejected(
                    RelationEndpointResolutionFailure.FILE_OUTSIDE_EXACT_SCOPE,
                )
            }
            return Refinement.Refined(Resolved.create(lease, scope, evidence))
        }
    }
}

enum class RelationOccurrenceFailure {
    INVALID_RANGE,
}

@ConsistentCopyVisibility
data class RelationOccurrence private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        /**
         * Proof transition: `(SymbolDiscoveryFileIdentity, Int, Int) -> Refinement<
         * RelationOccurrence, RelationOccurrenceFailure>`.
         *
         * Establishes an exact detached file and non-empty absolute source range.
         * [RelationOccurrenceFailure] is the closed expected failure. Raw offsets may enter only
         * from a request-local K2-confirmed PSI occurrence.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<RelationOccurrence, RelationOccurrenceFailure> = when (
            val range = ExactDeclarationTextRange.parse(rawStartInclusive, rawEndExclusive)
        ) {
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
    EXACT_COMPILER_CONFIRMED,
}

enum class RelationFactFailure {
    ENDPOINT_LEASE_MISMATCH,
    ENDPOINT_SCOPE_MISMATCH,
    SUBJECT_ORIENTATION_MISMATCH,
}

@ConsistentCopyVisibility
data class RelationFact private constructor(
    val subject: SymbolSelector,
    val meaning: RelationMeaning,
    val source: RelationEndpoint,
    val target: RelationEndpoint,
    val occurrence: RelationOccurrence,
    val generation: EvidenceGeneration,
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
        appendFactField(generation.value.toString())
        appendFactField(provenance.name)
        appendFactField(coverage.name)
    }

    companion object {
        /**
         * Proof transition: `(RelationRequest, RelationEndpoint, RelationEndpoint,
         * RelationOccurrence, RelationProvenance) -> Refinement<RelationFact,
         * RelationFactFailure>`.
         *
         * Establishes one exact compiler-confirmed edge with closed orientation, subject lease and
         * scope, exact occurrence, generation, and provenance. [RelationFactFailure] is the closed
         * expected failure. Native values may enter only through already-detached endpoints and
         * occurrence evidence.
         */
        fun create(
            request: RelationRequest,
            source: RelationEndpoint,
            target: RelationEndpoint,
            occurrence: RelationOccurrence,
            provenance: RelationProvenance,
        ): Refinement<RelationFact, RelationFactFailure> {
            if (source.lease != request.selector.lease || target.lease != request.selector.lease) {
                return Refinement.Rejected(RelationFactFailure.ENDPOINT_LEASE_MISMATCH)
            }
            if (source.scope != request.selector.scope || target.scope != request.selector.scope) {
                return Refinement.Rejected(RelationFactFailure.ENDPOINT_SCOPE_MISMATCH)
            }
            val oriented = when (request.meaning) {
                RelationMeaning.Callees -> source.isSubject(request.selector)
                RelationMeaning.References,
                RelationMeaning.Callers,
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides,
                RelationMeaning.TypeUses,
                    -> target.isSubject(request.selector)
            }
            if (!oriented) {
                return Refinement.Rejected(RelationFactFailure.SUBJECT_ORIENTATION_MISMATCH)
            }
            return Refinement.Refined(
                RelationFact(
                    subject = request.selector,
                    meaning = request.meaning,
                    source = source,
                    target = target,
                    occurrence = occurrence,
                    generation = request.selector.lease.generation,
                    provenance = provenance,
                    coverage = RelationFactCoverage.EXACT_COMPILER_CONFIRMED,
                ),
            )
        }

        private val RELATION_FACT_ORDER = compareBy<RelationFact>(
            { it.meaning.canonicalOrder() },
            { it.source.fingerprint.value },
            { it.target.fingerprint.value },
            { it.occurrence.file.stableValue },
            { it.occurrence.range.startInclusive },
            { it.occurrence.range.endExclusive },
        )
    }
}

private fun RelationEndpoint.isSubject(selector: SymbolSelector): Boolean =
    this is RelationEndpoint.Subject && this.selector === selector

private fun relationEndpointFingerprint(
    lease: SemanticReadLease,
    scope: SymbolSearchScope,
    evidence: CompilerGroundedSymbolEvidence,
): RelationEndpointFingerprint {
    val canonical = buildString {
        appendFactField(lease.workspaceRoot.value)
        appendFactField(lease.generation.value.toString())
        scope.appendEndpointFields(this)
        appendFactField(evidence.file.stableValue)
        appendFactField(evidence.range.startInclusive.toString())
        appendFactField(evidence.range.endExclusive.toString())
        appendFactField(evidence.name.value)
        appendFactField(evidence.kind.name)
        appendFactField(evidence.compilerIdentity.value)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return RelationEndpointFingerprint(
        digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        },
    )
}

private fun SymbolSearchScope.appendEndpointFields(target: StringBuilder) {
    when (this) {
        is SymbolSearchScope.ExactFile -> {
            target.appendFactField("exact-file")
            target.appendFactField(file.value)
        }
        is SymbolSearchScope.Module -> {
            target.appendFactField("module")
            target.appendFactField(module.value)
        }
        is SymbolSearchScope.SourceSet -> {
            target.appendFactField("source-set")
            target.appendFactField(project.buildRoot.value)
            target.appendFactField(project.projectPath.value)
            target.appendFactField(sourceSet.value)
        }
        is SymbolSearchScope.GradleProject -> {
            target.appendFactField("gradle-project")
            target.appendFactField(project.buildRoot.value)
            target.appendFactField(project.projectPath.value)
        }
        is SymbolSearchScope.Workspace -> {
            target.appendFactField("workspace")
            target.appendFactField(libraries.name)
        }
    }
    target.appendFactField(sourceKinds.name)
    target.appendFactField(generatedSources.name)
}

private fun RelationMeaning.canonicalOrder(): Int = when (this) {
    RelationMeaning.References -> 0
    RelationMeaning.Callers -> 1
    RelationMeaning.Callees -> 2
    RelationMeaning.Implementations -> 3
    RelationMeaning.Inheritors -> 4
    RelationMeaning.Overrides -> 5
    RelationMeaning.TypeUses -> 6
}

private fun StringBuilder.appendFactField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
