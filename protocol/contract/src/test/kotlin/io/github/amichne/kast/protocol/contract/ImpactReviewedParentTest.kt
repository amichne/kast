package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedParentTest {
    @Test
    fun `nested callback rejects foreign parent sibling and erased result owner`() {
        val parent = parent()
        val base = ImpactReviewedValueFixture()
        val invalid =
            listOf(
                parent.copy(enclosing = parent.enclosing.copy(range = base.range(1, 120))),
                parent.copy(body = parent.body.copy(range = base.range(91, 100))),
                parent.copy(
                    contract =
                        parent.contract.copy(
                            basis =
                                (parent.contract.basis as ImpactSemanticBasisDocument.Published).copy(
                                    generation = (ImpactEvidenceRevisionDocument.parse(8) as Refinement.Refined).value
                                )
                        )
                ),
            )
        invalid.forEach { assertFalse(nested(it).admitsEvidence()) }
        val valid = nested(parent)
        assertFalse(
            valid
                .copy(target = valid.target.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration))
                .admitsEvidence()
        )
    }

    @Test
    fun `nested callback keeps its declared parent without claiming invocation`() {
        val parent = parent()
        assertTrue(parent.admits(parent.enclosing), "The independent parent fixture must be admissible")
        val transfer = nested(parent)
        assertTrue(transfer.admitsEvidence())
        assertTrue(transfer.target.admitsValueState())
        val authority =
            Json.parseToJsonElement(Json.encodeToString(transfer))
                .jsonObject
                .getValue("binding")
                .jsonObject
                .getValue("authority")
                .jsonObject
        assertEquals(
            "DECLARED_CALLBACK",
            authority.getValue("parent").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "NOT_ESTABLISHED",
            authority.getValue("invocationEvidence").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `declaration parent is encoded even with default serialization`() {
        val encoded = Json.parseToJsonElement(Json.encodeToString(ImpactReviewedMappedFixture().binding)).jsonObject
        assertEquals("DECLARATION", encoded.getValue("parent").jsonObject.getValue("type").jsonPrimitive.content)
    }

    private fun parent(): ImpactDeclaredCallbackValueBindingDocument {
        val fixture = ImpactReviewedScopeFixture()
        return fixture.declared.copy(
            body = fixture.body.copy(range = fixture.range(20, 95)),
            contract = fixture.declared.contract.copy(occurrenceRange = fixture.range(10, 100)),
        )
    }

    private fun nested(parent: ImpactDeclaredCallbackValueBindingDocument): ImpactReviewedValueTransferDocument {
        val fixture = ImpactReviewedMappedFixture {
            it.copy(parent = ImpactReviewedCallbackParentDocument.DeclaredCallback(parent))
        }
        return fixture.transfer.copy(
            target =
                fixture.transfer.target.copy(executionOwner = ImpactExecutionOwnerDocument.DeclaredCallback(parent))
        )
    }
}
