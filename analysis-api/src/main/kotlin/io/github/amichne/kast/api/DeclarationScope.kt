@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
    @DocField(description = "Zero-based character offset of the declaration start.")
    val startOffset: Int,
    @DocField(description = "Zero-based character offset one past the declaration end.")
    val endOffset: Int,
    @DocField(description = "One-based line number of the declaration start.")
    val startLine: Int,
    @DocField(description = "One-based line number of the declaration end.")
    val endLine: Int,
    @DocField(description = "Full source text of the declaration, omitted when not requested.")
    val sourceText: String? = null,
)
