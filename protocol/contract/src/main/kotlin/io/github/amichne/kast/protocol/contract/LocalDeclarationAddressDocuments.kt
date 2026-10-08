package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind

/** The file alternative is retained because it participates in canonical local identity. */
sealed interface LocalDeclarationFileDocument {
    val value: ProtocolText

    data class Workspace(override val value: ProtocolText) : LocalDeclarationFileDocument

    data class External(override val value: ProtocolText) : LocalDeclarationFileDocument
}

enum class LocalDeclarationKindDocument {
    FUNCTION,
    PROPERTY,
}

enum class LocalDeclarationAddressDocumentFailure {
    INVALID_FILE,
    INVALID_OWNER_IDENTITY,
    INVALID_OWNER_RANGE,
    INVALID_LEXICAL_OWNER,
    OWNER_DEPTH_EXCEEDED,
}

/** Snapshot-local source and compiler owner proof, validated by the canonical address owner. */
@ConsistentCopyVisibility
data class LocalDeclarationAddressDocument
private constructor(
    val file: LocalDeclarationFileDocument,
    val kind: LocalDeclarationKindDocument,
    val range: SourceRangeDocument,
    val ownerIdentity: ProtocolText,
    val ownerRange: SourceRangeDocument,
    val lexicalOwners: BoundedProtocolList<SourceRangeDocument>,
    private val admittedAddress: LocalDeclarationAddress,
) {
    companion object {
        fun create(
            file: LocalDeclarationFileDocument,
            kind: LocalDeclarationKindDocument,
            range: SourceRangeDocument,
            ownerIdentity: ProtocolText,
            ownerRange: SourceRangeDocument,
            lexicalOwners: BoundedProtocolList<SourceRangeDocument>,
        ): Refinement<LocalDeclarationAddressDocument, LocalDeclarationAddressDocumentFailure> {
            val canonicalFile =
                when (
                    val parsed =
                        when (file) {
                            is LocalDeclarationFileDocument.Workspace ->
                                LocalDeclarationAddress.restoreFile("workspace", file.value.value)
                            is LocalDeclarationFileDocument.External ->
                                LocalDeclarationAddress.restoreFile("external", file.value.value)
                        }
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(parsed.failure.toDocumentFailure())
                }
            val owner =
                when (val parsed = CompilerSymbolIdentity.parse(ownerIdentity.value)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LocalDeclarationAddressDocumentFailure.INVALID_OWNER_IDENTITY)
                }
            val address =
                when (
                    val parsed =
                        LocalDeclarationAddress.create(
                            canonicalFile,
                            when (kind) {
                                LocalDeclarationKindDocument.FUNCTION -> LocalDeclarationKind.FUNCTION
                                LocalDeclarationKindDocument.PROPERTY -> LocalDeclarationKind.PROPERTY
                            },
                            range.canonicalRange(),
                            owner,
                            ownerRange.canonicalRange(),
                            lexicalOwners.values.map { it.canonicalRange() },
                        )
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(parsed.failure.toDocumentFailure())
                }
            return Refinement.Refined(
                LocalDeclarationAddressDocument(file, kind, range, ownerIdentity, ownerRange, lexicalOwners, address)
            )
        }
    }

    internal fun canonicalAddress(): LocalDeclarationAddress = admittedAddress
}

private fun LocalDeclarationAddressFailure.toDocumentFailure(): LocalDeclarationAddressDocumentFailure =
    when (this) {
        LocalDeclarationAddressFailure.INVALID_FILE -> LocalDeclarationAddressDocumentFailure.INVALID_FILE
        LocalDeclarationAddressFailure.INVALID_OWNER_IDENTITY ->
            LocalDeclarationAddressDocumentFailure.INVALID_OWNER_IDENTITY
        LocalDeclarationAddressFailure.INVALID_OWNER_RANGE -> LocalDeclarationAddressDocumentFailure.INVALID_OWNER_RANGE
        LocalDeclarationAddressFailure.INVALID_LEXICAL_OWNER ->
            LocalDeclarationAddressDocumentFailure.INVALID_LEXICAL_OWNER
        LocalDeclarationAddressFailure.OWNER_DEPTH_EXCEEDED ->
            LocalDeclarationAddressDocumentFailure.OWNER_DEPTH_EXCEEDED
    }

enum class LocalPropertyMutabilityDocument {
    VAL,
    VAR,
}

private fun SourceRangeDocument.canonicalRange(): ExactDeclarationTextRange =
    when (val parsed = ExactDeclarationTextRange.parse(startInclusive.value, endExclusive.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("Admitted source range lost its invariant")
    }
