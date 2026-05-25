package io.github.amichne.kast.indexstore.api.metrics.symbolquery

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence

data class SymbolQueryFilters(
    val kinds: Set<String> = emptySet(),
    val visibility: Set<String> = emptySet(),
    val modulePath: String? = null,
    val sourceSet: String? = null,
    val fileGlob: String? = null,
    val packagePrefix: String? = null,
    val fqNamePrefix: String? = null,
)

enum class SymbolQueryGraphDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

data class SymbolQueryFieldMatch(
    val field: String,
    val term: String,
    val matchType: String,
    val evidence: String? = null,
)

data class SymbolQueryConstraint(
    val field: String,
    val operator: String,
    val value: List<String>,
    val source: String = "sqlite",
)

data class SymbolQueryDeclarationMatch(
    val fqId: Long,
    val fqName: String,
    val simpleName: String,
    val kind: String,
    val visibility: String,
    val prefixId: Int,
    val dirPath: String,
    val filename: String,
    val path: String,
    val declarationOffset: Int?,
    val modulePath: String?,
    val sourceSet: String?,
    val exactMatches: List<SymbolQueryFieldMatch>,
    val lexicalMatches: List<SymbolQueryFieldMatch>,
    val structuralConstraints: List<SymbolQueryConstraint>,
    val confidence: Confidence,
)

data class SymbolQueryGraphEdge(
    val originFqId: Long,
    val startFqId: Long,
    val resultFqId: Long,
    val depth: Int,
    val fromFqId: Long?,
    val fromFqName: String?,
    val edgeKind: String,
    val toFqId: Long,
    val toFqName: String,
    val sourceFile: String?,
    val sourceOffset: Int?,
)
