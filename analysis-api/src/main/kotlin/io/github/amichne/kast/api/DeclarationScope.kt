package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

/**
 * Captures the full PSI text range and optional source text of a declaration.
 *
 * [startOffset]/[endOffset] are character offsets from `PsiElement.textRange`.
 * [startLine]/[endLine] are 1-indexed line numbers.
 * [sourceText] is the full declaration text, nullable so callers can opt out of large payloads.
 */
@Serializable
data class DeclarationScope(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
    val sourceText: String? = null,
)
