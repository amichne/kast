package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.detachedIdentityBytes
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class RelationReferenceTargetFailure {
    DIFFERENT_COMPILER_IDENTITY
}

/** Equality proof retains the exact target authority, rather than reducing confirmation to a flag. */
class RelationConfirmedReferenceTarget private constructor(val target: RelationEndpoint) {
    companion object {
        fun fromCompiler(
            target: RelationEndpoint,
            resolved: CompilerSymbolIdentity,
        ): Refinement<RelationConfirmedReferenceTarget, RelationReferenceTargetFailure> =
            if (target.compilerIdentity == resolved) Refinement.Refined(RelationConfirmedReferenceTarget(target))
            else Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_COMPILER_IDENTITY)
    }
}

/** Source context is independent of target identity and declaration ownership. */
enum class RelationReferenceContext {
    IMPORT,
    ALIASED_IMPORT,
    TYPE,
    CODE,
    FILE_ANNOTATION,
}

enum class RelationOwnershipUnavailableCause {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_DECLARATION,
}

sealed interface RelationReferenceOwnership {
    data class DeclarationOwned(val declaration: RelationEndpoint.Resolved) : RelationReferenceOwnership

    data class FileScoped(val context: RelationReferenceContext) : RelationReferenceOwnership

    data class Unavailable(val cause: RelationOwnershipUnavailableCause) : RelationReferenceOwnership
}

enum class RelationReferenceOccurrenceFailure {
    NON_REFERENCE_MEANING,
    TARGET_MISMATCH,
    OWNER_AUTHORITY_MISMATCH,
    OWNER_SCOPE_MISMATCH,
    OWNER_LOCATION_MISMATCH,
    OCCURRENCE_SCOPE_MISMATCH,
    FILE_CONTEXT_MISMATCH,
}

/** A target-confirmed source occurrence. Only a proven declaration owner can project a graph edge. */
@ConsistentCopyVisibility
data class RelationReferenceOccurrence
private constructor(
    val target: RelationEndpoint,
    val meaning: RelationMeaning,
    val occurrence: RelationOccurrence,
    val context: RelationReferenceContext,
    val ownership: RelationReferenceOwnership,
    val authority: SemanticReadIdentity,
    val provenance: RelationProvenance,
) : Comparable<RelationReferenceOccurrence> {
    val coverage = RelationFactCoverage.EXACT_COMPILER_CONFIRMED

    private val detachedTextUnits: Long
        get() =
            canonicalProjection().length.toLong() +
                target.detachedTextUnits() +
                when (val owner = ownership) {
                    is RelationReferenceOwnership.DeclarationOwned -> owner.declaration.detachedTextUnits()
                    is RelationReferenceOwnership.FileScoped,
                    is RelationReferenceOwnership.Unavailable -> 0L
                }

    /** Includes endpoint proofs, scope and constraints; fingerprints alone do not account for retained authority. */
    val retainedBytes: Long
        get() = REFERENCE_STRUCTURE_BYTES + UTF16_UNIT_BYTES * detachedTextUnits

    /** Worst-case JSON escaping plus the bounded fixed fields of two symbol documents and occurrence metadata. */
    fun projectedUtf8Size(): Long = REFERENCE_DOCUMENT_BYTES + JSON_ESCAPE_UNIT_BYTES * detachedTextUnits

    override fun compareTo(other: RelationReferenceOccurrence): Int =
        canonicalProjection().compareTo(other.canonicalProjection())

    fun canonicalProjection(): String = buildString {
        append(target.fingerprint.value).append('\u0000')
        append(meaning).append('\u0000')
        append(occurrence.file.stableValue).append('\u0000')
        append(occurrence.range.startInclusive).append(':').append(occurrence.range.endExclusive).append('\u0000')
        append(context.name).append('\u0000')
        when (val owner = ownership) {
            is RelationReferenceOwnership.DeclarationOwned ->
                append("declaration:").append(owner.declaration.fingerprint.value)
            is RelationReferenceOwnership.FileScoped -> append("file:").append(owner.context.name)
            is RelationReferenceOwnership.Unavailable -> append("unavailable:").append(owner.cause.name)
        }
        append('\u0000').append(authority.revisionKey.value).append('\u0000').append(provenance.name)
    }

    fun declarationFact(request: RelationRequest): Refinement<RelationFact, RelationReferenceProjectionFailure> =
        when (val owner = ownership) {
            is RelationReferenceOwnership.DeclarationOwned ->
                when (val fact = RelationFact.create(request, owner.declaration, target, occurrence, provenance)) {
                    is Refinement.Refined -> Refinement.Refined(fact.value)
                    is Refinement.Rejected -> Refinement.Rejected(RelationReferenceProjectionFailure.REQUEST_MISMATCH)
                }
            is RelationReferenceOwnership.FileScoped ->
                Refinement.Rejected(RelationReferenceProjectionFailure.FILE_SCOPED)
            is RelationReferenceOwnership.Unavailable ->
                Refinement.Rejected(RelationReferenceProjectionFailure.OWNERSHIP_UNAVAILABLE)
        }

    companion object {
        /** The native boundary calls this only after compiler identity confirms the exact subject target. */
        fun confirmed(
            request: RelationRequest,
            target: RelationConfirmedReferenceTarget,
            occurrence: RelationOccurrence,
            context: RelationReferenceContext,
            ownership: RelationReferenceOwnership,
            provenance: RelationProvenance,
        ): Refinement<RelationReferenceOccurrence, RelationReferenceOccurrenceFailure> {
            if (target.target !== request.subject) {
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.TARGET_MISMATCH)
            }
            if (request.meaning != RelationMeaning.References && request.meaning != RelationMeaning.TypeUses) {
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.NON_REFERENCE_MEANING)
            }
            if (!request.admitsOccurrenceFile(occurrence))
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.OCCURRENCE_SCOPE_MISMATCH)
            when (val admitted = request.admitOwnership(occurrence, context, ownership)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                RelationReferenceOccurrence(
                    request.subject,
                    request.meaning,
                    occurrence,
                    context,
                    ownership,
                    request.subject.lease.identity,
                    provenance,
                )
            )
        }
    }
}

enum class RelationReferenceProjectionFailure {
    FILE_SCOPED,
    OWNERSHIP_UNAVAILABLE,
    REQUEST_MISMATCH,
}

internal fun RelationEndpoint.detachedTextUnits(): Long =
    lease.workspaceRoot.value.length.toLong() +
        lease.identity.revisionKey.value.length +
        file.stableValue.length +
        name.value.length +
        qualifiedIdentity.detachedTextUnits() +
        signature.canonicalEncoding().value.length +
        fingerprint.value.length +
        compilerIdentity.value.length +
        scope.detachedIdentityBytes() +
        constraints.fingerprintFields().sumOf { it.length.toLong() + CONSTRAINT_FIELD_BYTES }

private fun io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.detachedTextUnits(): Long =
    when (this) {
        is io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available -> value.length.toLong()
        io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Unavailable -> 0L
    }

private fun RelationRequest.admitsOccurrenceFile(occurrence: RelationOccurrence): Boolean {
    val requestedScope = searchScope
    return when (val file = occurrence.file) {
        is io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace ->
            java.nio.file.Path.of(file.path.value)
                .startsWith(java.nio.file.Path.of(subject.lease.workspaceRoot.value)) &&
                (requestedScope !is io.github.amichne.kast.symbol.contract.SymbolSearchScope.ExactFile ||
                    file.path == requestedScope.file)
        is io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.External ->
            (requestedScope as? io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace)?.libraries ==
                io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.INCLUDE
    }
}

private fun RelationRequest.admitOwnership(
    occurrence: RelationOccurrence,
    context: RelationReferenceContext,
    ownership: RelationReferenceOwnership,
): Refinement<Unit, RelationReferenceOccurrenceFailure> {
    when (ownership) {
        is RelationReferenceOwnership.DeclarationOwned -> {
            if (ownership.declaration.lease != subject.lease) {
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.OWNER_AUTHORITY_MISMATCH)
            }
            if (ownership.declaration.file != occurrence.file) {
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.OWNER_LOCATION_MISMATCH)
            }
            if (!admitsEndpoint(ownership.declaration)) {
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.OWNER_SCOPE_MISMATCH)
            }
        }
        is RelationReferenceOwnership.FileScoped ->
            if (
                ownership.context != context ||
                    context !in
                        setOf(
                            RelationReferenceContext.IMPORT,
                            RelationReferenceContext.ALIASED_IMPORT,
                            RelationReferenceContext.FILE_ANNOTATION,
                        )
            )
                return Refinement.Rejected(RelationReferenceOccurrenceFailure.FILE_CONTEXT_MISMATCH)
        is RelationReferenceOwnership.Unavailable -> Unit
    }
    return Refinement.Refined(Unit)
}

private const val REFERENCE_STRUCTURE_BYTES = 1_024L
private const val REFERENCE_DOCUMENT_BYTES = 2_048L
private const val UTF16_UNIT_BYTES = 2L
private const val JSON_ESCAPE_UNIT_BYTES = 6L
private const val CONSTRAINT_FIELD_BYTES = 24L
