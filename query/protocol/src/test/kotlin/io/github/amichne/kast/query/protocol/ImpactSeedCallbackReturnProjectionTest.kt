package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactSeedCallbackReturnProjectionTest {
    @Test
    fun `a genuine empty callback return keeps full evidence and its supplying context through projection`() {
        val f = ImpactCallbackProjectionCase()
        val old = f.returned()
        val path = ValueAccessPath.fromSegments(listOf(ValuePathSegment.AnyElement)).value()
        val unguarded = old.source.withState(valuePath = path, callStack = ValueCallStack.Empty).value()
        val predicates =
            ValuePredicates.fromCompiler(
                    listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(unguarded)))
                )
                .value()
        val source = unguarded.withState(predicates = predicates).value()
        val target = old.target.withState(valuePath = path, predicates = predicates).value()
        val transfer =
            ValueTransfer.fromCompiler(
                    source,
                    target,
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    ValueSourceTransfer.fromCompiler(ValueSourceOperation.SeedCallbackReturn(f.frame)),
                )
                .value()
        val projected = transfer.impactDocument(f.references).value()
        assertTrue(projected.admitsEvidence())
        assertTrue(projected.source.callStack.values.isEmpty())
        assertEquals(listOf(ImpactValuePathSegmentDocument.AnyElement), projected.source.valuePath.values)
        assertEquals(projected.source.valuePath, projected.target.valuePath)
        assertEquals(projected.source.predicates, projected.target.predicates)
        assertTrue(projected.source.predicates.values.single() is ImpactValuePredicateDocument.NonNull)
        assertEquals(1, projected.target.callStack.values.size)
        val operation =
            (projected.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.SeedCallbackReturn
        val callback = operation.frame.target as ImpactCallTargetDocument.Callback
        assertEquals(
            callback.supplyingFrame.invocation.range,
            projected.target.callStack.values.single().invocation.range,
        )
        assertEquals(f.frame.invocation.range.startInclusive, projected.target.range.start.value)
        assertEquals(f.frame.invocation.range.endExclusive, projected.target.range.end.value)
        val encoded =
            Json.encodeToJsonElement(ImpactCompilerTransferDocument.serializer(), projected)
                .jsonObject
                .getValue("evidence")
                .jsonObject
                .getValue("operation")
                .jsonObject
        assertEquals(setOf("type", "frame"), encoded.keys)
        assertEquals("SEED_CALLBACK_RETURN", encoded.getValue("type").jsonPrimitive.content)
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
