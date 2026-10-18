package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWordFailure

/** Backtick identifiers use scanWords, whose index omits words longer than 100 UTF-16 characters. */
@JvmInline
internal value class IntellijIndexedDeclarationName private constructor(val word: SymbolDiscoveryWord) {
    companion object {
        fun parse(raw: String): Refinement<IntellijIndexedDeclarationName, SymbolDiscoveryWordFailure> {
            if (raw.length > MAX_INDEXED_NAME_LENGTH) return Refinement.Rejected(SymbolDiscoveryWordFailure.TOO_LONG)
            if (raw == "Companion") return Refinement.Rejected(SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR)
            return when (val parsed = SymbolDiscoveryWord.parse(raw)) {
                is Refinement.Refined -> Refinement.Refined(IntellijIndexedDeclarationName(parsed.value))
                is Refinement.Rejected -> parsed
            }
        }
    }
}

private const val MAX_INDEXED_NAME_LENGTH = 100
