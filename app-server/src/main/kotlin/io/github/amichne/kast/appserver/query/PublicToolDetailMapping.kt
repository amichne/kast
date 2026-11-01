package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget

/** Detail admission keeps result identity and its sole cursor before canonical lowering. */
internal fun PublicToolReadResultAction.admitDetailSelection(): Refinement<Unit, PublicToolInputFailure> {
    val selected = detail
    return when {
        selected == null -> Refinement.Refined(Unit)
        selected.result != result ->
            detailRejected(PublicToolParameter.DETAIL_RESULT, PublicToolRule.SAME_RETAINED_RESULT)
        cursor != null || evidenceCursor != null ->
            detailRejected(PublicToolParameter.DETAIL_CURSOR, PublicToolRule.DETAIL_CURSOR_CONTROL)
        selected.kind.detailTarget() != selected.target ->
            detailRejected(PublicToolParameter.DETAIL_KIND, PublicToolRule.DETAIL_TARGET_KIND)
        else -> Refinement.Refined(Unit)
    }
}

private fun QueryEntryDetailKind.detailTarget(): QueryEntryDetailTarget =
    when (this) {
        QueryEntryDetailKind.EXACT_SYMBOL,
        QueryEntryDetailKind.OCCURRENCE,
        QueryEntryDetailKind.REFERENCE_OCCURRENCE,
        QueryEntryDetailKind.TRAVERSAL_RECORD,
        QueryEntryDetailKind.BINDING_ROW,
        QueryEntryDetailKind.VALUE_PATH,
        QueryEntryDetailKind.IMPACT_WITNESS -> QueryEntryDetailTarget.ROW
        QueryEntryDetailKind.FAILURE,
        QueryEntryDetailKind.OMISSION,
        QueryEntryDetailKind.WALK_OBSERVATION,
        QueryEntryDetailKind.REFERENCE_OBSERVATION,
        QueryEntryDetailKind.DISCOVERY_OBSERVATION,
        QueryEntryDetailKind.RELATION_OBSERVATION -> QueryEntryDetailTarget.EVIDENCE
    }

private fun detailRejected(
    parameter: PublicToolParameter,
    rule: PublicToolRule,
): Refinement<Unit, PublicToolInputFailure> = Refinement.Rejected(PublicToolInputFailure.Parameter(parameter, rule))
