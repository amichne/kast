package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactMapEntrySlotDocument
import io.github.amichne.kast.protocol.contract.ImpactPairSlotDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionSelectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedOrdinaryTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleInputDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueAuthorityDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.PairSlot
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCollectionSelection
import io.github.amichne.kast.relation.contract.ReviewedMapEntryCorrelation
import io.github.amichne.kast.relation.contract.ReviewedTupleInput
import io.github.amichne.kast.relation.contract.ReviewedTupleOrigin
import io.github.amichne.kast.relation.contract.ReviewedValueAuthority
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature

internal fun ReviewedValueAuthority.impactDocument(): ImpactProjected<ImpactReviewedValueAuthorityDocument> =
    when (this) {
        is ReviewedCallbackValueBinding -> impactDocument()
        is ReviewedCallValueBinding -> impactDocument()
    }

internal fun ReviewedCallValueBinding.impactDocument(): ImpactProjected<ImpactReviewedCallValueBindingDocument> =
    enclosing
        .impactDeclaration()
        .impactZip(invocation.impactDocument())
        .impactZip(ordinaryTargetDocument())
        .impactZip(artifact.impactDocument())
        .impactZip(entryReadOrder.impactDocument())
        .impactMap { (authority, order) ->
            val (selected, artifacts) = authority
            ImpactReviewedCallValueBindingDocument(
                artifact.rule.impactDocument(),
                selected.first.first.basis,
                selected.first.first,
                selected.first.second,
                selected.second,
                artifacts,
                order,
            )
        }

private fun ReviewedCallValueBinding.ordinaryTargetDocument(): ImpactProjected<ImpactReviewedOrdinaryTargetDocument> =
    when (val signature = target.signature) {
        is CanonicalCompilerSignature.Function ->
            target.impactCallableTarget(basis).impactMap(ImpactReviewedOrdinaryTargetDocument::Function)
        is CanonicalCompilerSignature.Property ->
            ValueDeclarationIdentity.fromCompiler(target)
                .impactDocument(basis)
                .impactZip(signature.qualifiedIdentity.value.impactText())
                .impactZip(signature.receiver.declaredReceiverDocument())
                .impactZip(signature.contextReceivers.impactEach { it.value.impactText() })
                .impactZip(signature.returnType.value.impactText())
                .impactMap { (selected, returned) ->
                    ImpactReviewedOrdinaryTargetDocument.Property(
                        selected.first.first.first,
                        selected.first.first.second,
                        selected.first.second,
                        selected.second,
                        returned,
                    )
                }
        else -> Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    }

internal fun ReviewedTupleOrigin.impactDocument(): ImpactProjected<ImpactReviewedTupleOriginDocument> =
    binding.impactDocument().impactZip(callStack.impactDocument()).impactMap { (binding, stack) ->
        ImpactReviewedTupleOriginDocument(binding, stack)
    }

internal fun ReviewedMapEntryCorrelation.impactDocument(): ImpactProjected<ImpactReviewedMapEntryCorrelationDocument> =
    binding
        .impactDocument()
        .impactZip(tupleOrigin.impactDocument())
        .impactZip(returnRange.impactDocument())
        .impactZip(returnExecution.impactDocument())
        .impactZip(callStack.impactDocument())
        .impactZip(keyEvidence.impactDocument())
        .impactMap { (entry, evidence) ->
            val (supplier, stack) = entry
            ImpactReviewedMapEntryCorrelationDocument(
                supplier.first.first.first,
                supplier.first.first.second,
                supplier.first.second,
                supplier.second,
                stack,
                evidence,
            )
        }

internal fun ReviewedValueRelation.impactDocument(): ImpactProjected<ImpactReviewedValueRelationDocument> =
    when (this) {
        is ReviewedValueRelation.CallbackReceiverInput ->
            receiver.impactDocument().impactMap { ImpactReviewedValueRelationDocument.CallbackReceiverInput(it) }
        is ReviewedValueRelation.GroupPlacement ->
            entry.impactDocument().impactMap { ImpactReviewedValueRelationDocument.GroupPlacement(it) }
        is ReviewedValueRelation.FilteredElements ->
            Refinement.Refined(ImpactReviewedValueRelationDocument.FilteredElements(selection.impactDocument()))
        ReviewedValueRelation.NonNullCallbackResult ->
            Refinement.Refined(ImpactReviewedValueRelationDocument.NonNullCallbackResult)
        is ReviewedValueRelation.EntryInput ->
            entry.impactDocument().impactMap {
                ImpactReviewedValueRelationDocument.EntryInput(it, slot.impactDocument())
            }
        is ReviewedValueRelation.PreservedKey ->
            entry.impactDocument().impactMap { ImpactReviewedValueRelationDocument.PreservedKey(it) }
        is ReviewedValueRelation.EntryProjection ->
            entry.impactDocument().impactMap {
                ImpactReviewedValueRelationDocument.EntryProjection(it, slot.impactDocument())
            }
        is ReviewedValueRelation.MappedValue ->
            entry.impactDocument().impactMap { ImpactReviewedValueRelationDocument.MappedValue(it) }
        is ReviewedValueRelation.CallbackCorrespondence ->
            Refinement.Refined(ImpactReviewedValueRelationDocument.CallbackCorrespondence(semantics.impactDocument()))
        is ReviewedValueRelation.ReorderedElements ->
            Refinement.Refined(ImpactReviewedValueRelationDocument.ReorderedElements(selection.impactDocument()))
        is ReviewedValueRelation.TupleConstruction ->
            origin.impactDocument().impactMap {
                ImpactReviewedValueRelationDocument.TupleConstruction(it, input.impactDocument(), slot.impactDocument())
            }
        is ReviewedValueRelation.TupleProjection ->
            origin.impactDocument().impactMap {
                ImpactReviewedValueRelationDocument.TupleProjection(it, slot.impactDocument())
            }
        is ReviewedValueRelation.AssociationComponent ->
            entry.impactDocument().impactMap {
                ImpactReviewedValueRelationDocument.AssociationComponent(it, slot.impactDocument())
            }
    }

internal fun PairSlot.impactDocument(): ImpactPairSlotDocument =
    when (this) {
        PairSlot.FIRST -> ImpactPairSlotDocument.FIRST
        PairSlot.SECOND -> ImpactPairSlotDocument.SECOND
    }

internal fun MapEntrySlot.impactDocument(): ImpactMapEntrySlotDocument =
    when (this) {
        MapEntrySlot.KEY -> ImpactMapEntrySlotDocument.KEY
        MapEntrySlot.VALUE -> ImpactMapEntrySlotDocument.VALUE
    }

private fun ReviewedTupleInput.impactDocument(): ImpactReviewedTupleInputDocument =
    when (this) {
        ReviewedTupleInput.EXTENSION_RECEIVER -> ImpactReviewedTupleInputDocument.EXTENSION_RECEIVER
        ReviewedTupleInput.ARGUMENT_ZERO -> ImpactReviewedTupleInputDocument.ARGUMENT_ZERO
        ReviewedTupleInput.ARGUMENT_ONE -> ImpactReviewedTupleInputDocument.ARGUMENT_ONE
    }

private fun ReviewedCollectionSelection.impactDocument(): ImpactReviewedCollectionSelectionDocument =
    when (this) {
        ReviewedCollectionSelection.ELEMENT -> ImpactReviewedCollectionSelectionDocument.ELEMENT
        ReviewedCollectionSelection.CONTENT -> ImpactReviewedCollectionSelectionDocument.CONTENT
    }
