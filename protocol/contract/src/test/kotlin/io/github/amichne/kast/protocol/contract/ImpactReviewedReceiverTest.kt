package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedReceiverTest {
    @Test
    fun `also receiver result cannot omit its normal callback return premise`() {
        val transfer = receiverTransfer()
        assertFalse(
            transfer.copy(target = transfer.target.copy(conditions = EmptyImpactConditions)).admitsEvidence(),
            "An invocation contract does not establish a normal callback return",
        )
    }

    @Test
    fun `also rejects callback returns dispatch receivers and content identity guesses`() {
        val transfer = receiverTransfer()
        val fixture = ImpactReviewedScopeFixture()
        val receiver = transfer.source.role as ImpactValueRoleDocument.Receiver
        val invalid =
            listOf(
                transfer.copy(source = transfer.source.copy(role = ImpactValueRoleDocument.Return)),
                transfer.copy(source = transfer.source.copy(range = fixture.range(50, 60))),
                transfer.copy(
                    source = transfer.source.copy(role = receiver.copy(kind = ImpactReceiverKindDocument.DISPATCH))
                ),
                transfer.copy(source = transfer.source.copy(valuePath = fixture.source.valuePath)),
                transfer.copy(target = transfer.target.copy(valuePath = fixture.source.valuePath)),
                transfer.copy(
                    source =
                        transfer.source.copy(
                            role = receiver.copy(invocation = receiver.invocation.copy(range = fixture.range(31, 90)))
                        )
                ),
                transfer.copy(
                    source =
                        transfer.source.copy(
                            role = receiver.copy(invocation = receiver.invocation.copy(callable = fixture.declaration))
                        )
                ),
                transfer.copy(
                    binding =
                        transfer.binding.copy(
                            authority =
                                (transfer.binding.authority as ImpactReviewedValueBindingDocument).copy(
                                    invocationEvidence = ImpactReviewedInvocationEvidenceDocument.Unestablished
                                )
                        )
                ),
                transfer.copy(
                    binding =
                        transfer.binding.copy(
                            relation =
                                ImpactReviewedValueRelationDocument.CallbackCorrespondence(
                                    ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
                                )
                        )
                ),
            )
        for (changed in invalid) assertFalse(changed.admitsEvidence(), changed.toString())
    }

    @Test
    fun `also carries the whole extension receiver with independent invocation evidence`() {
        val transfer = receiverTransfer()
        assertTrue(transfer.admitsEvidence(), "Missing reviewed also receiver correspondence")
        val encoded = Json.parseToJsonElement(Json.encodeToString(transfer)).jsonObject
        val relationship = encoded.getValue("binding").jsonObject
        val binding = relationship.getValue("authority").jsonObject
        assertEquals("KOTLIN_2_3_0_ALSO_V1", binding.getValue("rule").jsonPrimitive.content)
        assertEquals(
            "CONDITIONAL_RECEIVER_TO_CALL_RESULT",
            relationship.getValue("relation").jsonObject.getValue("semantics").jsonPrimitive.content,
        )
        assertEquals("NOT_ESTABLISHED", binding.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", binding.getValue("invocationCount").jsonPrimitive.content)
        val lines = binding.getValue("artifacts").jsonObject.getValue("functionLines").jsonObject
        assertEquals("99", lines.getValue("first").jsonPrimitive.content)
        assertEquals("105", lines.getValue("last").jsonPrimitive.content)
    }

    @Test
    fun `normal callback return premise rejects wrong origin binding and erased downstream conditions`() {
        val f = ImpactReviewedScopeFixture()
        val transfer = receiverTransfer()
        val premise =
            assertInstanceOf(
                ImpactValueConditionDocument.ReviewedCallbackNormalReturn::class.java,
                transfer.target.conditions.values.single(),
            )
        for (changed in
            listOf(
                premise.copy(origin = premise.origin.copy(range = f.range(31, 35))),
                premise.copy(useRange = f.range(20, 90)),
                premise.copy(binding = f.binding),
            )) assertFalse(
            transfer.copy(target = transfer.target.copy(conditions = f.base.bounded(listOf(changed)))).admitsEvidence()
        )
        assertFalse(
            transfer
                .copy(target = transfer.target.copy(conditions = f.base.bounded(listOf(premise, premise))))
                .admitsEvidence()
        )
        val downstream =
            ImpactCompilerTransferDocument(
                transfer.target,
                transfer.target.copy(role = ImpactValueRoleDocument.Return),
                ImpactTransferKindDocument.RETURN,
            )
        assertTrue(downstream.admitsEvidence())
        assertFalse(
            downstream.copy(target = downstream.target.copy(conditions = EmptyImpactConditions)).admitsEvidence()
        )
    }

    @Test
    fun `normal return premise has exact conditional wire shape without an execution assertion`() {
        val transfer = receiverTransfer()
        val encoded = Json.encodeToString(transfer)
        val premise =
            Json.parseToJsonElement(encoded)
                .jsonObject
                .getValue("target")
                .jsonObject
                .getValue("conditions")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(setOf("type", "origin", "useRange", "binding"), premise.keys)
        assertEquals("REVIEWED_CALLBACK_NORMAL_RETURN", premise.getValue("type").jsonPrimitive.content)
        assertEquals(
            "30",
            premise.getValue("origin").jsonObject.getValue("range").jsonObject.getValue("start").jsonPrimitive.content,
        )
        assertEquals("90", premise.getValue("useRange").jsonObject.getValue("end").jsonPrimitive.content)
        assertEquals(
            "NOT_ESTABLISHED",
            premise.getValue("binding").jsonObject.getValue("actualExecution").jsonPrimitive.content,
        )
        assertEquals(transfer, Json.decodeFromString<ImpactReviewedValueTransferDocument>(encoded))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ImpactReviewedValueTransferDocument>(
                encoded.replace("REVIEWED_CALLBACK_NORMAL_RETURN", "CALLBACK_EXECUTED")
            )
        }
    }

    private fun receiverTransfer(): ImpactReviewedValueTransferDocument {
        val f = ImpactReviewedScopeFixture()
        val binding = receiverBinding(f)
        val source =
            f.source.copy(
                range = f.range(30, 35),
                role =
                    ImpactValueRoleDocument.Receiver(
                        ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration),
                        ImpactReceiverKindDocument.EXTENSION,
                    ),
                executionOwner = ImpactExecutionOwnerDocument.Declaration,
                valuePath = EmptyImpactValuePath,
            )
        val premise =
            ImpactValueConditionDocument.ReviewedCallbackNormalReturn(
                ImpactValueConditionOriginDocument(
                    ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
                    source.range,
                    source.role,
                    source.valuePath,
                    source.callStack,
                ),
                binding.supplierRange,
                binding,
            )
        val result = f.target.copy(valuePath = EmptyImpactValuePath, conditions = f.base.bounded(listOf(premise)))
        return ImpactReviewedValueTransferDocument(
            source,
            result,
            ImpactReviewedValueRelationshipDocument.fromBinding(
                binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT,
            ),
        )
    }

    private fun receiverBinding(f: ImpactReviewedScopeFixture): ImpactReviewedValueBindingDocument {
        val signature =
            CompilerSignatureDocument.Function(
                f.base.text("kotlin.also"),
                CompilerReceiverDocument.Present(f.base.text("T")),
                f.base.bounded(emptyList()),
                f.base.bounded(listOf(f.base.text("(T)->kotlin/Unit"))),
                (CompilerTypeParameterCountDocument.parse(1) as Refinement.Refined).value,
            )
        val declaration =
            f.declaration.copy(
                compilerIdentity =
                    (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined).value.identity
            )
        val declared =
            f.declared.copy(
                contract =
                    f.declared.contract.copy(
                        target =
                            f.declared.contract.target.copy(
                                declaration = declaration,
                                qualifiedIdentity = signature.qualifiedIdentity,
                                valueParameters = signature.valueParameters,
                                typeParameterCount = f.base.count(1),
                            )
                    )
            )
        return f.binding.copy(
            rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1,
            artifacts =
                f.artifacts.copy(
                    functionLines = ImpactReviewedSourceLinesDocument(f.base.offset(99), f.base.offset(105))
                ),
            selected =
                f.binding.selected.copy(
                    declaration = declaration,
                    qualifiedIdentity = signature.qualifiedIdentity,
                    valueParameters = signature.valueParameters,
                    typeParameterCount = f.base.count(1),
                ),
            invocationEvidence = ImpactReviewedInvocationEvidenceDocument.Declared(declared),
        )
    }
}
