package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestIngress
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import kotlinx.serialization.json.encodeToJsonElement

internal fun PublicToolReadSource.lowerSource(): Refinement<PublicToolCanonical, PublicToolInputFailure> {
    val anchor =
        when (val admitted = sourceAnchor()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val selectedText =
        when (val admitted = sourceText()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val entityLimit =
        when (val admitted = sourceEntityLimit()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val byteLimit =
        when (val admitted = sourceByteLimit()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val base = SourceReadRequest(anchor, sourceRegion(), sourceEntities(), selectedText)
    val canonical =
        base.copy(
            entityLimit = entityLimit ?: base.entityLimit,
            textByteLimit = byteLimit ?: base.textByteLimit,
            page =
                when (val selected = page) {
                    null,
                    PublicToolSourcePageFirst -> SourceReadPageDocument.First
                    is PublicToolSourcePageContinue -> SourceReadPageDocument.Continue(selected.continuation)
                },
            executionBudget = (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
            format = SourceReadFormatDocument.COMPACT,
        )
    val encoded =
        try {
            PublicToolContract.json.encodeToJsonElement(SourceReadRequest.serializer(), canonical)
        } catch (_: kotlinx.serialization.SerializationException) {
            return sourceFieldRejected(SourceRequestPath.FILTERS, SourceRequestRule.NONEMPTY_UNIQUE_VALUES)
        }
    return when (val admitted = SourceRequestIngress.decode(encoded, PublicToolContract.json)) {
        is Refinement.Refined -> Refinement.Refined(PublicToolCanonical.Source(admitted.value))
        is Refinement.Rejected -> sourceRejected(admitted.failure)
    }
}

private fun PublicToolReadSource.sourceAnchor(): Refinement<SourceReadAnchorDocument.Symbol, PublicToolInputFailure> {
    val anchor =
        when (val admitted = SourceReadAnchorDocument.admit(symbolRef)) {
            is Refinement.Refined ->
                admitted.value as? SourceReadAnchorDocument.Symbol
                    ?: return sourceRejected(
                        SourceReadFailureDetail.ReferenceRejected(
                            SourceReferenceRole.SYMBOL,
                            SourceReferenceFailure.WRONG_FAMILY,
                        )
                    )
            is Refinement.Rejected ->
                return sourceRejected(
                    SourceReadFailureDetail.ReferenceRejected(
                        SourceReferenceRole.SYMBOL,
                        when (admitted.failure) {
                            SourceReadAnchorDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                            SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE -> SourceReferenceFailure.MALFORMED
                            SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING ->
                                SourceReferenceFailure.INVALID_PAYLOAD_ENCODING
                            SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH ->
                                SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH
                        },
                    )
                )
        }
    return Refinement.Refined(anchor)
}

private fun PublicToolReadSource.sourceRegion(): SourceRegionSelectionDocument =
    when (region) {
        null,
        PublicToolRegion.DECLARATION -> SourceRegionSelectionDocument.Anchor
        PublicToolRegion.FILE -> SourceRegionSelectionDocument.File
        PublicToolRegion.CLASS_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS)
        PublicToolRegion.CALLABLE_BODY -> SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
    }

private fun PublicToolReadSource.sourceText(): Refinement<SourceTextRequestDocument, PublicToolInputFailure> {
    val selectedText =
        when (val selected = text) {
            null,
            is PublicToolSourceTextComplete -> SourceTextRequestDocument.Complete
            PublicToolSourceTextNone -> SourceTextRequestDocument.None
            is PublicToolSourceTextWindow -> {
                val before =
                    when (val parsed = SourceLineCountDocument.parse(selected.beforeLines ?: 0)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return sourceFieldRejected(SourceRequestPath.BEFORE_LINES, SourceRequestRule.LINE_COUNT)
                    }
                val after =
                    when (val parsed = SourceLineCountDocument.parse(selected.afterLines ?: 0)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return sourceFieldRejected(SourceRequestPath.AFTER_LINES, SourceRequestRule.LINE_COUNT)
                    }
                SourceTextRequestDocument.Window(before, after)
            }
        }
    return Refinement.Refined(selectedText)
}

private fun PublicToolReadSource.sourceEntities(): SourceEntitySelectionDocument =
    when (val selected = entities) {
        null,
        PublicToolSourceEntitiesNone -> SourceEntitySelectionDocument.None
        is PublicToolSourceEntitiesMatching ->
            SourceEntitySelectionDocument.Matching(
                when (selected.containment) {
                    null,
                    PublicToolContainment.DIRECT -> SourceContainmentDocument.DIRECT
                    PublicToolContainment.DESCENDANTS -> SourceContainmentDocument.DESCENDANTS
                },
                selected.filters.values.map(PublicToolSourceFilter::lowerSourceFilter),
            )
    }

private fun PublicToolReadSource.sourceEntityLimit(): Refinement<SourceEntityLimitDocument?, PublicToolInputFailure> {
    val limit =
        (entities as? PublicToolSourceEntitiesMatching)?.limit?.let { raw ->
            when (val parsed = SourceEntityLimitDocument.parse(raw)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return sourceFieldRejected(SourceRequestPath.ENTITY_LIMIT, SourceRequestRule.ENTITY_COUNT)
            }
        }
    return Refinement.Refined(limit)
}

private fun PublicToolReadSource.sourceByteLimit(): Refinement<SourceTextByteLimitDocument?, PublicToolInputFailure> {
    val bytes =
        when (val selected = text) {
            is PublicToolSourceTextComplete -> selected.maxBytes
            is PublicToolSourceTextWindow -> selected.maxBytes
            null,
            PublicToolSourceTextNone -> null
        }?.let { raw ->
            when (val parsed = SourceTextByteLimitDocument.parse(raw.toLong())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return sourceFieldRejected(SourceRequestPath.TEXT_BYTE_LIMIT, SourceRequestRule.BYTE_COUNT)
            }
        }
    return Refinement.Refined(bytes)
}

private fun sourceRejected(cause: SourceReadCause): Refinement.Rejected<PublicToolInputFailure> =
    Refinement.Rejected(PublicToolInputFailure.Source(cause))

private fun sourceFieldRejected(
    path: SourceRequestPath,
    rule: SourceRequestRule,
): Refinement.Rejected<PublicToolInputFailure> =
    sourceRejected(SourceReadFailureDetail.RequestRejected(SourceRequestField(path), rule))

private fun PublicToolSourceFilter.lowerSourceFilter(): SourceEntityFilterDocument =
    when (this) {
        is PublicToolSourceFilterDeclarations ->
            SourceEntityFilterDocument.Declarations(
                kinds.values.map { kind ->
                    when (kind) {
                        PublicToolKinds.CLASSLIKE -> SourceDeclarationKindDocument.CLASSLIKE
                        PublicToolKinds.CONSTRUCTOR -> SourceDeclarationKindDocument.CONSTRUCTOR
                        PublicToolKinds.FUNCTION -> SourceDeclarationKindDocument.FUNCTION
                        PublicToolKinds.PROPERTY -> SourceDeclarationKindDocument.PROPERTY
                        PublicToolKinds.TYPE_ALIAS -> SourceDeclarationKindDocument.TYPE_ALIAS
                    }
                },
                visibility =
                    visibilities?.let { selected ->
                        SourceVisibilitySelectionDocument.Exact(
                            selected.values.map { visibility ->
                                when (visibility) {
                                    PublicToolVisibilities.PUBLIC -> SourceDeclarationVisibilityDocument.PUBLIC
                                    PublicToolVisibilities.PROTECTED -> SourceDeclarationVisibilityDocument.PROTECTED
                                    PublicToolVisibilities.INTERNAL -> SourceDeclarationVisibilityDocument.INTERNAL
                                    PublicToolVisibilities.PRIVATE -> SourceDeclarationVisibilityDocument.PRIVATE
                                    PublicToolVisibilities.LOCAL -> SourceDeclarationVisibilityDocument.LOCAL
                                }
                            }
                        )
                    } ?: SourceVisibilitySelectionDocument.Any,
            )
        PublicToolSourceFilterParameters -> SourceEntityFilterDocument.Parameters
        PublicToolSourceFilterCalls -> SourceEntityFilterDocument.Calls
        PublicToolSourceFilterReferences -> SourceEntityFilterDocument.References
    }
