package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EnclosingRegionKind
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.LineCount
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor as DomainSourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest as DomainSourceReadRequest
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal sealed interface SourceRequestAdmission {
    data class Admitted(val request: DomainSourceReadRequest) : SourceRequestAdmission

    data class Rejected(val reason: SourceReadCause) : SourceRequestAdmission
}

internal fun SourceReadRequest.admit(
    authority: QueryReferenceAuthority,
    current: SemanticReadAuthority,
    budget: SourceProtocolBudget,
): SourceRequestAdmission {
    val domainAnchor =
        when (val admitted = anchor.admit(authority, current)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return SourceRequestAdmission.Rejected(admitted.failure)
        }
    val domainRegion = region.domain()
    val domainEntities =
        when (val admitted = entities.domain()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return SourceRequestAdmission.Rejected(admitted.failure)
        }
    val domainText =
        when (val admitted = text.domain()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return SourceRequestAdmission.Rejected(admitted.failure)
        }
    val domainEntityLimit =
        SourceEntityLimit.parse(minOf(entityLimit.value, budget.resources.resultLimit.value)).refinedOrNull()
            ?: return SourceRequestAdmission.Rejected(
                SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
            )
    val domainTextByteLimit =
        SourceTextByteLimit.parse(minOf(textByteLimit.value, budget.maximumTextBytes.value)).refinedOrNull()
            ?: return SourceRequestAdmission.Rejected(
                SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
            )
    val domainPage =
        when (val admitted = page.domain()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return SourceRequestAdmission.Rejected(admitted.failure)
        }
    return SourceRequestAdmission.Admitted(
        DomainSourceReadRequest(
            domainAnchor,
            domainRegion,
            domainEntities,
            domainText,
            domainEntityLimit,
            domainTextByteLimit,
            domainPage,
            budget.resources,
            format.domainOutputIdentity(),
        )
    )
}

private fun SourceEntitySelectionDocument.domain(): Refinement<EntitySelection, SourceReadCause> =
    when (this) {
        SourceEntitySelectionDocument.None -> Refinement.Refined(EntitySelection.None)
        is SourceEntitySelectionDocument.Matching -> {
            if (filters.size !in 1..MAX_SOURCE_FILTER_COUNT)
                return sourceRequestRejected(
                    io.github.amichne.kast.protocol.contract.SourceRequestPath.FILTERS,
                    io.github.amichne.kast.protocol.contract.SourceRequestRule.FILTER_COUNT,
                )
            val mapped = mutableListOf<EntityFilter>()
            for ((index, filter) in filters.withIndex()) {
                when (val admitted = filter.domain(index)) {
                    is Refinement.Refined -> mapped.add(admitted.value)
                    is Refinement.Rejected -> return admitted
                }
            }
            when (
                val admitted =
                    EntitySelection.matching(
                        containment.domain(),
                        mapped,
                    )
            ) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected ->
                    sourceRequestRejected(
                        io.github.amichne.kast.protocol.contract.SourceRequestPath.FILTERS,
                        io.github.amichne.kast.protocol.contract.SourceRequestRule.UNIQUE_FILTER_FAMILIES,
                    )
            }
        }
    }

private fun SourceEntityFilterDocument.domain(index: Int): Refinement<EntityFilter, SourceReadCause> =
    when (this) {
        is SourceEntityFilterDocument.Declarations -> {
            if (kinds.isEmpty() || kinds.size != kinds.distinct().size)
                return sourceRequestRejected(
                    io.github.amichne.kast.protocol.contract.SourceRequestPath.DECLARATION_KINDS,
                    io.github.amichne.kast.protocol.contract.SourceRequestRule.NONEMPTY_UNIQUE_VALUES,
                    index,
                )
            val domainKinds =
                when (val admitted = DeclarationKindSelection.from(kinds.map { it.domain() }.toSet())) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                        )
                }
            val domainVisibility =
                when (val admitted = visibility.domain(index)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            Refinement.Refined(EntityFilter.Declarations(domainKinds, domainVisibility))
        }
        SourceEntityFilterDocument.Parameters -> Refinement.Refined(EntityFilter.Parameters)
        SourceEntityFilterDocument.Calls -> Refinement.Refined(EntityFilter.Calls)
        SourceEntityFilterDocument.References -> Refinement.Refined(EntityFilter.References)
    }

private fun sourceRequestRejected(
    path: io.github.amichne.kast.protocol.contract.SourceRequestPath,
    rule: io.github.amichne.kast.protocol.contract.SourceRequestRule,
    index: Int? = null,
): Refinement.Rejected<SourceReadCause> {
    val position = index?.let {
        when (val parsed = io.github.amichne.kast.protocol.contract.SourceFilterIndex.parse(it)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                )
        }
    }
    return Refinement.Rejected(
        SourceReadFailureDetail.RequestRejected(
            io.github.amichne.kast.protocol.contract.SourceRequestField(path, position),
            rule,
        )
    )
}

private fun SourceDeclarationKindDocument.domain(): DeclarationKind =
    when (this) {
        SourceDeclarationKindDocument.CLASSLIKE -> DeclarationKind.CLASSLIKE
        SourceDeclarationKindDocument.CONSTRUCTOR -> DeclarationKind.CONSTRUCTOR
        SourceDeclarationKindDocument.FUNCTION -> DeclarationKind.FUNCTION
        SourceDeclarationKindDocument.PROPERTY -> DeclarationKind.PROPERTY
        SourceDeclarationKindDocument.TYPE_ALIAS -> DeclarationKind.TYPE_ALIAS
    }

private fun SourceDeclarationVisibilityDocument.domain(): DeclarationVisibility =
    when (this) {
        SourceDeclarationVisibilityDocument.PUBLIC -> DeclarationVisibility.PUBLIC
        SourceDeclarationVisibilityDocument.PROTECTED -> DeclarationVisibility.PROTECTED
        SourceDeclarationVisibilityDocument.INTERNAL -> DeclarationVisibility.INTERNAL
        SourceDeclarationVisibilityDocument.PRIVATE -> DeclarationVisibility.PRIVATE
        SourceDeclarationVisibilityDocument.LOCAL -> DeclarationVisibility.LOCAL
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun SourceReadAnchorDocument.admit(
    authority: QueryReferenceAuthority,
    current: SemanticReadAuthority,
): Refinement<DomainSourceReadAnchor, SourceReadCause> =
    when (val requested = this) {
        is SourceReadAnchorDocument.Candidate ->
            when (val lookup = authority.restoreCandidate(requested.selector, current)) {
                is CanonicalSelectorDecoding.Decoded ->
                    Refinement.Refined(DomainSourceReadAnchor.Candidate(lookup.value))
                is CanonicalSelectorDecoding.Rejected ->
                    return Refinement.Rejected(
                        SourceReadFailureDetail.ReferenceRejected(
                            SourceReferenceRole.CANDIDATE,
                            lookup.failure.sourceFailure(),
                        )
                    )
            }
        is SourceReadAnchorDocument.Symbol ->
            when (val lookup = authority.restoreExact(requested.selector, current)) {
                is CanonicalSelectorDecoding.Decoded -> Refinement.Refined(DomainSourceReadAnchor.Symbol(lookup.value))
                is CanonicalSelectorDecoding.Rejected ->
                    return Refinement.Rejected(
                        SourceReadFailureDetail.ReferenceRejected(
                            SourceReferenceRole.SYMBOL,
                            lookup.failure.sourceFailure(),
                        )
                    )
            }
        is SourceReadAnchorDocument.Source -> {
            val token =
                when (val parsed = SourceSelectorToken.parse(requested.selector.value)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            SourceReadFailureDetail.ReferenceRejected(
                                SourceReferenceRole.SOURCE,
                                parsed.failure.sourceFailure(),
                            )
                        )
                }
            val selector =
                when (val restored = authority.restoreSource(token, current)) {
                    is Refinement.Refined -> restored.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            SourceReadFailureDetail.ReferenceRejected(
                                SourceReferenceRole.SOURCE,
                                restored.failure.sourceFailure(),
                            )
                        )
                }
            Refinement.Refined(DomainSourceReadAnchor.Source(selector))
        }
    }

private fun SourceRegionSelectionDocument.domain(): RegionSelection =
    when (val requested = this) {
        SourceRegionSelectionDocument.Anchor -> RegionSelection.Anchor
        is SourceRegionSelectionDocument.Body ->
            RegionSelection.Body(
                when (requested.kind) {
                    SourceBodyKindDocument.CALLABLE -> BodyKind.CALLABLE
                    SourceBodyKindDocument.CLASS -> BodyKind.CLASS
                }
            )
        SourceRegionSelectionDocument.File -> RegionSelection.File
        is SourceRegionSelectionDocument.Enclosing ->
            RegionSelection.Enclosing(
                when (requested.kind) {
                    SourceEnclosingRegionKindDocument.DECLARATION -> EnclosingRegionKind.DECLARATION
                    SourceEnclosingRegionKindDocument.CALLABLE_BODY -> EnclosingRegionKind.CALLABLE_BODY
                    SourceEnclosingRegionKindDocument.CLASS_BODY -> EnclosingRegionKind.CLASS_BODY
                }
            )
    }

private fun SourceTextRequestDocument.domain(): Refinement<TextProjection, SourceReadCause> =
    when (val requested = this) {
        SourceTextRequestDocument.Complete -> Refinement.Refined(TextProjection.Complete)
        SourceTextRequestDocument.None -> Refinement.Refined(TextProjection.None)
        is SourceTextRequestDocument.Window -> {
            val before =
                LineCount.parse(requested.beforeLines.value).refinedOrNull()
                    ?: return Refinement.Rejected(
                        SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                    )
            val after =
                LineCount.parse(requested.afterLines.value).refinedOrNull()
                    ?: return Refinement.Rejected(
                        SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                    )
            Refinement.Refined(TextProjection.window(before, after))
        }
    }

private const val MAX_SOURCE_FILTER_COUNT = 4

private fun SourceContainmentDocument.domain(): Containment =
    when (this) {
        SourceContainmentDocument.DIRECT -> Containment.DIRECT
        SourceContainmentDocument.DESCENDANTS -> Containment.DESCENDANTS
    }

private fun SourceVisibilitySelectionDocument.domain(index: Int): Refinement<VisibilitySelection, SourceReadCause> =
    when (val requested = this) {
        SourceVisibilitySelectionDocument.Any -> Refinement.Refined(VisibilitySelection.Any)
        is SourceVisibilitySelectionDocument.Exact -> {
            if (requested.values.isEmpty() || requested.values.size != requested.values.distinct().size)
                return sourceRequestRejected(
                    io.github.amichne.kast.protocol.contract.SourceRequestPath.VISIBILITY_VALUES,
                    io.github.amichne.kast.protocol.contract.SourceRequestRule.NONEMPTY_UNIQUE_VALUES,
                    index,
                )
            when (val admitted = VisibilitySelection.exact(requested.values.map { it.domain() }.toSet())) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                    )
            }
        }
    }

private fun SourceReadPageDocument.domain(): Refinement<SourceReadPage, SourceReadCause> =
    when (val requested = this) {
        SourceReadPageDocument.First -> Refinement.Refined(SourceReadPage.First)
        is SourceReadPageDocument.Continue ->
            Refinement.Refined(
                SourceReadPage.Continue(
                    SourceReadContinuation.parse(requested.continuation.value).refinedOrNull()
                        ?: return Refinement.Rejected(
                            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
                        )
                )
            )
    }
