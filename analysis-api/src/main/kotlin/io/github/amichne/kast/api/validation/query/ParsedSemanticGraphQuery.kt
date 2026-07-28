package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.result.SemanticGraphGeneration

data class ParsedSemanticGraphQuery(
    val filePaths: List<SemanticGraphPath>,
    val removedFilePaths: List<SemanticGraphPath>,
    val expectedGeneration: SemanticGraphGeneration?,
)
