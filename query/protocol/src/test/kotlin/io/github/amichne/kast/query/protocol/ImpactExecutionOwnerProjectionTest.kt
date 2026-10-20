package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.admitsExecutionOwner
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactExecutionOwnerProjectionTest {
    private val projectionReferences = CanonicalQueryReferences()

    @Test
    fun `declaration execution is explicit in output and old references keep their declaration meaning`() {
        val f = ImpactPathProjectionFixture()
        val site = f.producer.impactDocument(references = projectionReferences).value()
        val invocation = f.call.impactDocument().value()
        val encoded = Json.encodeToJsonElement(ImpactValueSiteReferenceDocument.serializer(), site).jsonObject
        assertEquals(
            setOf("enclosing", "range", "role", "executionOwner", "valuePath", "callStack", "predicates"),
            encoded.keys,
        )
        assertEquals(
            "DECLARATION",
            encoded.getValue("executionOwner").jsonObject.getValue("type").jsonPrimitive.content,
        )
        val oldSite = LegacySite(site.enclosing, site.range, site.role)
        assertEquals(
            site,
            Json.decodeFromString(
                ImpactValueSiteReferenceDocument.serializer(),
                Json.encodeToString(LegacySite.serializer(), oldSite),
            ),
        )
        val oldInvocation = LegacyInvocation(invocation.range, invocation.callable)
        assertEquals(
            invocation,
            Json.decodeFromString(
                ImpactInvocationReferenceDocument.serializer(),
                Json.encodeToString(LegacyInvocation.serializer(), oldInvocation),
            ),
        )
    }

    @Test
    fun `anonymous invocation and result preserve the source body and reject another execution owner`() {
        val f = ImpactPathProjectionFixture()
        val execution = execution(f, 10, 100)
        val call = ValueInvocation.fromCompiler(f.owner, range(30, 40), f.call.callable, execution).value()
        val site = call.resultSite().impactDocument(references = projectionReferences).value()
        val invocation = call.impactDocument().value()
        val projected = site.executionOwner as ImpactExecutionOwnerDocument.Anonymous
        val body = (execution.body as io.github.amichne.kast.relation.contract.ValueExecutionBody.Anonymous).callable
        assertEquals(site.enclosing.basis, projected.body.basis)
        assertEquals(site.enclosing.file, projected.body.file)
        assertEquals(10, projected.body.range.start.value)
        assertEquals(100, projected.body.range.end.value)
        assertEquals(body.compilerIdentity.value, projected.body.compilerIdentity.value)
        assertEquals(site.executionOwner, invocation.executionOwner)
        assertTrue(site.matchesSite(call.resultSite(), references = projectionReferences))
        assertTrue(site.admitsExecutionOwner())
        assertFalse(
            site
                .copy(executionOwner = ImpactExecutionOwnerDocument.Declaration)
                .matchesSite(call.resultSite(), references = projectionReferences)
        )
        val other = ValueInvocation.fromCompiler(f.owner, call.range, call.callable, execution(f, 15, 110)).value()
        assertNotEquals(call.identity, other.identity)
        assertFalse(site.matchesSite(other.resultSite(), references = projectionReferences))
        val encoded = Json.encodeToJsonElement(ImpactValueSiteReferenceDocument.serializer(), site).jsonObject
        assertEquals("ANONYMOUS", encoded.getValue("executionOwner").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("basis", "file", "range", "compilerIdentity"),
            encoded.getValue("executionOwner").jsonObject.getValue("body").jsonObject.keys,
        )
    }

    @Test
    fun `receiver transfers preserve the exact invocation and anonymous owner`() {
        val f = ImpactPathProjectionFixture()
        val execution = execution(f, 10, 100)
        val call = ValueInvocation.fromCompiler(f.owner, range(40, 80), f.call.callable, execution).value()
        val source = ValueSite.fromCompiler(f.owner, range(30, 40), ValueRole.ExpressionResult, execution).value()
        val receiver =
            ValueSite.fromCompiler(
                    f.owner,
                    range(40, 50),
                    ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                    execution,
                )
                .value()
        val transfer = ValueTransfer.fromCompiler(source, receiver, ValueTransferKind.RECEIVER).value()
        val projected = transfer.impactDocument(references = projectionReferences).value()
        assertEquals(ImpactTransferKindDocument.RECEIVER, projected.kind)
        assertEquals(
            call.impactDocument().value(),
            (projected.target.role as ImpactValueRoleDocument.Receiver).invocation,
        )
        assertEquals(projected.source.executionOwner, projected.target.executionOwner)
        assertEquals(
            ImpactReceiverKindDocument.EXTENSION,
            (projected.target.role as ImpactValueRoleDocument.Receiver).kind,
        )
        val wrongSlot =
            projected.target.copy(
                role =
                    ImpactValueRoleDocument.Receiver(call.impactDocument().value(), ImpactReceiverKindDocument.DISPATCH)
            )
        assertFalse(wrongSlot.matchesSite(receiver, references = projectionReferences))
        assertTrue(projected.target.matchesSite(receiver, references = projectionReferences))
        assertTrue(projected.target.admitsExecutionOwner())
    }

    @Test
    fun `foreign or misplaced anonymous bodies fail structural admission`() {
        val f = ImpactPathProjectionFixture()
        val call = ValueInvocation.fromCompiler(f.owner, range(30, 40), f.call.callable, execution(f, 10, 100)).value()
        val site = call.resultSite().impactDocument(references = projectionReferences).value()
        val owner = site.executionOwner as ImpactExecutionOwnerDocument.Anonymous
        val foreign =
            ImpactPathProjectionFixture(generation = 2)
                .producer
                .impactDocument(references = projectionReferences)
                .value()
                .enclosing
                .basis
        assertFalse(
            site.copy(executionOwner = owner.copy(body = owner.body.copy(basis = foreign))).admitsExecutionOwner()
        )
        assertFalse(
            site
                .copy(
                    executionOwner = owner.copy(body = owner.body.copy(range = range(50, 80).impactDocument().value()))
                )
                .admitsExecutionOwner()
        )
    }

    private fun execution(f: ImpactPathProjectionFixture, start: Int, end: Int): ValueExecutionOwner {
        val range = range(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(f.owner.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(f.owner.file, range, signature).value()
        return ValueExecutionOwner.fromCompiler(f.owner, body, f.lease.identity).value()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()
}

@Serializable
private data class LegacySite(
    val enclosing: ImpactDeclarationReferenceDocument,
    val range: ImpactSourceRangeDocument,
    val role: ImpactValueRoleDocument,
)

@Serializable
private data class LegacyInvocation(
    val range: ImpactSourceRangeDocument,
    val callable: ImpactDeclarationReferenceDocument,
)

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
