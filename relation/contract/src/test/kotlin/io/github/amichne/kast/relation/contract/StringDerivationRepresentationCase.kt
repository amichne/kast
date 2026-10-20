package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Existing pure origin and transformation fixtures supply independent Known and Unknown input branches. */
internal class StringDerivationRepresentationCase {
    val origin = ModeledValueProvenanceOracleCase()

    fun input(evidence: RepresentationEvidence): RepresentationEvidence {
        val binding = site(flowRange(44, 45), ValueRole.LocalBinding)
        val local = site(flowRange(46, 50), ValueRole.LocalRead)
        val bound = ValueTransfer.fromCompiler(evidence.site, binding, ValueTransferKind.LOCAL_BINDING).flowRefined()
        val read = ValueTransfer.fromCompiler(binding, local, ValueTransferKind.LOCAL_READ).flowRefined()
        return evidence.transfer(bound).flowRefined().transfer(read).flowRefined()
    }

    fun site(range: ExactDeclarationTextRange, role: ValueRole): ValueSite =
        ValueSite.fromCompiler(
                enclosing = origin.model.host,
                range = range,
                role = role,
                executionOwner = origin.model.execution,
                callStack = origin.model.stack,
                predicates = origin.model.predicates,
            )
            .flowRefined()

    fun transfer(source: ValueSite): ValueTransfer {
        val template = flowRange(41, 59)
        val evidence =
            ValueTransferEvidence.StringInterpolation.fromCompiler(
                    templateRange = template,
                    entryRange = flowRange(45, 53),
                    entryIndex = ValueInterpolationEntryIndex.parse(0).flowRefined(),
                    scalarKind = ValueInterpolationScalarKind.STRING,
                )
                .flowRefined()
        return ValueTransfer.fromCompiler(
                source = source,
                target = site(template, ValueRole.ExpressionResult),
                kind = ValueTransferKind.DERIVATION,
                evidence = evidence,
            )
            .flowRefined()
    }
}
