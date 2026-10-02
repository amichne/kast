package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Grammar supported by the case-sensitive indexed word ingress; literal and regex queries require another contract. */
private const val MAX_INDEXED_WORD_LENGTH = 256

enum class SymbolDiscoveryWordFailure {
    BLANK,
    TOO_LONG,
    UNSUPPORTED_GRAMMAR,
}

@JvmInline
value class SymbolDiscoveryWord private constructor(val value: String) {
    companion object {
        private val grammar = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun parse(raw: String): Refinement<SymbolDiscoveryWord, SymbolDiscoveryWordFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(SymbolDiscoveryWordFailure.BLANK)
                raw.length > MAX_INDEXED_WORD_LENGTH -> Refinement.Rejected(SymbolDiscoveryWordFailure.TOO_LONG)
                !grammar.matches(raw) -> Refinement.Rejected(SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR)
                else -> Refinement.Refined(SymbolDiscoveryWord(raw))
            }
    }
}

enum class SymbolTextMatchFailure {
    INVALID_RANGE,
    OWNER_RANGE_MISMATCH,
    INVALID_CONTEXT,
    CONTEXT_RANGE_MISMATCH,
    WORD_MISMATCH,
    INVALID_LINE,
}

/** Detached lexical evidence; the enclosing declaration still requires exact compiler refinement. */
@ConsistentCopyVisibility
data class SymbolTextMatch
private constructor(
    val word: SymbolDiscoveryWord,
    val lease: SemanticReadAuthority,
    val file: CanonicalWorkspaceFilePath,
    val range: SymbolDiscoverySourceRange,
    val declarationRange: SymbolDiscoverySourceRange,
    val context: String,
    val contextRange: SymbolDiscoverySourceRange,
    val line: Int,
) {
    internal fun belongsTo(authority: SemanticReadAuthority, location: SymbolDiscoveryCandidateLocation): Boolean =
        when (location) {
            is SymbolDiscoveryCandidateLocation.Declaration ->
                lease == authority &&
                    location.file == SymbolDiscoveryFileIdentity.Workspace(file) &&
                    declarationRange.startInclusive == location.offset
            is SymbolDiscoveryCandidateLocation.File,
            is SymbolDiscoveryCandidateLocation.Text -> false
        }

    companion object {
        const val MAX_CONTEXT_LENGTH = 512

        /** Refines one verified UTF16 occurrence and its bounded line context under the owner's authority. */
        fun fromBoundary(
            word: SymbolDiscoveryWord,
            lease: SemanticReadAuthority,
            file: CanonicalWorkspaceFilePath,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
            rawDeclarationStartInclusive: Int,
            rawDeclarationEndExclusive: Int,
            rawContext: String,
            rawContextStartInclusive: Int,
            rawLine: Int,
        ): Refinement<SymbolTextMatch, SymbolTextMatchFailure> {
            val range =
                when (val parsed = SymbolDiscoverySourceRange.parse(rawStartInclusive, rawEndExclusive)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(SymbolTextMatchFailure.INVALID_RANGE)
                }
            val owner =
                when (
                    val parsed =
                        SymbolDiscoverySourceRange.parse(rawDeclarationStartInclusive, rawDeclarationEndExclusive)
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(SymbolTextMatchFailure.OWNER_RANGE_MISMATCH)
                }
            if (owner.startInclusive.value > rawStartInclusive || owner.endExclusive.value < rawEndExclusive)
                return Refinement.Rejected(SymbolTextMatchFailure.OWNER_RANGE_MISMATCH)
            val contextRange =
                when (val admitted = admitContext(rawContext, rawContextStartInclusive, rawLine)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            if (rawStartInclusive < rawContextStartInclusive || rawEndExclusive > contextRange.endExclusive.value)
                return Refinement.Rejected(SymbolTextMatchFailure.CONTEXT_RANGE_MISMATCH)
            if (!contextMatchesWord(rawContext, rawContextStartInclusive, range, word))
                return Refinement.Rejected(SymbolTextMatchFailure.WORD_MISMATCH)
            return Refinement.Refined(
                SymbolTextMatch(word, lease, file, range, owner, rawContext, contextRange, rawLine)
            )
        }

        private fun admitContext(
            rawContext: String,
            start: Int,
            line: Int,
        ): Refinement<SymbolDiscoverySourceRange, SymbolTextMatchFailure> {
            val invalidCharacters = rawContext.any { it.isISOControl() && it != '\t' }
            if (rawContext.isEmpty() || rawContext.length > MAX_CONTEXT_LENGTH || invalidCharacters)
                return Refinement.Rejected(SymbolTextMatchFailure.INVALID_CONTEXT)
            if (line < 1) return Refinement.Rejected(SymbolTextMatchFailure.INVALID_LINE)
            if (start > Int.MAX_VALUE - rawContext.length)
                return Refinement.Rejected(SymbolTextMatchFailure.CONTEXT_RANGE_MISMATCH)
            return when (val parsed = SymbolDiscoverySourceRange.parse(start, start + rawContext.length)) {
                is Refinement.Refined -> parsed
                is Refinement.Rejected -> Refinement.Rejected(SymbolTextMatchFailure.CONTEXT_RANGE_MISMATCH)
            }
        }

        private fun contextMatchesWord(
            context: String,
            contextStart: Int,
            range: SymbolDiscoverySourceRange,
            word: SymbolDiscoveryWord,
        ): Boolean =
            range.endExclusive.value - range.startInclusive.value == word.value.length &&
                context.substring(range.startInclusive.value - contextStart, range.endExclusive.value - contextStart) ==
                    word.value
    }
}
