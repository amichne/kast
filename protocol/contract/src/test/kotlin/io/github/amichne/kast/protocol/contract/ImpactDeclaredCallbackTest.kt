package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactDeclaredCallbackTest {
    @Test
    fun `encoded dependency result has exact contract fields and no invented invocation`() {
        val f = DeclaredDocumentFixture()
        val transfer = f.transfer
        assertTrue(transfer.admitsEvidence())
        val encoded = Json.encodeToString(transfer)
        val document = Json.parseToJsonElement(encoded).jsonObject
        val operation = document.getValue("evidence").jsonObject.getValue("operation").jsonObject
        assertEquals(setOf("type", "binding"), operation.keys)
        assertEquals("DECLARED_CALLBACK_RESULT", operation.getValue("type").jsonPrimitive.content)
        val binding = operation.getValue("binding").jsonObject
        assertEquals(setOf("enclosing", "body", "contract", "resultEffect"), binding.keys)
        assertEquals("RETURNS_RESULT_OF", binding.getValue("resultEffect").jsonPrimitive.content)
        val contract = binding.getValue("contract").jsonObject
        assertEquals(
            setOf(
                "basis",
                "occurrenceFile",
                "occurrenceRange",
                "owner",
                "target",
                "position",
                "classDigest",
                "provenance",
                "invocationKind",
            ),
            contract.keys,
        )
        assertEquals("ab".repeat(32), contract.getValue("classDigest").jsonPrimitive.content)
        assertEquals("0", contract.getValue("position").jsonPrimitive.content)
        assertEquals("AT_MOST_ONCE", contract.getValue("invocationKind").jsonPrimitive.content)
        assertEquals("KOTLIN_BINARY_CONTRACT", contract.getValue("provenance").jsonPrimitive.content)
        val fact = Json.parseToJsonElement(Json.encodeToString<ImpactValueConditionDocument>(f.condition)).jsonObject
        assertEquals(setOf("type", "origin", "useRange", "binding"), fact.keys)
        assertFalse(encoded.contains("invocationOwner"))
        val restored = Json.decodeFromString<ImpactCompilerTransferDocument>(encoded)
        assertEquals(transfer, restored)
        assertTrue(restored.admitsEvidence())
    }

    @Test
    fun `unknown or erased effect and contract fields fail decoding`() {
        val f = DeclaredDocumentFixture()
        val encoded = Json.encodeToString(f.binding)
        for (malformed in
            listOf(
                encoded.replace("RETURNS_RESULT_OF", "RETURNS_RECEIVER"),
                encoded.replace("AT_MOST_ONCE", "SOMETIMES"),
                encoded.replace(",\"resultEffect\":\"RETURNS_RESULT_OF\"", ""),
                encoded.replace("\"classDigest\":\"${"ab".repeat(32)}\",", ""),
            )) {
            assertNotEquals(encoded, malformed)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString<ImpactDeclaredCallbackValueBindingDocument>(malformed)
            }
        }
    }

    @Test
    fun `calls in place alone admits execution but rejects result proof`() {
        val f = DeclaredDocumentFixture()
        val binding = f.binding.copy(resultEffect = ImpactDeclaredCallbackResultEffectDocument.UNSPECIFIED)
        val source = f.transfer.source.copy(executionOwner = ImpactExecutionOwnerDocument.DeclaredCallback(binding))
        assertTrue(source.admitsExecutionOwner())
        assertFalse(f.transfer.copy(source = source, evidence = proof(binding)).admitsEvidence())
        assertFalse(f.transfer.copy(evidence = proof(binding)).admitsEvidence())
    }

    @Test
    fun `transfer rejects wrong body supplier formal digest target and prerequisite origin`() {
        val f = DeclaredDocumentFixture()
        val binding = f.binding
        val contract = binding.contract
        val changes =
            listOf(
                binding.copy(body = binding.body.copy(range = f.range(41, 80))),
                binding.copy(contract = contract.copy(occurrenceRange = f.range(29, 90))),
                binding.copy(contract = contract.copy(position = f.base.offset(1))),
                binding.copy(contract = contract.copy(classDigest = f.base.text("cd".repeat(32)))),
                binding.copy(
                    contract =
                        contract.copy(target = contract.target.copy(qualifiedIdentity = f.base.text("fixture.other")))
                ),
                binding.copy(contract = contract.copy(owner = contract.owner.copy(range = f.range(1, 100)))),
            )
        for (changed in changes) {
            assertFalse(f.transfer.copy(evidence = proof(changed)).admitsEvidence())
            val changedFact = f.condition.copy(binding = changed)
            val target = f.transfer.target.copy(conditions = f.base.bounded(listOf(changedFact)))
            assertFalse(f.transfer.copy(target = target).admitsEvidence())
            assertFalse(f.transfer.target.hasSameValueIdentity(target))
        }
        for (changed in
            listOf(
                f.condition.copy(useRange = f.range(30, 91)),
                f.condition.copy(origin = f.condition.origin.copy(range = f.range(51, 60))),
                f.condition.copy(origin = f.condition.origin.copy(role = ImpactValueRoleDocument.LocalRead)),
                f.condition.copy(origin = f.condition.origin.copy(valuePath = EmptyImpactValuePath)),
            )) assertFalse(
            f.transfer
                .copy(target = f.transfer.target.copy(conditions = f.base.bounded(listOf(changed))))
                .admitsEvidence()
        )
        val malformed = binding.copy(contract = contract.copy(classDigest = f.base.text("AB".repeat(32))))
        assertFalse(malformed.admits(binding.enclosing))
    }

    @Test
    fun `condition and source proof cannot be erased at result or downstream`() {
        val f = DeclaredDocumentFixture()
        assertFalse(f.transfer.copy(evidence = ImpactTransferEvidenceDocument.Direct).admitsEvidence())
        assertFalse(
            f.transfer.copy(target = f.transfer.target.copy(conditions = EmptyImpactConditions)).admitsEvidence()
        )
        val target = f.transfer.target.copy(role = ImpactValueRoleDocument.Return)
        val downstream = ImpactCompilerTransferDocument(f.transfer.target, target, ImpactTransferKindDocument.RETURN)
        assertTrue(downstream.admitsEvidence())
        assertFalse(downstream.copy(target = target.copy(conditions = EmptyImpactConditions)).admitsEvidence())
    }

    private fun proof(binding: ImpactDeclaredCallbackValueBindingDocument) =
        ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.DeclaredCallbackResult(binding))
}

private class DeclaredDocumentFixture {
    val base = ImpactFindingFixture()
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("fixture.select"),
            CompilerReceiverDocument.Absent,
            base.bounded(emptyList()),
            base.bounded(listOf(base.text("()->T"), base.text("()->T"))),
            CompilerTypeParameterCountDocument.parse(1).declaredValue(),
        )
    private val target =
        ImpactDeclaredCallbackTargetDocument(
            base.declaration.copy(
                file = base.text("jar:///effects.jar!/fixture/EffectsKt.class"),
                compilerIdentity = CompilerSymbolEvidenceDocument.fromSignature(signature).declaredValue().identity,
            ),
            signature.qualifiedIdentity,
            ImpactDeclaredCallbackReceiverDocument.Absent,
            signature.contextReceivers,
            signature.valueParameters,
            base.count(1),
        )
    val binding =
        ImpactDeclaredCallbackValueBindingDocument(
            base.declaration,
            ImpactCapturedBodyDocument(
                base.declaration.file,
                range(40, 80),
                base.text("canonical-signature-sha256-v1|" + "c".repeat(64)),
            ),
            ImpactCallbackDependencyContractDocument(
                base.declaration.basis,
                base.declaration.file,
                range(30, 90),
                ImpactCapturedBodyDocument(
                    base.declaration.file,
                    base.declaration.range,
                    base.declaration.compilerIdentity,
                ),
                target,
                base.offset(0),
                base.text("ab".repeat(32)),
                QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT,
                QueryCallbackDependencyInvocationKindDocument.AT_MOST_ONCE,
            ),
            ImpactDeclaredCallbackResultEffectDocument.RETURNS_RESULT_OF,
        )
    private val source =
        ImpactValueSiteReferenceDocument(
            base.declaration,
            range(50, 60),
            ImpactValueRoleDocument.Return,
            ImpactExecutionOwnerDocument.DeclaredCallback(binding),
            base.bounded(listOf(ImpactValuePathSegmentDocument.Element)),
        )
    val condition =
        ImpactValueConditionDocument.DeclaredCallbackResult(
            ImpactValueConditionOriginDocument(
                ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
                source.range,
                source.role,
                source.valuePath,
                source.callStack,
            ),
            binding.contract.occurrenceRange,
            binding,
        )
    val transfer =
        ImpactCompilerTransferDocument(
            source,
            source.copy(
                range = binding.contract.occurrenceRange,
                role = ImpactValueRoleDocument.ExpressionResult,
                executionOwner = ImpactExecutionOwnerDocument.Declaration,
                conditions = base.bounded(listOf(condition)),
            ),
            ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.DeclaredCallbackResult(binding)),
        )

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(base.offset(start), base.offset(end))
}

private fun <T, F> Refinement<T, F>.declaredValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid declared fixture: $failure")
    }
