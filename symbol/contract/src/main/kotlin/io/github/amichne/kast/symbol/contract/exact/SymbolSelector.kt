package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val MAX_COMPILER_SYMBOL_IDENTITY_LENGTH = 4096
private const val SYMBOL_SELECTOR_FINGERPRINT_HEX_LENGTH = 64
private const val SELECTOR_HEX_RADIX = 16

enum class CompilerSymbolIdentityFailure {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
}

/** Bounded, detached identity projected from one compiler symbol. */
@JvmInline
value class CompilerSymbolIdentity private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<CompilerSymbolIdentity,
         * CompilerSymbolIdentityFailure>`.
         *
         * Establishes a non-blank, bounded serialized compiler identity without control
         * characters. [CompilerSymbolIdentityFailure] is the closed expected failure. Native
         * compiler adapters must canonicalize and hash raw compiler signatures before parsing
         * their serialized identity.
         */
        fun parse(
            raw: String,
        ): Refinement<CompilerSymbolIdentity, CompilerSymbolIdentityFailure> = when {
            raw.isBlank() -> Refinement.Rejected(CompilerSymbolIdentityFailure.BLANK)
            raw.length > MAX_COMPILER_SYMBOL_IDENTITY_LENGTH ->
                Refinement.Rejected(CompilerSymbolIdentityFailure.TOO_LONG)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(CompilerSymbolIdentityFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(CompilerSymbolIdentity(raw))
        }
    }
}

/** Closed compiler declaration families exposed by `symbol.inspect`. */
enum class CompilerSymbolKind {
    CLASSLIKE,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

enum class CompilerGroundedSymbolEvidenceFailure {
    INVALID_RANGE,
    INVALID_NAME,
    INVALID_QUALIFIED_IDENTITY,
    QUALIFIED_IDENTITY_MISMATCH,
    SIGNATURE_KIND_MISMATCH,
    COMPILER_IDENTITY_MISMATCH,
}

/** Detached evidence created only after one native declaration resolves to a compiler symbol. */
@ConsistentCopyVisibility
data class CompilerGroundedSymbolEvidence private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val name: SymbolDiscoveryCandidateName,
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity,
    val kind: CompilerSymbolKind,
    val signature: CanonicalCompilerSignature,
    val compilerIdentity: CompilerSymbolIdentity,
) {
    companion object {
        /**
         * Proof transition: `(SymbolDiscoveryFileIdentity, Int, Int, String, String?,
         * CompilerSymbolKind, CanonicalCompilerSignature) -> Refinement<
         * CompilerGroundedSymbolEvidence, CompilerGroundedSymbolEvidenceFailure>`.
         *
         * Establishes a detached exact file, non-empty range, bounded name, explicit qualified
         * identity state, closed compiler kind, structured compiler signature, and its derived
         * compiler identity.
         * [CompilerGroundedSymbolEvidenceFailure] is the closed expected failure. Raw PSI and
         * compiler values may be extracted only at the request-local native compiler boundary.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
            rawName: String,
            rawQualifiedIdentity: String?,
            kind: CompilerSymbolKind,
            signature: CanonicalCompilerSignature,
        ): Refinement<CompilerGroundedSymbolEvidence, CompilerGroundedSymbolEvidenceFailure> {
            val range = when (
                val parsed = ExactDeclarationTextRange.parse(
                    rawStartInclusive,
                    rawEndExclusive,
                )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CompilerGroundedSymbolEvidenceFailure.INVALID_RANGE,
                )
            }
            val name = when (val parsed = SymbolDiscoveryCandidateName.parse(rawName)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CompilerGroundedSymbolEvidenceFailure.INVALID_NAME,
                )
            }
            val qualifiedIdentity = when (
                val parsed = ExactDeclarationQualifiedIdentity.fromBoundary(rawQualifiedIdentity)
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CompilerGroundedSymbolEvidenceFailure.INVALID_QUALIFIED_IDENTITY,
                )
            }
            if (!signature.supports(kind)) {
                return Refinement.Rejected(
                    CompilerGroundedSymbolEvidenceFailure.SIGNATURE_KIND_MISMATCH,
                )
            }
            if (
                qualifiedIdentity !is ExactDeclarationQualifiedIdentity.Available ||
                qualifiedIdentity.value != signature.qualifiedIdentity.value
            ) {
                return Refinement.Rejected(
                    CompilerGroundedSymbolEvidenceFailure.QUALIFIED_IDENTITY_MISMATCH,
                )
            }
            return Refinement.Refined(
                CompilerGroundedSymbolEvidence(
                    file = file,
                    range = range,
                    name = name,
                    qualifiedIdentity = qualifiedIdentity,
                    kind = kind,
                    signature = signature,
                    compilerIdentity = CompilerSymbolIdentity.fromCanonicalSignature(signature),
                ),
            )
        }

        /**
         * Restores persisted compiler evidence only when the stored identity is the exact
         * projection of the retained canonical signature.
         */
        fun restoreBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
            rawName: String,
            rawQualifiedIdentity: String?,
            kind: CompilerSymbolKind,
            signature: CanonicalCompilerSignature,
            compilerIdentity: CompilerSymbolIdentity,
        ): Refinement<CompilerGroundedSymbolEvidence, CompilerGroundedSymbolEvidenceFailure> =
            when (
                val evidence = fromBoundary(
                    file = file,
                    rawStartInclusive = rawStartInclusive,
                    rawEndExclusive = rawEndExclusive,
                    rawName = rawName,
                    rawQualifiedIdentity = rawQualifiedIdentity,
                    kind = kind,
                    signature = signature,
                )
            ) {
                is Refinement.Rejected -> evidence
                is Refinement.Refined -> if (
                    evidence.value.compilerIdentity == compilerIdentity
                ) {
                    evidence
                } else {
                    Refinement.Rejected(
                        CompilerGroundedSymbolEvidenceFailure.COMPILER_IDENTITY_MISMATCH,
                    )
                }
            }
    }
}

enum class SymbolSelectorFingerprintFailure {
    INVALID_FORMAT,
}

@JvmInline
value class SymbolSelectorFingerprint private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<SymbolSelectorFingerprint,
         * SymbolSelectorFingerprintFailure>`.
         *
         * Establishes the canonical lowercase SHA-256 fingerprint form. The closed expected
         * failure is [SymbolSelectorFingerprintFailure]. Raw text may enter only at compiler issue
         * or protocol-token restoration boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<SymbolSelectorFingerprint, SymbolSelectorFingerprintFailure> =
            if (
                raw.length == SYMBOL_SELECTOR_FINGERPRINT_HEX_LENGTH &&
                raw.all { character -> character in '0'..'9' || character in 'a'..'f' }
            ) {
                Refinement.Refined(SymbolSelectorFingerprint(raw))
            } else {
                Refinement.Rejected(SymbolSelectorFingerprintFailure.INVALID_FORMAT)
            }
    }
}

enum class SymbolSelectorIssueFailure {
    FILE_MISMATCH,
    NAME_MISMATCH,
    START_OFFSET_MISMATCH,
    FINGERPRINT_MISMATCH,
}

/** Compiler-grounded exact symbol authority bound to one root, generation, scope, and declaration. */
class SymbolSelector private constructor(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val name: SymbolDiscoveryCandidateName,
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity,
    val kind: CompilerSymbolKind,
    val signature: CanonicalCompilerSignature,
    val compilerIdentity: CompilerSymbolIdentity,
    val fingerprint: SymbolSelectorFingerprint,
) {
    companion object {
        /**
         * Proof transition: `(SymbolDiscoverySelection, CompilerGroundedSymbolEvidence) ->
         * Refinement<SymbolSelector, SymbolSelectorIssueFailure>`.
         *
         * Establishes that compiler evidence resolves the selected file, name, and exact source
         * offset, then seals root, generation, complete scope, declaration location, and compiler
         * identity under one opaque fingerprint. [SymbolSelectorIssueFailure] is the closed
         * expected failure. Compiler evidence may enter only from the request-local native
         * selector adapter.
         */
        fun issue(
            selection: SymbolDiscoverySelection,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<SymbolSelector, SymbolSelectorIssueFailure> {
            if (evidence.file != selection.candidate.location.file) {
                return Refinement.Rejected(SymbolSelectorIssueFailure.FILE_MISMATCH)
            }
            if (evidence.name != selection.candidate.name) {
                return Refinement.Rejected(SymbolSelectorIssueFailure.NAME_MISMATCH)
            }
            val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
            if (evidence.range.startInclusive != location.offset.value) {
                return Refinement.Rejected(SymbolSelectorIssueFailure.START_OFFSET_MISMATCH)
            }
            return Refinement.Refined(issue(selection.lease, selection.scope, evidence))
        }

        /**
         * Proof transition: `(SemanticReadLease, SymbolSearchScope,
         * CompilerGroundedSymbolEvidence) -> SymbolSelector`.
         *
         * Issues exact selector authority from already compiler-grounded relation evidence. Raw
         * compiler values cannot enter this transition.
         */
        fun issue(
            lease: SemanticReadLease,
            scope: SymbolSearchScope,
            evidence: CompilerGroundedSymbolEvidence,
        ): SymbolSelector = SymbolSelector(
            lease = lease,
            scope = scope,
            file = evidence.file,
            range = evidence.range,
            name = evidence.name,
            qualifiedIdentity = evidence.qualifiedIdentity,
            kind = evidence.kind,
            signature = evidence.signature,
            compilerIdentity = evidence.compilerIdentity,
            fingerprint = symbolSelectorFingerprint(lease, scope, evidence),
        )

        /**
         * Proof transition: `(SemanticReadLease, SymbolSearchScope,
         * CompilerGroundedSymbolEvidence, SymbolSelectorFingerprint) ->
         * Refinement<SymbolSelector, SymbolSelectorIssueFailure>`.
         *
         * Restores exact selector authority only when every decoded fact reproduces the encoded
         * fingerprint. [SymbolSelectorIssueFailure] closes tampering and stale reconstruction.
         */
        fun restore(
            lease: SemanticReadLease,
            scope: SymbolSearchScope,
            evidence: CompilerGroundedSymbolEvidence,
            fingerprint: SymbolSelectorFingerprint,
        ): Refinement<SymbolSelector, SymbolSelectorIssueFailure> =
            if (symbolSelectorFingerprint(lease, scope, evidence) == fingerprint) {
                Refinement.Refined(issue(lease, scope, evidence))
            } else {
                Refinement.Rejected(SymbolSelectorIssueFailure.FINGERPRINT_MISMATCH)
            }
    }
}

enum class SymbolSelectorRevalidationFailure {
    DECLARATION_MOVED_OR_CHANGED,
}

/** Proof that current compiler evidence is identical to one issued exact selector. */
class RevalidatedSymbolSelector private constructor(
    val selector: SymbolSelector,
) {
    companion object {
        /**
         * Proof transition: `(SymbolSelector, CompilerGroundedSymbolEvidence) -> Refinement<
         * RevalidatedSymbolSelector, SymbolSelectorRevalidationFailure>`.
         *
         * Establishes exact fingerprint identity under the selector's original root, generation,
         * scope, location, and compiler identity. [SymbolSelectorRevalidationFailure] is the closed
         * expected failure. Compiler evidence may enter only from the request-local native
         * selector adapter.
         */
        fun validate(
            selector: SymbolSelector,
            evidence: CompilerGroundedSymbolEvidence,
        ): Refinement<RevalidatedSymbolSelector, SymbolSelectorRevalidationFailure> =
            if (
                symbolSelectorFingerprint(
                    selector.lease,
                    selector.scope,
                    evidence,
                ) == selector.fingerprint
            ) {
                Refinement.Refined(RevalidatedSymbolSelector(selector))
            } else {
                Refinement.Rejected(
                    SymbolSelectorRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
                )
            }
    }
}

/** Detached public description projected from one exact selector. */
@ConsistentCopyVisibility
data class SymbolDescription private constructor(
    val selector: SymbolSelector,
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val name: SymbolDiscoveryCandidateName,
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity,
    val kind: CompilerSymbolKind,
    val signature: CanonicalCompilerSignature,
    val compilerIdentity: CompilerSymbolIdentity,
) {
    companion object {
        /**
         * Proof transition: `SymbolSelector -> SymbolDescription`.
         *
         * Preserves the selector's root/generation authority and projects only detached compiler
         * evidence. No PSI, VFS, search scope, or compiler object crosses this contract boundary.
         */
        fun from(selector: SymbolSelector): SymbolDescription = SymbolDescription(
            selector = selector,
            file = selector.file,
            range = selector.range,
            name = selector.name,
            qualifiedIdentity = selector.qualifiedIdentity,
            kind = selector.kind,
            signature = selector.signature,
            compilerIdentity = selector.compilerIdentity,
        )
    }
}

private fun CanonicalCompilerSignature.supports(kind: CompilerSymbolKind): Boolean = when (this) {
    is CanonicalCompilerSignature.Function ->
        kind == CompilerSymbolKind.FUNCTION || kind == CompilerSymbolKind.CONSTRUCTOR
    is CanonicalCompilerSignature.Property -> kind == CompilerSymbolKind.PROPERTY
    is CanonicalCompilerSignature.TypeAlias -> kind == CompilerSymbolKind.TYPE_ALIAS
    is CanonicalCompilerSignature.ClassLike -> kind == CompilerSymbolKind.CLASSLIKE
}

private fun symbolSelectorFingerprint(
    lease: SemanticReadLease,
    scope: SymbolSearchScope,
    evidence: CompilerGroundedSymbolEvidence,
): SymbolSelectorFingerprint {
    val canonical = buildString {
        appendSelectorField(lease.workspaceRoot.value)
        appendSelectorField(lease.generation.value.toString())
        scope.appendSelectorFields(this)
        appendSelectorField(evidence.file.stableValue)
        appendSelectorField(evidence.range.startInclusive.toString())
        appendSelectorField(evidence.range.endExclusive.toString())
        appendSelectorField(evidence.name.value)
        appendSelectorField(evidence.kind.name)
        appendSelectorField(evidence.compilerIdentity.value)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    val raw = digest.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(SELECTOR_HEX_RADIX).padStart(2, '0')
    }
    return when (val parsed = SymbolSelectorFingerprint.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("SHA-256 projection is a canonical selector fingerprint")
    }
}

private fun SymbolSearchScope.appendSelectorFields(target: StringBuilder) {
    when (this) {
        is SymbolSearchScope.ExactFile -> {
            target.appendSelectorField("exact-file")
            target.appendSelectorField(file.value)
        }
        is SymbolSearchScope.Module -> {
            target.appendSelectorField("module")
            target.appendSelectorField(module.value)
        }
        is SymbolSearchScope.SourceSet -> {
            target.appendSelectorField("source-set")
            target.appendSelectorField(project.buildRoot.value)
            target.appendSelectorField(project.projectPath.value)
            target.appendSelectorField(sourceSet.value)
        }
        is SymbolSearchScope.GradleProject -> {
            target.appendSelectorField("gradle-project")
            target.appendSelectorField(project.buildRoot.value)
            target.appendSelectorField(project.projectPath.value)
        }
        is SymbolSearchScope.Workspace -> {
            target.appendSelectorField("workspace")
            target.appendSelectorField(libraries.name)
        }
    }
    target.appendSelectorField(sourceKinds.name)
    target.appendSelectorField(generatedSources.name)
}

private fun StringBuilder.appendSelectorField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
