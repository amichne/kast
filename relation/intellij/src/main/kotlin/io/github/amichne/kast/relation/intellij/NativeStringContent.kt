package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueStringContent
import io.github.amichne.kast.relation.contract.ValueStringContentInput
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Only the established embedded result supplies content. Descendants supply no shortcut through a call or getter. */
internal class NativeStringContent(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val retained: NativeValueFlowTransfers,
) {
    private val observation = projection.observation

    fun read(expression: KtExpression, entry: KtStringTemplateEntryWithExpression) {
        when (val result = construction(expression, entry)) {
            NativeStringConstruction.NullConstant -> constantNull(expression, entry)
            NativeStringConstruction.UnresolvedConversion ->
                nativeStringConversionReceiver(source, expression, scope, projection, retained)
            is NativeStringConstruction.Contribution -> emit(result.evidence)
        }
    }

    fun readConverted(
        expression: KtExpression,
        entry: KtStringTemplateEntryWithExpression,
        invocation: io.github.amichne.kast.relation.contract.ValueInvocation,
    ) {
        val selected = nativeStringConversionDeclaration(expression, projection)
        if (selected !is Refinement.Refined) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, entry)
            return
        }
        val endpoint = nativeValueCallableEndpoint(selected.value, source.enclosing, scope, projection)
        if (endpoint !is Refinement.Refined || endpoint.value.valueIdentity != invocation.callable.valueIdentity) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, entry)
            return
        }
        val template = entry.parent as? KtStringTemplateExpression ?: return
        when (val content = content(expression, entry, template, false)) {
            is NativeStringConstruction.Contribution -> emit(content.evidence)
            NativeStringConstruction.NullConstant,
            NativeStringConstruction.UnresolvedConversion ->
                retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, entry)
        }
    }

    private fun constantNull(expression: KtExpression, entry: KtStringTemplateEntryWithExpression) {
        val template = entry.parent as? KtStringTemplateExpression ?: return
        when (
            val record =
                io.github.amichne.kast.relation.contract.ValueStringConstruction.constantNullFromCompiler(
                    source,
                    sourceValueRange(template),
                    sourceValueRange(entry),
                    sourceValueRange(expression),
                )
        ) {
            is Refinement.Refined -> retained.stringConstructions += record.value
            is Refinement.Rejected -> retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, entry)
        }
    }

    private fun construction(
        expression: KtExpression,
        entry: KtStringTemplateEntryWithExpression,
    ): NativeStringConstruction {
        val template = entry.parent as? KtStringTemplateExpression
        if (template == null || entry.expression !== expression || source.valuePath.segments.isNotEmpty())
            return NativeStringConstruction.UnresolvedConversion
        return observation.observedAnalyze(expression) {
            val type =
                expression.expressionType as? KaClassType
                    ?: return@observedAnalyze NativeStringConstruction.UnresolvedConversion
            val name = type.classId.asSingleFqName().asString()
            when {
                name == "kotlin.Nothing" && type.isMarkedNullable -> NativeStringConstruction.NullConstant
                name != "kotlin.String" -> NativeStringConstruction.UnresolvedConversion
                else -> content(expression, entry, template, type.isMarkedNullable)
            }
        }
    }

    private fun content(
        expression: KtExpression,
        entry: KtStringTemplateEntryWithExpression,
        template: KtStringTemplateExpression,
        nullable: Boolean,
    ): NativeStringConstruction =
        when (
            val content =
                ValueStringContent.fromCompiler(
                    templateRange = sourceValueRange(template),
                    entryRange = sourceValueRange(entry),
                    inputRange = sourceValueRange(expression),
                    input = if (nullable) ValueStringContentInput.NULLABLE_STRING else ValueStringContentInput.STRING,
                )
        ) {
            is Refinement.Refined -> NativeStringConstruction.Contribution(content.value)
            is Refinement.Rejected -> NativeStringConstruction.UnresolvedConversion
        }

    private fun emit(content: ValueStringContent) {
        val conditions =
            when (val result = conditions(content)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> {
                    retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                    return
                }
            }
        when (
            val target =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = content.templateRange,
                    role = ValueRole.ExpressionResult,
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    conditions = conditions,
                )
        ) {
            is Refinement.Refined -> retained.emitTarget(target.value, ValueTransferKind.STRING_CONTENT, content)
            is Refinement.Rejected -> retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun conditions(
        content: ValueStringContent
    ): Refinement<ValueConditions, io.github.amichne.kast.relation.contract.ValueSiteFailure> =
        when (content.input) {
            ValueStringContentInput.STRING -> Refinement.Refined(source.conditions)
            ValueStringContentInput.NULLABLE_STRING ->
                when (val fact = ValueCondition.StringContentNonNull.fromCompiler(source, content)) {
                    is Refinement.Rejected -> fact
                    is Refinement.Refined -> source.conditions.requiring(fact.value)
                }
        }
}

private sealed interface NativeStringConstruction {
    data class Contribution(val evidence: ValueStringContent) : NativeStringConstruction

    data object NullConstant : NativeStringConstruction

    data object UnresolvedConversion : NativeStringConstruction
}
