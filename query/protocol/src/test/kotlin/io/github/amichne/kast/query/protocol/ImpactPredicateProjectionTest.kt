package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactPredicateProjectionTest {
    @Test
    fun `site projection keeps a predicate on its exact tested value`() {
        val f = ImpactPathProjectionFixture()
        val subject = ValuePredicateSubject.fromCompiler(f.producer)
        val predicates = ValuePredicates.fromCompiler(listOf(ValuePredicate.NonNull.fromCompiler(subject))).value()
        val guarded = f.producer.withState(predicates = predicates).value()
        val projected = guarded.impactDocument(CanonicalQueryReferences()).value()
        val predicate = projected.predicates.values.single() as ImpactValuePredicateDocument.NonNull
        assertEquals(f.producer.range.startInclusive, predicate.subject.range.start.value)
        assertEquals(projected.enclosing, predicate.subject.enclosing)
        val encoded =
            Json.encodeToJsonElement(
                    io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument.serializer(),
                    projected,
                )
                .jsonObject
        val encodedSubject = encoded.getValue("predicates").jsonArray.single().jsonObject.getValue("subject").jsonObject
        assertFalse("predicates" in encodedSubject)
        assertEquals(
            "NON_NULL",
            encoded.getValue("predicates").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `implicit formal projection keeps the body anchor and explicit implicit origin`() {
        val f = ImpactPathProjectionFixture()
        val range = ExactDeclarationTextRange.parse(40, 80).value()
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(f.owner.file, range),
                    null,
                    emptyList(),
                    listOf("String"),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(f.owner.file, range, signature).value()
        val execution = ValueExecutionOwner.fromCompiler(f.owner, body, f.lease.identity).value()
        val parameter =
            ValueFormalParameter.fromCompilerImplicit(
                    execution,
                    ValueArgumentPosition.parse(0).value(),
                    SymbolDiscoveryCandidateName.parse("it").value(),
                )
                .value()
        val projected = parameter.impactDocument().value()
        assertEquals(ImpactFormalOriginDocument.Implicit, projected.origin)
        assertTrue(projected.executionOwner is ImpactExecutionOwnerDocument.Anonymous)
        assertEquals(40, projected.range.start.value)
        assertEquals(80, projected.range.end.value)
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
