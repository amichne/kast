package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.query.SemanticGraphPath

data class ParsedSemanticGraphQuery(
    val filePaths: List<SemanticGraphPath>,
    val removedFilePaths: List<SemanticGraphPath>,
) {
    val pageSize: PositiveInt = PositiveInt(1)
}
