package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement

/** A compiler-owned declaration has either a qualified identity or an exact local address. */
sealed interface CompilerDeclarationAddress {
    data class Qualified(val identity: CanonicalCompilerQualifiedIdentity) : CompilerDeclarationAddress

    data class Local(val address: LocalDeclarationAddress) : CompilerDeclarationAddress
}

enum class LocalDeclarationAddressFailure {
    INVALID_FILE,
    INVALID_OWNER_IDENTITY,
    INVALID_OWNER_RANGE,
    INVALID_LEXICAL_OWNER,
    OWNER_DEPTH_EXCEEDED,
}

enum class LocalDeclarationKind {
    FUNCTION,
    PROPERTY,
    ANONYMOUS_OBJECT,
}

/** Presentation label only; compiler ownership and the admitted address establish identity. */
const val ANONYMOUS_OBJECT_DECLARATION_NAME = "<anonymous-object>"

enum class LocalPropertyMutability {
    VAL,
    VAR,
}

/** Finite native projection failures, kept separate from pure detached-address validation. */
sealed interface LocalDeclarationProjectionFailure {
    data object WorkLimitReached : LocalDeclarationProjectionFailure

    data object CompilerTypeError : LocalDeclarationProjectionFailure

    data object CompilerTypeUnsupported : LocalDeclarationProjectionFailure

    data object UnsupportedDeclaration : LocalDeclarationProjectionFailure

    data object SignatureUnavailable : LocalDeclarationProjectionFailure

    data class InvalidAddress(val cause: LocalDeclarationAddressFailure) : LocalDeclarationProjectionFailure

    data object SourceUnavailable : LocalDeclarationProjectionFailure

    data object OwnerUnavailable : LocalDeclarationProjectionFailure

    data object CompilerOwnerUnavailable : LocalDeclarationProjectionFailure

    data object LexicalAncestryUnavailable : LocalDeclarationProjectionFailure

    data object OwnerDepthExceeded : LocalDeclarationProjectionFailure
}

/** Exact snapshot-local address. Names do not establish equality; the selector retains semantic authority. */
@ConsistentCopyVisibility
data class LocalDeclarationAddress
private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val kind: LocalDeclarationKind,
    val range: ExactDeclarationTextRange,
    val ownerIdentity: CompilerSymbolIdentity,
    val ownerRange: ExactDeclarationTextRange,
    val lexicalOwners: List<ExactDeclarationTextRange>,
) {
    companion object {
        const val MAX_OWNER_DEPTH = 32

        /** Restores detached syntax only; live scope/ownership is re-admitted at compiler lookup. */
        fun restoreFile(
            rawKind: String,
            rawValue: String,
        ): Refinement<SymbolDiscoveryFileIdentity, LocalDeclarationAddressFailure> {
            val file =
                when (rawKind) {
                    "workspace" -> {
                        val path =
                            try {
                                java.nio.file.Path.of(rawValue)
                            } catch (_: java.nio.file.InvalidPathException) {
                                return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                            }
                        val parent =
                            path.parent ?: return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                        val root =
                            when (
                                val parsed =
                                    io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot.fromCanonicalPath(
                                        parent
                                    )
                            ) {
                                is Refinement.Refined -> parsed.value
                                is Refinement.Rejected ->
                                    return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                            }
                        when (val parsed = CanonicalWorkspaceFilePath.fromCanonicalPath(root, path)) {
                            is Refinement.Refined -> SymbolDiscoveryFileIdentity.Workspace(parsed.value)
                            is Refinement.Rejected ->
                                return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                        }
                    }
                    "external" ->
                        when (val parsed = DetachedVirtualFileUrl.parse(rawValue)) {
                            is Refinement.Refined -> SymbolDiscoveryFileIdentity.External(parsed.value)
                            is Refinement.Rejected ->
                                return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                        }
                    else -> return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_FILE)
                }
            return Refinement.Refined(file)
        }

        fun create(
            file: SymbolDiscoveryFileIdentity,
            kind: LocalDeclarationKind,
            range: ExactDeclarationTextRange,
            ownerIdentity: CompilerSymbolIdentity,
            ownerRange: ExactDeclarationTextRange,
            lexicalOwners: List<ExactDeclarationTextRange>,
        ): Refinement<LocalDeclarationAddress, LocalDeclarationAddressFailure> {
            if (!ownerIdentity.hasCanonicalDigest())
                return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_OWNER_IDENTITY)
            if (lexicalOwners.size > MAX_OWNER_DEPTH)
                return Refinement.Rejected(LocalDeclarationAddressFailure.OWNER_DEPTH_EXCEEDED)
            if (!ownerRange.strictlyContains(range))
                return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_OWNER_RANGE)
            var enclosing = ownerRange
            for (lexical in lexicalOwners) {
                if (!enclosing.strictlyContains(lexical) || !lexical.strictlyContains(range))
                    return Refinement.Rejected(LocalDeclarationAddressFailure.INVALID_LEXICAL_OWNER)
                enclosing = lexical
            }
            return Refinement.Refined(
                LocalDeclarationAddress(
                    file,
                    kind,
                    range,
                    ownerIdentity,
                    ownerRange,
                    java.util.List.copyOf(lexicalOwners),
                )
            )
        }
    }
}

private const val SHA256_HEX_DIGEST_LENGTH = 64

private fun CompilerSymbolIdentity.hasCanonicalDigest(): Boolean =
    value.startsWith(CANONICAL_IDENTITY_PREFIX) && value.drop(CANONICAL_IDENTITY_PREFIX.length).isCanonicalHexDigest()

private fun String.isCanonicalHexDigest(): Boolean = length == SHA256_HEX_DIGEST_LENGTH && all(::isLowercaseHexDigit)

private fun isLowercaseHexDigit(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f'

private fun ExactDeclarationTextRange.strictlyContains(other: ExactDeclarationTextRange): Boolean =
    startInclusive <= other.startInclusive && endExclusive >= other.endExclusive && this != other

/** Callable facts shared by qualified and compiler-confirmed local functions. */
sealed interface CanonicalCompilerCallableSignature : CanonicalCompilerSignature {
    val receiver: CanonicalCompilerReceiver
    val contextReceivers: List<CanonicalCompilerType>
    val valueParameters: List<CanonicalCompilerType>
    val typeParameterCount: CanonicalTypeParameterCount
}
