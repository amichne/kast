@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/** The genuine anonymous K2 signature distinguishes an extension receiver from its declared or implicit parameters. */
internal fun nativeCallbackFormal(
    literal: KtFunction,
    executionOwner: ValueExecutionOwner,
    argument: ValueArgumentPosition,
    observation: IntellijReadObservation,
): Refinement<ValueFormalPosition, ValueFlowUnsupportedCause> =
    observation.observedAnalyze(literal) {
        val symbol =
            when (literal) {
                is KtFunctionLiteral -> literal.symbol
                is KtNamedFunction -> literal.symbol
                else -> return@observedAnalyze rejectedFormal()
            }
        val receiverCount = if (symbol.receiverParameter == null) 0 else 1
        if (receiverCount == 1 && argument.value == 0)
            return@observedAnalyze Refinement.Refined(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION))
        val position =
            when (val value = ValueArgumentPosition.parse(argument.value - receiverCount)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return@observedAnalyze rejectedFormal()
            }
        val parameter = symbol.valueParameters.getOrNull(position.value) ?: return@observedAnalyze rejectedFormal()
        val name =
            when (val value = SymbolDiscoveryCandidateName.parse(parameter.name.asString())) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return@observedAnalyze rejectedFormal()
            }
        val formal =
            if (parameter.isImplicitLambdaParameter)
                ValueFormalParameter.fromCompilerImplicit(executionOwner, position, name)
            else {
                val declaration = parameter.psi as? KtParameter ?: return@observedAnalyze rejectedFormal()
                ValueFormalParameter.fromCompiler(
                    callable = executionOwner.enclosing,
                    position = position,
                    name = name,
                    range = sourceValueRange(declaration),
                    executionOwner = executionOwner,
                )
            }
        when (formal) {
            is Refinement.Refined -> Refinement.Refined(ValueFormalPosition.Parameter(formal.value))
            is Refinement.Rejected -> rejectedFormal()
        }
    }

private fun rejectedFormal() = Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
