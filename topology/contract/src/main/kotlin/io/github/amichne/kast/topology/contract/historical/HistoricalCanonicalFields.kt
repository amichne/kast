package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets

/** Encoded fields preserve every admitted Unicode scalar without replacement or normalization. */
internal class HistoricalCanonicalFields private constructor(internal val utf8: List<ByteArray>) {
    companion object {
        fun parse(fields: List<String>): Refinement<HistoricalCanonicalFields, HistoricalValueFailure> {
            if (fields.any { !wellFormedHistoricalUnicode(it) })
                return Refinement.Rejected(HistoricalValueFailure.INVALID_TEXT)
            return Refinement.Refined(HistoricalCanonicalFields(fields.map { it.toByteArray(StandardCharsets.UTF_8) }))
        }
    }
}

internal fun wellFormedHistoricalUnicode(raw: String): Boolean {
    var index = 0
    while (index < raw.length) {
        val character = raw[index++]
        if (character.isHighSurrogate()) {
            if (index == raw.length || !raw[index].isLowSurrogate()) return false
            index++
        } else if (character.isLowSurrogate()) return false
    }
    return true
}
