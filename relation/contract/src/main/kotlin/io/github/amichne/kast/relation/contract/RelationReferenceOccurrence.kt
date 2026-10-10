package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerDeclarationAddress
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.detachedIdentityBytes
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class RelationReferenceTargetFailure {
    DIFFERENT_COMPILER_IDENTITY,
    DIFFERENT_DECLARATION_FILE,
    DIFFERENT_DECLARATION_RANGE,
}

/** Equality proof retains resolved compiler facts and the selected exact declaration authority. */
class RelationConfirmedReferenceTarget
private constructor(
    val target: RelationEndpoint,
    val resolved: CompilerGroundedSymbolEvidence,
) {
    companion object {
        fun fromCompiler(
            target: RelationEndpoint,
            resolved: CompilerGroundedSymbolEvidence,
        ): Refinement<RelationConfirmedReferenceTarget, RelationReferenceTargetFailure> =
            when {
                target.compilerIdentity != resolved.compilerIdentity ->
                    Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_COMPILER_IDENTITY)
                target.file != resolved.file ->
                    Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_DECLARATION_FILE)
                target.range != resolved.range ->
                    Refinement.Rejected(RelationReferenceTargetFailure.DIFFERENT_DECLARATION_RANGE)
                else -> Refinement.Refined(RelationConfirmedReferenceTarget(target, resolved))
            }
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

    private fun detachedTextUnits(): Long =
        canonicalProjection().length.toLong() +
            target.detachedTextUnits() +
            when (val owner = ownership) {
                is RelationReferenceOwnership.DeclarationOwned -> owner.declaration.detachedTextUnits()
                is RelationReferenceOwnership.FileScoped,
                is RelationReferenceOwnership.Unavailable -> 0L
            }

    // Admission fixes every input. Repeated checkpoint and output accounting retain these numeric proofs.
    private val retainedByteCount =
        RelationByteCount.parse(
                REFERENCE_STRUCTURE_BYTES + REFERENCE_ACCOUNTING_BYTES + UTF16_UNIT_BYTES * detachedTextUnits()
            )
            .refinedInvariant()

    /** Includes endpoint proofs, scope and constraints; fingerprints alone do not account for retained authority. */
    val retainedBytes: Long
        get() = retainedByteCount.value

    private val projectedByteCount =
        RelationByteCount.parse(
                REFERENCE_DOCUMENT_BYTES +
                    canonicalProjection().projectedJsonTextBytes() +
                    2L * target.projectedSelectorTextBytes().base64Bytes() +
                    target.projectedDocumentTextBytes() +
                    (target.lease.workspaceRoot.value.projectedJsonTextBytes() +
                            authority.revisionKey.value.projectedJsonTextBytes() +
                            occurrence.file.stableValue.projectedJsonTextBytes())
                        .base64Bytes() +
                    when (val owner = ownership) {
                        is RelationReferenceOwnership.DeclarationOwned ->
                            owner.declaration.projectedSelectorTextBytes().base64Bytes() +
                                owner.declaration.projectedDocumentTextBytes()
                        is RelationReferenceOwnership.FileScoped,
                        is RelationReferenceOwnership.Unavailable -> 0L
                    }
            )
            .refinedInvariant()

    /**
     * Bounds one full occurrence row, including inline selectors. The target selector occurs twice (row reference and
     * target document), each owner selector once, and the occurrence range selector once. Compact handles fit within
     * this inline bound. Response-envelope and separately emitted observation charges belong to their own owners.
     */
    fun projectedUtf8Size(): Long = projectedByteCount.value

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

/** Every retained endpoint identity field remains in the selector bound, including hidden read authority. */
private fun RelationEndpoint.projectedSelectorTextBytes(): Long =
    listOf(
            lease.workspaceRoot.value,
            lease.identity.revisionKey.value,
            file.stableValue,
            name.value,
            qualifiedIdentity.projectedText(),
            signature.canonicalEncoding().value,
            fingerprint.value,
            compilerIdentity.value,
        )
        .sumOf { it.projectedJsonTextBytes() } +
        scope.projectedTextBytes() +
        constraints.fingerprintFields().sumOf { it.projectedJsonTextBytes() + CONSTRAINT_FIELD_BYTES }

/** Qualified identity also occurs in the structured signature; both appearances are charged. */
private fun RelationEndpoint.projectedDocumentTextBytes(): Long =
    file.stableValue.projectedJsonTextBytes() +
        name.value.projectedJsonTextBytes() +
        qualifiedIdentity.projectedText().projectedJsonTextBytes() +
        compilerIdentity.value.projectedJsonTextBytes() +
        signature.projectedDocumentTextBytes()

private fun CanonicalCompilerSignature.projectedDocumentTextBytes(): Long =
    when (val address = declarationAddress) {
        is CompilerDeclarationAddress.Qualified -> address.identity.value.projectedJsonTextBytes()
        is CompilerDeclarationAddress.Local ->
            address.address.file.stableValue.projectedJsonTextBytes() +
                address.address.ownerIdentity.value.projectedJsonTextBytes() +
                LOCAL_ADDRESS_DOCUMENT_BYTES +
                address.address.lexicalOwners.size * LOCAL_LEXICAL_OWNER_DOCUMENT_BYTES
    } +
        when (this) {
            is CanonicalCompilerCallableSignature ->
                receiver.projectedTextBytes() +
                    contextReceivers.sumOf { it.value.projectedJsonTextBytes() + JSON_LIST_ITEM_BYTES } +
                    valueParameters.sumOf { it.value.projectedJsonTextBytes() + JSON_LIST_ITEM_BYTES } +
                    if (this is CanonicalCompilerSignature.LocalFunction) returnType.value.projectedJsonTextBytes()
                    else 0L
            is CanonicalCompilerSignature.Property ->
                receiver.projectedTextBytes() +
                    contextReceivers.sumOf { it.value.projectedJsonTextBytes() + JSON_LIST_ITEM_BYTES } +
                    returnType.value.projectedJsonTextBytes()
            is CanonicalCompilerSignature.LocalProperty -> returnType.value.projectedJsonTextBytes()
            is CanonicalCompilerSignature.AnonymousObject ->
                supertypes.sumOf { it.value.projectedJsonTextBytes() + JSON_LIST_ITEM_BYTES }
            is CanonicalCompilerSignature.TypeAlias,
            is CanonicalCompilerSignature.ClassLike -> 0L
        }

private fun CanonicalCompilerReceiver.projectedTextBytes(): Long =
    when (this) {
        CanonicalCompilerReceiver.Absent -> 0L
        is CanonicalCompilerReceiver.Present -> type.value.projectedJsonTextBytes()
    }

private fun ExactDeclarationQualifiedIdentity.projectedText(): String =
    when (this) {
        is ExactDeclarationQualifiedIdentity.Available -> value
        ExactDeclarationQualifiedIdentity.Unavailable -> ""
    }

private fun SymbolSearchScope.projectedTextBytes(): Long =
    when (this) {
        is SymbolSearchScope.ExactFile -> file.value.projectedJsonTextBytes()
        is SymbolSearchScope.Module -> module.value.projectedJsonTextBytes()
        is SymbolSearchScope.GradleProject ->
            project.buildRoot.value.projectedJsonTextBytes() + project.projectPath.value.projectedJsonTextBytes()
        is SymbolSearchScope.SourceSet ->
            project.buildRoot.value.projectedJsonTextBytes() +
                project.projectPath.value.projectedJsonTextBytes() +
                sourceSet.value.projectedJsonTextBytes()
        is SymbolSearchScope.Workspace -> 0L
    }

/** Base64url needs at most four bytes per three JSON payload bytes, including the last incomplete group. */
private fun Long.base64Bytes(): Long =
    BASE64_ENCODED_GROUP_BYTES * ((this + BASE64_INPUT_GROUP_BYTES - 1L) / BASE64_INPUT_GROUP_BYTES)

/** Non-ASCII and control units retain the six-byte bound, including UTF-16 surrogate pairs. */
private fun String.projectedJsonTextBytes(): Long = sumOf { character ->
    when {
        character == '"' || character == '\\' -> 2L
        character in ' '..'~' -> 1L
        else -> JSON_ESCAPE_UNIT_BYTES
    }
}

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
/** Two retained, unboxed byte-count proofs; the conservative base structure charge remains intact. */
private const val REFERENCE_ACCOUNTING_BYTES = 16L
/** Row fields, enum labels, scalar numbers, token framing and base64-expanded fixed selector JSON fields. */
private const val REFERENCE_DOCUMENT_BYTES = 4_096L
private const val UTF16_UNIT_BYTES = 2L
private const val JSON_ESCAPE_UNIT_BYTES = 6L
private const val CONSTRAINT_FIELD_BYTES = 24L
private const val JSON_LIST_ITEM_BYTES = 3L
private const val BASE64_INPUT_GROUP_BYTES = 3L
private const val BASE64_ENCODED_GROUP_BYTES = 4L

/** Detached local address fields and bounded source-range/owner framing. */
private const val LOCAL_ADDRESS_DOCUMENT_BYTES = 512L
private const val LOCAL_LEXICAL_OWNER_DOCUMENT_BYTES = 128L
