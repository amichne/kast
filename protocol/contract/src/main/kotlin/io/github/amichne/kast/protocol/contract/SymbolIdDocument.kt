package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Canonical snapshot-local equality key. It is deliberately not a selector or a read capability. */
@JvmInline
value class SymbolIdDocument private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<SymbolIdDocument, SymbolIdDocumentFailure> =
            if (raw.matches(Regex("sym:[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]"))) Refinement.Refined(SymbolIdDocument(raw))
            else Refinement.Rejected(SymbolIdDocumentFailure.INVALID_STRUCTURE)
    }
}

enum class SymbolIdDocumentFailure {
    INVALID_STRUCTURE
}
