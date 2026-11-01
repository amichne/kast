package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactReviewedEntryFixture {
    val base = ImpactReviewedValueFixture()
    val producer =
        ImpactReviewedMappedFixture(12, 15) {
            it.copy(body = it.body.copy(range = base.range(10, 20)), supplierRange = base.range(5, 25))
        }
    val binding = ImpactReviewedMappedFixture().binding
    private val call = ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration)
    val source =
        producer.transfer.target.copy(
            range = base.range(30, 35),
            role = ImpactValueRoleDocument.Receiver(call, ImpactReceiverKindDocument.EXTENSION),
        )
    val family = ImpactReviewedMapEntryFamilyDocument(binding, source.callStack)
    val entry =
        ImpactReviewedInputEntryOriginDocument(
            family,
            source.range,
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
        )
    private val formalRange = base.range(40, 41)
    private val owner = ImpactExecutionOwnerDocument.ReviewedCallback(binding)
    val input =
        ImpactReviewedValueTransferDocument(
            source,
            source.copy(
                range = formalRange,
                executionOwner = owner,
                role =
                    ImpactValueRoleDocument.Formal(
                        ImpactFormalPositionDocument.Parameter(
                            ImpactFormalParameterDocument(
                                binding.enclosing,
                                base.range(0, 1).start,
                                base.text("entry"),
                                formalRange,
                                owner,
                            )
                        )
                    ),
                valuePath =
                    base.bounded(
                        listOf(
                            ImpactValuePathSegmentDocument.MapEntryComponent(entry, ImpactMapEntrySlotDocument.VALUE)
                        ) + source.valuePath.values.drop(1)
                    ),
            ),
            ImpactReviewedValueRelationshipDocument(
                binding,
                ImpactReviewedValueRelationDocument.EntryInput(entry, ImpactMapEntrySlotDocument.VALUE),
            ),
        )

    fun read(key: Boolean): ImpactReviewedValueTransferDocument {
        val slot = if (key) ImpactMapEntrySlotDocument.KEY else ImpactMapEntrySlotDocument.VALUE
        val selected = property(key)
        val invocation = ImpactInvocationReferenceDocument(base.range(50, 60), selected.declaration, owner)
        val authority =
            ImpactReviewedCallValueBindingDocument(
                if (key) ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1
                else ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1,
                binding.basis,
                binding.enclosing,
                invocation,
                selected,
                metadata(),
            )
        val receiver =
            input.target.copy(
                range = base.range(50, 55),
                role = ImpactValueRoleDocument.Receiver(invocation, ImpactReceiverKindDocument.DISPATCH),
                valuePath =
                    base.bounded(
                        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(entry, slot)) +
                            source.valuePath.values.drop(1)
                    ),
            )
        val condition = stableCondition(receiver, authority, slot)
        return ImpactReviewedValueTransferDocument(
            receiver,
            receiver.copy(
                range = invocation.range,
                role = ImpactValueRoleDocument.ExpressionResult,
                valuePath = base.bounded(receiver.valuePath.values.drop(1)),
                conditions = base.bounded(receiver.conditions.values + condition),
            ),
            ImpactReviewedValueRelationshipDocument(
                authority,
                ImpactReviewedValueRelationDocument.EntryProjection(entry, slot),
            ),
        )
    }

    private fun stableCondition(
        receiver: ImpactValueSiteReferenceDocument,
        authority: ImpactReviewedCallValueBindingDocument,
        slot: ImpactMapEntrySlotDocument,
    ) =
        ImpactValueConditionDocument.ReviewedEntryComponentStable(
            ImpactValueConditionOriginDocument(
                ImpactExecutionReferenceDocument(receiver.enclosing, receiver.executionOwner),
                receiver.range,
                receiver.role,
                receiver.valuePath,
                receiver.callStack,
            ),
            authority.invocation.range,
            authority,
            entry,
            slot,
        )

    private fun property(key: Boolean): ImpactReviewedOrdinaryTargetDocument.Property {
        val signature =
            CompilerSignatureDocument.Property(
                base.text("kotlin.collections.Map.Entry." + if (key) "key" else "value"),
                CompilerReceiverDocument.Absent,
                base.bounded(emptyList()),
                base.text(if (key) "K" else "V"),
            )
        val identity = (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined).value.identity
        return ImpactReviewedOrdinaryTargetDocument.Property(
            binding.selected.declaration.copy(
                file =
                    base.text("jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/collections/collections.kotlin_builtins"),
                compilerIdentity = identity,
            ),
            signature.qualifiedIdentity,
            ImpactDeclaredCallbackReceiverDocument.Absent,
            signature.contextReceivers,
            signature.returnType,
        )
    }

    private fun metadata() =
        ImpactReviewedEntryDeclarationArtifactDocument(
            base.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
            base.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
            base.text("a972160cdcfa4c563abeaf0d10992080470713315a2028de4c421a761a0b3850"),
            ImpactReviewedSourceLinesDocument(base.range(611, 627).start, base.range(611, 627).end),
            ImpactReviewedMetadataEvidenceDocument(
                base.text("kotlin/collections/collections.kotlin_builtins"),
                base.text("e91b5fc8241f903f0cd2c6e3d038c10a9c74a8f22659dfa9268e21afcca70bf1"),
            ),
        )
}
