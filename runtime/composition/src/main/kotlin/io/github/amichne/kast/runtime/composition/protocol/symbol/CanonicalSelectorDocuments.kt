package io.github.amichne.kast.runtime.composition.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface SelectorScopeDocumentFields {
    val sourceKinds: String
    val generatedSources: String
    val scope: String
    val scopeFile: String?
    val libraries: String?
}

/** One closed, versioned candidate payload; each variant carries only facts it has proved. */
@Serializable
internal sealed interface CandidateSelectorDocument {
    val root: String
    val generation: Long

    @Serializable
    @SerialName("declaration")
    data class Declaration(
        override val root: String,
        override val generation: Long,
        override val sourceKinds: String,
        override val generatedSources: String,
        override val scope: String,
        override val scopeFile: String? = null,
        override val libraries: String? = null,
        val kind: String,
        val name: String,
        val fileType: String,
        val file: String,
        val offset: Int,
    ) : CandidateSelectorDocument, SelectorScopeDocumentFields

    @Serializable
    @SerialName("file")
    data class File(
        override val root: String,
        override val generation: Long,
        val file: String,
    ) : CandidateSelectorDocument

    @Serializable
    @SerialName("range")
    data class Range(
        override val root: String,
        override val generation: Long,
        val file: String,
        val startInclusive: Int,
        val endExclusive: Int,
    ) : CandidateSelectorDocument
}

/** Fixed exact-selector token payload encoded by its compiler-generated serializer. */
@Serializable
internal data class ExactSelectorDocument(
    val root: String,
    val generation: Long,
    override val sourceKinds: String,
    override val generatedSources: String,
    override val scope: String,
    override val scopeFile: String? = null,
    override val libraries: String? = null,
    val fileType: String,
    val file: String,
    val start: Int,
    val end: Int,
    val name: String,
    val qualifiedIdentity: String?,
    val kind: String,
    val compilerSignature: String,
    val compilerIdentity: String,
    val fingerprint: String,
) : SelectorScopeDocumentFields
