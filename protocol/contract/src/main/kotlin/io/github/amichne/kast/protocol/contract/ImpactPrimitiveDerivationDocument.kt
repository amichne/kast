package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import kotlinx.serialization.Serializable

internal const val MAX_IMPACT_BUILTIN_MODULE_NAME_LENGTH = 512

@Serializable
enum class ImpactIntArithmeticRuleDocument(callableId: String) {
    INT_MINUS_V1("kotlin.Int.minus"),
    INT_PLUS_V1("kotlin.Int.plus"),
    INT_TIMES_V1("kotlin.Int.times");

    internal val compilerIdentity: String =
        when (
            val signature =
                CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = callableId,
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("kotlin/Int"),
                    rawTypeParameterCount = 0,
                )
        ) {
            is Refinement.Refined -> CompilerSymbolIdentity.fromCanonicalSignature(signature.value).value
            is Refinement.Rejected -> error("Invalid builtin rule signature")
        }
}

@Serializable
enum class ImpactIntArithmeticInputDocument {
    DISPATCH_RECEIVER,
    ARGUMENT_0,
}

@Serializable
enum class ImpactIntBuiltinEvidenceDocument {
    K2_BUILTINS_MODULE,
    K2_BUILTINS_METADATA,
}

internal fun ImpactTransferEvidenceDocument.PrimitiveDerivation.admits(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.PRIMITIVE_DERIVATION &&
        compilerIdentity.value == rule.compilerIdentity &&
        moduleName.admitsBuiltinModuleName() &&
        resultRange != inputRange &&
        resultRange.containsExecutionRange(inputRange) &&
        source.range == inputRange &&
        target.range == resultRange &&
        basis == source.enclosing.basis &&
        source.admitsPrimitiveResult(target)

private fun ProtocolText.admitsBuiltinModuleName(): Boolean =
    value.isNotBlank() && value.length <= MAX_IMPACT_BUILTIN_MODULE_NAME_LENGTH && value.none { it.isISOControl() }

private fun ImpactValueSiteReferenceDocument.admitsPrimitiveResult(target: ImpactValueSiteReferenceDocument): Boolean =
    enclosing == target.enclosing &&
        executionOwner.hasSameExecutionIdentity(target.executionOwner) &&
        callStack.hasSameCallStackIdentity(target.callStack) &&
        (role == ImpactValueRoleDocument.ExpressionResult || role == ImpactValueRoleDocument.LocalRead) &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        valuePath.values.isEmpty() &&
        target.valuePath.values.isEmpty()
