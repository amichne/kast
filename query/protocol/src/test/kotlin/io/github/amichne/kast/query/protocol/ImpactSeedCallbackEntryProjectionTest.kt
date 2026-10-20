package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.EmptyImpactCallStack
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached callback proofs exercise projection and shape admission without native resolution. */
class ImpactSeedCallbackEntryProjectionTest {
    @Test
    fun `empty-stack callback seed encodes its distinct operation and both genuine frames`() {
        val f = ImpactCallbackProjectionCase()
        val entry = projected(f)
        assertTrue(entry.admitsEvidence())
        assertEquals(listOf(ImpactValuePathSegmentDocument.AnyElement), entry.source.valuePath.values)
        assertEquals(entry.source.valuePath, entry.target.valuePath)
        assertEquals(entry.source.predicates, entry.target.predicates)
        assertTrue(entry.source.predicates.values.single() is ImpactValuePredicateDocument.NonNull)
        assertTrue(entry.source.callStack.values.isEmpty())
        assertEquals(2, entry.target.callStack.values.size)
        val operation =
            Json.encodeToJsonElement(ImpactCompilerTransferDocument.serializer(), entry)
                .jsonObject
                .getValue("evidence")
                .jsonObject
                .getValue("operation")
                .jsonObject
        assertEquals(setOf("type", "frame", "position"), operation.keys)
        assertEquals("SEED_CALLBACK_ENTER", operation.getValue("type").jsonPrimitive.content)
        val proof = operation.getValue("frame").jsonObject.getValue("target").jsonObject
        assertEquals(setOf("type", "flow", "selected", "supplyingFrame"), proof.keys)
        assertEquals("CALLBACK", proof.getValue("type").jsonPrimitive.content)
        val seed =
            (entry.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.SeedCallbackEnter
        assertFalse(
            entry
                .copy(
                    evidence =
                        ImpactTransferEvidenceDocument.Source(
                            ImpactSourceOperationDocument.Enter(seed.frame, seed.position)
                        )
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `seed operation rejects a nonempty source missing supplier and opaque target`() {
        val f = ImpactCallbackProjectionCase()
        val entry = projected(f)
        assertTrue(entry.admitsEvidence())
        val nonempty = f.argument.impactDocument(f.references).value().callStack
        assertFalse(entry.copy(source = entry.source.copy(callStack = nonempty)).admitsEvidence())
        assertFalse(
            entry
                .copy(
                    target =
                        entry.target.copy(
                            callStack = BoundedProtocolList.create(entry.target.callStack.values.drop(1)).value()
                        )
                )
                .admitsEvidence()
        )
        val source = entry.evidence as ImpactTransferEvidenceDocument.Source
        val operation = source.operation as ImpactSourceOperationDocument.SeedCallbackEnter
        val opaque = operation.copy(frame = operation.frame.copy(target = ImpactCallTargetDocument.Direct))
        assertFalse(entry.copy(evidence = source.copy(operation = opaque)).admitsEvidence())
        assertFalse(entry.copy(target = entry.target.copy(callStack = EmptyImpactCallStack)).admitsEvidence())
    }

    @Test
    fun `seed operation rejects another actual invoke and supplying call`() {
        val entry = projected(ImpactCallbackProjectionCase())
        val actual = entry.source.role as ImpactValueRoleDocument.FunctionArgument
        val changed =
            actual.invocation.copy(range = actual.invocation.range.copy(start = ProtocolOffset.parse(231).value()))
        assertFalse(entry.copy(source = entry.source.copy(role = actual.copy(invocation = changed))).admitsEvidence())
        val evidence = entry.evidence as ImpactTransferEvidenceDocument.Source
        val operation = evidence.operation as ImpactSourceOperationDocument.SeedCallbackEnter
        val callback = operation.frame.target as ImpactCallTargetDocument.Callback
        val supplier = callback.supplyingFrame
        val changedSupplier =
            supplier.copy(
                invocation =
                    supplier.invocation.copy(
                        range = supplier.invocation.range.copy(start = ProtocolOffset.parse(31).value())
                    )
            )
        val changedFrame = operation.frame.copy(target = callback.copy(supplyingFrame = changedSupplier))
        assertFalse(
            entry.copy(evidence = evidence.copy(operation = operation.copy(frame = changedFrame))).admitsEvidence()
        )
    }

    @Test
    fun `seed projection preserves flow qualification and rejects an unknown operation tag`() {
        val entry = projected(ImpactCallbackProjectionCase(qualified = true))
        val operation =
            (entry.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.SeedCallbackEnter
        val target = operation.frame.target as ImpactCallTargetDocument.Callback
        assertEquals(listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED), target.flow.obligations)
        assertTrue(entry.admitsEvidence())
        val encoded = Json.encodeToString(ImpactCompilerTransferDocument.serializer(), entry)
        val unknown = encoded.replace("SEED_CALLBACK_ENTER", "UNREVIEWED_CALLBACK_ENTER")
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactCompilerTransferDocument.serializer(), unknown)
        }
    }

    private fun projected(f: ImpactCallbackProjectionCase): ImpactCompilerTransferDocument {
        val path = ValueAccessPath.fromSegments(listOf(ValuePathSegment.AnyElement)).value()
        val unguarded = f.argument.withState(valuePath = path, callStack = ValueCallStack.Empty).value()
        val predicates =
            ValuePredicates.fromCompiler(
                    listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(unguarded)))
                )
                .value()
        val source = unguarded.withState(predicates = predicates).value()
        val target = f.formal.withState(valuePath = path, predicates = predicates).value()
        val position: ValueFormalPosition = (f.formal.role as ValueRole.Formal).position
        return ValueTransfer.fromCompiler(
                source,
                target,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.SeedCallbackEnter(f.frame, position)),
            )
            .value()
            .impactDocument(f.references)
            .value()
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
