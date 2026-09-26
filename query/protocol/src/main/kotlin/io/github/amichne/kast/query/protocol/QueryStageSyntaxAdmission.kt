package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryVisibilitySelection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility

internal fun QueryStepDocument.syntax(): QueryStepSyntax? =
    when (this) {
        is QueryStepDocument.Related -> QueryStepSyntax.Related(relation.meaning())
        QueryStepDocument.Distinct -> QueryStepSyntax.Distinct
        is QueryStepDocument.AppendReferences -> null // Bound and restored by admitSyntax above.
        is QueryStepDocument.Where ->
            when (val value = predicate) {
                is QueryPredicateDocument.Visibility -> {
                    val visibilities =
                        value.values.values.uniqueValues()?.mapTo(linkedSetOf()) {
                            DeclarationVisibility.valueOf(it.name)
                        } ?: return null
                    QueryStepSyntax.Where(
                        QueryPredicate.Visibility(
                            QueryVisibilitySelection.from(visibilities).refinedOrNull() ?: return null
                        )
                    )
                }
                is QueryPredicateDocument.Primitive ->
                    QueryStepSyntax.Where(
                        QueryPredicate.Primitive(
                            QueryPrimitiveField.valueOf(value.field.name),
                            QueryPrimitiveOperator.valueOf(value.operator.name),
                            QueryPrimitiveValue.parse(value.value.value).refinedOrNull() ?: return null,
                        )
                    )
            }
    }

internal fun QueryOutputDocument.syntax(): QueryOutputSyntax? =
    when (this) {
        is QueryOutputDocument.Symbols -> {
            val selected =
                fields.values.uniqueValues()?.mapTo(linkedSetOf()) { QuerySymbolField.valueOf(it.name) } ?: return null
            QueryOutputSyntax(QuerySymbolFields.from(selected).refinedOrNull() ?: return null)
        }
    }

private fun RelationKindDocument.meaning(): RelationMeaning =
    when (this) {
        RelationKindDocument.REFERENCES -> RelationMeaning.References
        RelationKindDocument.CALLERS -> RelationMeaning.Callers
        RelationKindDocument.CALLEES -> RelationMeaning.Callees
        RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
        RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
        RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
        RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
    }

private fun <Value> List<Value>.uniqueValues(): List<Value>? = takeIf { it.distinct().size == it.size }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
