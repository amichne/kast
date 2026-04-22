package io.github.amichne.kast.demo

/**
 * One row in the grep categorization table (Act 1).
 */
data class GrepCategory(
    val name: String,
    val count: Int,
    val example: String? = null,
)

/**
 * Complete result set for Act 1 — Text Search.
 */
data class GrepResult(
    val command: String,
    val totalHits: Int,
    val categories: List<GrepCategory>,
)

/**
 * The kind of reference found by symbol resolution.
 */
enum class ReferenceKind {
    CALL,
    OVERRIDE,
    TYPE_REF,
    IMPORT,
}

/**
 * One row in the resolution table (Act 2).
 */
data class ResolvedReference(
    val file: String,
    val line: Int,
    val kind: ReferenceKind,
    val resolvedType: String,
    val module: String,
)

/**
 * Complete result set for Act 2 — Symbol Resolution.
 */
data class ResolutionResult(
    val fqn: String,
    val declarationFile: String,
    val declarationLine: Int,
    val typeSignature: String,
    val refs: List<ResolvedReference>,
    val totalGrepHits: Int,
)

/**
 * A node in the caller graph tree (Act 3).
 *
 * This is a tree projection of a potentially cyclic caller graph.
 * The upstream traversal is responsible for cycle detection;
 * the renderer draws whatever tree it receives.
 */
data class CallerNode(
    val symbolName: String,
    val module: String,
    val children: List<CallerNode> = emptyList(),
)
