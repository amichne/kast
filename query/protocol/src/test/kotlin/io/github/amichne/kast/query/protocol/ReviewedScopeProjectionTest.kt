package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactModelExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity
import io.github.amichne.kast.protocol.contract.toContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedScopeProjectionTest {
    @Test
    fun `scope input output preserve actual let call full formal proof and matched frame`() {
        val f = ReviewedScopeProjectionCase()
        val input =
            f.input.impactDocument(f.references).modelValue() as ImpactValueModelApplicationDocument.ScopeCallbackInput
        val output =
            f.output.impactDocument(f.references).modelValue()
                as ImpactValueModelApplicationDocument.ScopeCallbackReturn
        val proof = input.frame.target as ImpactCallTargetDocument.ReviewedCallback
        assertEquals(f.argument.range.startInclusive, proof.argument.range.start.value)
        assertEquals(f.argument.range.endExclusive, proof.argument.range.end.value)
        assertEquals(f.argument.file.stableValue, proof.argument.file.value)
        assertEquals(f.host.fingerprint.value, proof.formalCallable.fingerprint.value)
        assertEquals(f.host.scope, f.formal.callable.scope)
        assertEquals(f.formal.name.value, proof.formal.name.value)
        assertEquals(0, proof.formal.index.value)
        val body = (proof.formalExecution as ImpactModelExecutionDocument.Anonymous).body
        val expected =
            (f.formal.executionOwner.body as io.github.amichne.kast.relation.contract.ValueExecutionBody.Anonymous)
                .callable
        assertEquals(expected.compilerIdentity.value, body.compilerEvidence.identity)
        assertEquals(
            expected.signature.protocolDocument(),
            (body.compilerEvidence.signature.toContract() as WireDocumentConversion.Converted).value,
        )
        assertTrue(input.source.matchesSite(f.source, f.references))
        assertTrue(input.target.matchesSite(f.input.target, f.references))
        assertTrue(output.source.matchesSite(f.output.source, f.references))
        assertTrue(output.target.matchesSite(f.output.target, f.references))
        val inputFrame =
            io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(listOf(input.frame)).modelValue()
        val outputFrame =
            io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(listOf(output.frame)).modelValue()
        assertTrue(inputFrame.hasSameCallStackIdentity(outputFrame))
        assertEquals(input.source.predicates, output.target.predicates)
    }

    @Test
    fun `modeled application tags remain distinct and a changed proof cannot match the stack`() {
        val f = ReviewedScopeProjectionCase()
        val input =
            f.input.impactDocument(f.references).modelValue() as ImpactValueModelApplicationDocument.ScopeCallbackInput
        val output = f.output.impactDocument(f.references).modelValue()
        for ((application, tag) in listOf(input to "SCOPE_CALLBACK_INPUT", output to "SCOPE_CALLBACK_RETURN")) {
            val encoded =
                Json.encodeToJsonElement(ImpactValueModelApplicationDocument.serializer(), application).jsonObject
            assertEquals(setOf("type", "source", "target", "binding", "frame"), encoded.keys)
            assertEquals(tag, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(
                "REVIEWED_CALLBACK",
                encoded
                    .getValue("frame")
                    .jsonObject
                    .getValue("target")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive
                    .content,
            )
        }
        val proof = input.frame.target as ImpactCallTargetDocument.ReviewedCallback
        val changed =
            proof.copy(
                argument = proof.argument.copy(range = proof.argument.range.copy(start = input.source.range.start))
            )
        val frames = input.target.callStack
        val wrong =
            io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                    listOf(input.frame.copy(target = changed))
                )
                .modelValue()
        assertFalse(frames.hasSameCallStackIdentity(wrong))
    }
}
