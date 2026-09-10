package io.github.amichne.kast.query.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface SelectorScopeDocumentFields {
    val sourceKinds: String
    val generatedSources: String
    val scope: String
    val scopeFile: String?
    val libraries: String?
    val constraints: SelectorConstraintsDocument?
}

/** One closed, versioned candidate payload; each variant carries only facts it has proved. */
@Serializable
internal sealed interface CandidateSelectorDocument {
    val root: String
    val generation: Long?
    val live: LiveSelectorAuthorityDocument?

    @Serializable
    @SerialName("declaration")
    data class Declaration(
        override val root: String,
        override val generation: Long? = null,
        override val live: LiveSelectorAuthorityDocument? = null,
        override val sourceKinds: String,
        override val generatedSources: String,
        override val scope: String,
        override val scopeFile: String? = null,
        override val libraries: String? = null,
        override val constraints: SelectorConstraintsDocument? = null,
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
        override val generation: Long? = null,
        override val live: LiveSelectorAuthorityDocument? = null,
        val file: String,
        val readScope: SelectorReadScopeDocument? = null,
    ) : CandidateSelectorDocument

    @Serializable
    @SerialName("range")
    data class Range(
        override val root: String,
        override val generation: Long? = null,
        override val live: LiveSelectorAuthorityDocument? = null,
        val file: String,
        val startInclusive: Int,
        val endExclusive: Int,
        val readScope: SelectorReadScopeDocument? = null,
    ) : CandidateSelectorDocument
}

/** Fixed exact-selector token payload encoded by its compiler-generated serializer. */
@Serializable
internal data class ExactSelectorDocument(
    val root: String,
    val generation: Long? = null,
    val live: LiveSelectorAuthorityDocument? = null,
    override val sourceKinds: String,
    override val generatedSources: String,
    override val scope: String,
    override val scopeFile: String? = null,
    override val libraries: String? = null,
    override val constraints: SelectorConstraintsDocument? = null,
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

/** Detached revision facts; no decoding constructor creates live read authority. */
@Serializable
internal data class LiveSelectorAuthorityDocument(
    val host: String,
    val epoch: Long,
    val contentView: String,
    val version: Int,
)

@Serializable
internal data class SelectorConstraintPathDocument(val value: String, val containment: String)

@Serializable
internal data class SelectorConstraintsDocument(
    val directory: SelectorConstraintPathDocument? = null,
    val packageName: SelectorConstraintPathDocument? = null,
    val declarationKinds: List<String>? = null,
    val sourceSets: List<String>? = null,
)

/** Scope proof retained by file and text candidates; absent only for historical exact-file tokens. */
@Serializable
internal data class SelectorReadScopeDocument(
    override val sourceKinds: String,
    override val generatedSources: String,
    override val scope: String,
    override val scopeFile: String? = null,
    override val libraries: String? = null,
    override val constraints: SelectorConstraintsDocument? = null,
) : SelectorScopeDocumentFields
