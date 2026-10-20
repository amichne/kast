package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueryExcludedCompilerTargetWireDocument(
    val file: String,
    val range: SourceRangeWireDocument,
    val name: String,
    val kind: SymbolKindWireDocument,
    @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
)
