package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

private const val MAX_IMPACT_CATCH_BRANCHES = 1024

@Serializable
data class ImpactCompilerTransferDocument(
    val source: ImpactValueSiteReferenceDocument,
    val target: ImpactValueSiteReferenceDocument,
    val kind: ImpactTransferKindDocument,
    val evidence: ImpactTransferEvidenceDocument,
) {
    constructor(
        source: ImpactValueSiteReferenceDocument,
        target: ImpactValueSiteReferenceDocument,
        kind: ImpactTransferKindDocument,
    ) : this(source, target, kind, ImpactTransferEvidenceDocument.Direct)

    fun admitsEvidence(): Boolean =
        (evidence is ImpactTransferEvidenceDocument.Guarded ||
            source.predicates.values.toSet() == target.predicates.values.toSet()) &&
            when (val proof = evidence) {
                is ImpactTransferEvidenceDocument.Guarded ->
                    proof.added.admitsPredicates(source.enclosing.basis) &&
                        (source.predicates.values + proof.added.values).toSet() == target.predicates.values.toSet() &&
                        copy(target = target.copy(predicates = source.predicates), evidence = proof.delegate)
                            .admitsEvidence()
                ImpactTransferEvidenceDocument.Direct -> kind.admitsDirectState(source, target)
                is ImpactTransferEvidenceDocument.Source -> proof.operation.admits(source, target, kind)
                is ImpactTransferEvidenceDocument.NormalBranchResult -> proof.admitsNormalBranch(source, target, kind)
            }
}

private fun ImpactTransferEvidenceDocument.NormalBranchResult.admitsNormalBranch(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.BRANCH_ALTERNATIVE &&
        (source.role == ImpactValueRoleDocument.ExpressionResult || source.role == ImpactValueRoleDocument.LocalRead) &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.enclosing == target.enclosing &&
        source.executionOwner == target.executionOwner &&
        source.valuePath == target.valuePath &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        admitsAnchors(source.range, target.range) &&
        alternative.admitsIndex()

private fun ImpactTransferKindDocument.admitsDirectState(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean =
    when (this) {
        ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
        ImpactTransferKindDocument.SOURCE_CALL_RETURN,
        ImpactTransferKindDocument.FIELD_READ,
        ImpactTransferKindDocument.CONSTRUCTOR_FIELD -> false
        ImpactTransferKindDocument.LOCAL_BINDING,
        ImpactTransferKindDocument.LOCAL_READ,
        ImpactTransferKindDocument.ARGUMENT,
        ImpactTransferKindDocument.RECEIVER,
        ImpactTransferKindDocument.RETURN,
        ImpactTransferKindDocument.PROPERTY_ASSIGNMENT,
        ImpactTransferKindDocument.BRANCH_ALTERNATIVE,
        ImpactTransferKindDocument.WRAPPER_RETURN ->
            source.valuePath == target.valuePath && source.callStack.hasSameCallStackIdentity(target.callStack)
    }

private fun ImpactTransferEvidenceDocument.NormalBranchResult.admitsAnchors(
    source: ImpactSourceRangeDocument,
    target: ImpactSourceRangeDocument,
): Boolean =
    target == tryRange &&
        tryRange.start.value < branchRange.start.value &&
        branchRange.start.value < branchRange.end.value &&
        branchRange.end.value <= tryRange.end.value &&
        source.start.value >= branchRange.start.value &&
        source.end.value <= branchRange.end.value

private fun ImpactTryBranchAlternativeDocument.admitsIndex(): Boolean =
    when (this) {
        ImpactTryBranchAlternativeDocument.TryBody -> true
        is ImpactTryBranchAlternativeDocument.CatchBody -> index in 0 until MAX_IMPACT_CATCH_BRANCHES
    }
