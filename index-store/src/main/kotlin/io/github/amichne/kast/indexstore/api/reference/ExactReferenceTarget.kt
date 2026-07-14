package io.github.amichne.kast.indexstore.api.reference

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath

data class ExactReferenceTarget(
    val fqName: String,
    val declarationFile: NormalizedPath,
    val declarationStartOffset: NonNegativeInt,
) {
    init {
        require(fqName.isNotBlank()) { "Reference target FQ name must not be blank" }
    }
}
