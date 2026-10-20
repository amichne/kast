package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedScopeDocumentTest {
    @Test
    fun `reviewed callback frame keeps full binding formal and anonymous proof`() {
        val f = ReviewedScopeDocumentCase()
        val encoded = Json.encodeToJsonElement(ImpactCallFrameDocument.serializer(), f.frame).jsonObject
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "binding", "argument", "formal", "formalCallable", "formalExecution"), target.keys)
        assertEquals("REVIEWED_CALLBACK", target.getValue("type").jsonPrimitive.content)
        assertEquals(
            "LET",
            target.getValue("binding").jsonObject.getValue("model").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals("ANONYMOUS", target.getValue("formalExecution").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(f.formal, f.target.formal)
        assertEquals(f.binding.enclosing, f.target.formalCallable)
        assertTrue(f.frame.admits(f.basis))
    }

    @Test
    fun `reviewed input and matched return preserve path predicates and exact push pop`() {
        val f = ReviewedScopeDocumentCase()
        assertEquals(f.source.valuePath, f.entered.valuePath)
        assertEquals(f.source.predicates, f.entered.predicates)
        assertEquals(listOf(f.frame), f.entered.callStack.values)
        assertTrue(f.input.admitsValueModel(f.basis))
        assertTrue(f.output.admitsValueModel(f.basis))
        assertEquals(f.source.valuePath, f.result.valuePath)
        assertEquals(f.source.predicates, f.result.predicates)
        assertTrue(f.result.callStack.values.isEmpty())
    }

    @Test
    fun `reviewed input rejects dispatch receiver changed formal and missing push`() {
        val f = ReviewedScopeDocumentCase()
        val dispatch =
            f.source.copy(role = ImpactValueRoleDocument.Receiver(f.call, ImpactReceiverKindDocument.DISPATCH))
        assertFalse(f.input.copy(source = dispatch).admitsValueModel(f.basis))
        assertFalse(f.input.copy(target = f.entered.copy(callStack = EmptyImpactCallStack)).admitsValueModel(f.basis))
        val changed = f.target.copy(formal = f.formal.copy(index = f.offset(1)))
        assertFalse(f.frame.copy(target = changed).admits(f.basis))
        assertFalse(
            f.frame
                .copy(target = f.target.copy(argument = f.target.argument.copy(range = f.range(53, 66))))
                .admits(f.basis)
        )
    }

    @Test
    fun `reviewed return rejects another call foreign proof and direct receiver result`() {
        val f = ReviewedScopeDocumentCase()
        assertFalse(f.output.copy(source = f.source).admitsValueModel(f.basis))
        assertFalse(f.output.copy(target = f.result.copy(range = f.range(43, 70))).admitsValueModel(f.basis))
        val foreign =
            (f.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(8).scopeValue()
            )
        val artifact = f.binding.artifact.copy(basis = foreign)
        assertFalse(f.input.copy(binding = f.binding.copy(artifact = artifact)).admitsValueModel(f.basis))
        val changed =
            f.target.formalCallable.copy(
                domain = f.target.formalCallable.domain.copy(libraries = QueryDiscoveryInclusionPolicyDocument.INCLUDE)
            )
        assertFalse(f.frame.copy(target = f.target.copy(formalCallable = changed)).admits(f.basis))
    }

    @Test
    fun `reviewed callback frames cannot grant native source transfer evidence`() {
        val f = ReviewedScopeDocumentCase()
        val position = ImpactFormalPositionDocument.Parameter(f.formal)
        val entry = ImpactSourceOperationDocument.Enter(f.frame, position)
        val returned = ImpactSourceOperationDocument.Return(f.frame)
        val seedReturn = ImpactSourceOperationDocument.SeedReturn(f.frame)
        assertFalse(entry.admits(f.source, f.entered, ImpactTransferKindDocument.SOURCE_CALL_ENTRY))
        assertFalse(returned.admits(f.returned, f.result, ImpactTransferKindDocument.SOURCE_CALL_RETURN))
        assertFalse(
            seedReturn.admits(
                f.returned.copy(callStack = EmptyImpactCallStack),
                f.result,
                ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            )
        )
    }

    @Test
    fun `renewed anonymous handles keep identity but unknown target and application kinds reject`() {
        val f = ReviewedScopeDocumentCase()
        val execution = f.target.formalExecution as ImpactModelExecutionDocument.Anonymous
        val renewed =
            execution.copy(
                body =
                    execution.body.copy(
                        occurrence = execution.body.occurrence.copy(candidateSelector = "renewed:reviewed-literal")
                    )
            )
        assertTrue(f.frame.hasSameFrameIdentity(f.frame.copy(target = f.target.copy(formalExecution = renewed))))
        val frameText = Json.encodeToString(ImpactCallFrameDocument.serializer(), f.frame)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactCallFrameDocument.serializer(),
                frameText.replace("\"REVIEWED_CALLBACK\"", "\"OPAQUE_CALLBACK\""),
            )
        }
        for (application in listOf(f.input, f.output)) {
            val text = Json.encodeToString(ImpactValueModelApplicationDocument.serializer(), application)
            val encoded = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("type", "source", "target", "binding", "frame"), encoded.keys)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(
                    ImpactValueModelApplicationDocument.serializer(),
                    text.replace("\"SCOPE_CALLBACK_", "\"OPAQUE_CALLBACK_"),
                )
            }
        }
    }
}

private fun <T> io.github.amichne.kast.kernel.Refinement<T, *>.scopeValue(): T =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Reviewed scope primitive rejected: $failure")
    }
