package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticInputDocument
import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactIntBuiltinEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.relation.contract.IntArithmeticInput
import io.github.amichne.kast.relation.contract.IntArithmeticRule
import io.github.amichne.kast.relation.contract.IntBuiltinEvidence
import io.github.amichne.kast.relation.contract.ValueIntArithmetic

internal fun ValueIntArithmetic.impactDocument(): ImpactProjected<ImpactTransferEvidenceDocument.PrimitiveDerivation> =
    callable.compilerIdentity.value
        .impactText()
        .impactZip(callable.moduleName.value.impactText())
        .impactZip(inputRange.impactDocument().impactZip(resultRange.impactDocument()))
        .impactZip(basis.impactDocument())
        .impactMap { (evidence, basis) ->
            val (identity, moduleName) = evidence.first
            val (inputRange, resultRange) = evidence.second
            ImpactTransferEvidenceDocument.PrimitiveDerivation(
                rule = rule.impactDocument(),
                compilerIdentity = identity,
                authority = authority.impactDocument(),
                moduleName = moduleName,
                input = input.impactDocument(),
                inputRange = inputRange,
                resultRange = resultRange,
                basis = basis,
            )
        }

private fun IntArithmeticRule.impactDocument(): ImpactIntArithmeticRuleDocument =
    when (this) {
        IntArithmeticRule.INT_MINUS_V1 -> ImpactIntArithmeticRuleDocument.INT_MINUS_V1
        IntArithmeticRule.INT_PLUS_V1 -> ImpactIntArithmeticRuleDocument.INT_PLUS_V1
        IntArithmeticRule.INT_TIMES_V1 -> ImpactIntArithmeticRuleDocument.INT_TIMES_V1
    }

private fun IntArithmeticInput.impactDocument(): ImpactIntArithmeticInputDocument =
    when (this) {
        IntArithmeticInput.DISPATCH_RECEIVER -> ImpactIntArithmeticInputDocument.DISPATCH_RECEIVER
        IntArithmeticInput.ARGUMENT_0 -> ImpactIntArithmeticInputDocument.ARGUMENT_0
    }

private fun IntBuiltinEvidence.impactDocument(): ImpactIntBuiltinEvidenceDocument =
    when (this) {
        IntBuiltinEvidence.K2_BUILTINS_MODULE -> ImpactIntBuiltinEvidenceDocument.K2_BUILTINS_MODULE
        IntBuiltinEvidence.K2_BUILTINS_METADATA -> ImpactIntBuiltinEvidenceDocument.K2_BUILTINS_METADATA
    }
