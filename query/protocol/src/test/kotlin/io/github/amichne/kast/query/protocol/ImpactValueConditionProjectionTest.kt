package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ImpactCastKindDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalLifetimeEndDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.hasSameValueIdentity
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCastKind
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactValueConditionProjectionTest {
    @Test
    fun `stable local transfer and detached identity retain the exact defined version for both lifetimes`() {
        val f = ImpactPathProjectionFixture()
        fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).conditionValue()
        fun wireRange(start: Int, end: Int) =
            ImpactSourceRangeDocument(
                ProtocolOffset.parse(start).conditionValue(),
                ProtocolOffset.parse(end).conditionValue(),
            )
        for ((end, expectedEnd) in
            listOf(
                ValueLocalVersion.End.NextWrite(range(60, 80), range(70, 80)) to
                    ImpactLocalLifetimeEndDocument.NextWrite(wireRange(60, 80), wireRange(70, 80)),
                ValueLocalVersion.End.BlockEnd(range(5, 195)) to
                    ImpactLocalLifetimeEndDocument.BlockEnd(wireRange(5, 195)),
            )) {
            val transfer = stableLocalTransfer(f.owner, end)
            val target = transfer.target
            val document = transfer.impactDocument().conditionValue()
            val expectedVersion =
                ImpactLocalVersionDocument.Defined(
                    wireRange(10, 30),
                    wireRange(5, 195),
                    wireRange(10, 30),
                    wireRange(20, 30),
                    expectedEnd,
                )
            val projected =
                assertInstanceOf(
                    ImpactValueConditionDocument.StableLocal::class.java,
                    document.target.conditions.values.single(),
                )
            assertEquals(expectedVersion, projected.version)
            assertEquals(expectedVersion, document.source.localVersion)
            assertEquals(ImpactLocalVersionDocument.Unversioned, document.target.localVersion)
            assertEquals(ImpactValueRoleDocument.LocalBinding, projected.origin.role)
            assertEquals(wireRange(10, 30), projected.origin.range)
            assertEquals(wireRange(35, 36), projected.useRange)
            assertEquals("kotlin/Any?", projected.originalType.value)
            assertEquals("fixture/Request", projected.refinedType.value)
            assertEquals(ImpactTransferKindDocument.LOCAL_READ, document.kind)
            assertTrue(document.admitsEvidence())
            assertEquals(document.target, target.identity.impactDocument().conditionValue())
            val detached = f.site(90, 91, ValueRole.Return).withState(conditions = target.conditions).conditionValue()
            val detachedDocument = detached.impactDocument().conditionValue()
            assertEquals(detachedDocument, detached.identity.impactDocument().conditionValue())
            assertEquals(listOf(projected), detachedDocument.conditions.values)
            assertFalse(
                detachedDocument.hasSameValueIdentity(
                    f.site(90, 91, ValueRole.Return).impactDocument().conditionValue()
                )
            )
        }
    }

    @Test
    fun `site and identity projection retain originating state after the occurrence changes`() {
        val f = ImpactPathProjectionFixture()
        val frame = ValueCallFrame.direct(f.call, ValueExecutionOwner.declaration(f.call.callable)).conditionValue()
        val source =
            f.site(10, 11, ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)))
                .withState(
                    ValueAccessPath.Whole.prepend(ValuePathSegment.Element).conditionValue(),
                    ValueCallStack.Empty.enter(frame).conditionValue(),
                )
                .conditionValue()
        val condition =
            ValueCondition.StableReceiver.fromCompiler(
                    source,
                    ExactDeclarationTextRange.parse(20, 21).conditionValue(),
                    ValueReceiverKind.EXTENSION,
                    CanonicalCompilerType.fromCompiler("kotlin/Any?").conditionValue(),
                    CanonicalCompilerType.fromCompiler("fixture/Request").conditionValue(),
                )
                .conditionValue()
        val conditions = ValueConditions.Unconditional.requiring(condition).conditionValue()
        val target =
            ValueSite.fromCompiler(
                    f.owner,
                    ExactDeclarationTextRange.parse(80, 90).conditionValue(),
                    ValueRole.Return,
                    conditions = conditions,
                )
                .conditionValue()
        val document = target.impactDocument().conditionValue()
        assertEquals(document, target.identity.impactDocument().conditionValue())
        val projected = document.conditions.values.single() as ImpactValueConditionDocument.StableReceiver
        assertEquals(10, projected.origin.range.start.value)
        assertEquals(11, projected.origin.range.end.value)
        assertEquals(20, projected.useRange.start.value)
        assertEquals(21, projected.useRange.end.value)
        assertEquals("kotlin/Any?", projected.originalType.value)
        assertEquals("fixture/Request", projected.refinedType.value)
        assertEquals(ImpactReceiverKindDocument.EXTENSION, projected.kind)
        assertEquals(
            ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)),
            projected.origin.role,
        )
        assertEquals(listOf(ImpactValuePathSegmentDocument.Element), projected.origin.valuePath.values)
        assertEquals(listOf(frame.impactDocument().conditionValue()), projected.origin.callStack.values)
        assertEquals(source.executionOwner.impactDocument().conditionValue(), projected.origin.execution)
        assertFalse(document.hasSameValueIdentity(f.site(80, 90, ValueRole.Return).impactDocument().conditionValue()))
    }

    @Test
    fun `structural ingress refuses claimed conditions before declaration lookup`() {
        val f = ImpactPathProjectionFixture()
        val source = f.site(10, 11, ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)))
        val condition =
            ValueCondition.StableReceiver.fromCompiler(
                    source,
                    ExactDeclarationTextRange.parse(20, 21).conditionValue(),
                    ValueReceiverKind.EXTENSION,
                    CanonicalCompilerType.fromCompiler("kotlin/Any?").conditionValue(),
                    CanonicalCompilerType.fromCompiler("fixture/Request").conditionValue(),
                )
                .conditionValue()
        val document =
            source
                .withState(conditions = ValueConditions.Unconditional.requiring(condition).conditionValue())
                .conditionValue()
                .impactDocument()
                .conditionValue()
        val budget =
            RelationBudget(
                ResourceBudget.RunnerControlled,
                RelationByteLimit.parse(1000).conditionValue(),
                ResultLimit.parse(1).conditionValue(),
            )
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailureCode.UNSUPPORTED_BOUNDARY_SITE),
            document.siteRequest(emptyList(), budget),
        )
    }

    @Test
    fun `safe and unsafe cast transfer projections preserve the operand prerequisite`() {
        val f = ImpactPathProjectionFixture()
        val source = f.site(10, 11, ValueRole.LocalRead)
        val range = ExactDeclarationTextRange.parse(10, 30).conditionValue()
        val kinds =
            listOf(
                ValueCastKind.UNSAFE to ImpactCastKindDocument.UNSAFE,
                ValueCastKind.SAFE to ImpactCastKindDocument.SAFE,
            )
        for ((kind, expectedKind) in kinds) {
            val fact =
                ValueCondition.CastSuccess.fromCompiler(
                        source,
                        range,
                        kind,
                        CanonicalCompilerType.fromCompiler("kotlin/Any?").conditionValue(),
                        CanonicalCompilerType.fromCompiler("kotlin/collections/List<kotlin/String>").conditionValue(),
                    )
                    .conditionValue()
            val target =
                ValueSite.fromCompiler(
                        f.owner,
                        range,
                        ValueRole.ExpressionResult,
                        conditions = source.conditions.requiring(fact).conditionValue(),
                    )
                    .conditionValue()
            val transfer = ValueTransfer.fromCompiler(source, target, ValueTransferKind.CAST_RESULT).conditionValue()
            val document = transfer.impactDocument().conditionValue()
            assertEquals(ImpactTransferKindDocument.CAST_RESULT, document.kind)
            assertTrue(document.admitsEvidence())
            assertEquals(target.identity.impactDocument().conditionValue(), document.target)
            val projected = document.target.conditions.values.single() as ImpactValueConditionDocument.CastSuccess
            assertEquals(expectedKind, projected.kind)
            assertEquals("kotlin/Any?", projected.originalType.value)
            assertEquals("kotlin/collections/List<kotlin/String>", projected.targetType.value)
            assertEquals(ImpactValueRoleDocument.LocalRead, projected.origin.role)
            assertEquals(10, projected.origin.range.start.value)
            assertEquals(11, projected.origin.range.end.value)
            assertEquals(30, projected.useRange.end.value)
        }
    }
}

private fun stableLocalTransfer(owner: RelationEndpoint, end: ValueLocalVersion.End): ValueTransfer {
    fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).conditionValue()
    val version =
        ValueLocalVersion.Defined.fromCompiler(
                ValueExecutionOwner.declaration(owner),
                range(10, 30),
                range(5, 195),
                range(10, 30),
                range(20, 30),
                end,
            )
            .conditionValue()
    val source =
        ValueSite.fromCompiler(owner, version.definition, ValueRole.LocalBinding, localVersion = version)
            .conditionValue()
    val fact =
        ValueCondition.StableLocal.fromCompiler(
                source,
                range(35, 36),
                CanonicalCompilerType.fromCompiler("kotlin/Any?").conditionValue(),
                CanonicalCompilerType.fromCompiler("fixture/Request").conditionValue(),
            )
            .conditionValue()
    val target =
        ValueSite.fromCompiler(
                owner,
                fact.useRange,
                ValueRole.LocalRead,
                conditions = source.conditions.requiring(fact).conditionValue(),
            )
            .conditionValue()
    return ValueTransfer.fromCompiler(source, target, ValueTransferKind.LOCAL_READ).conditionValue()
}

private fun <T, F> Refinement<T, F>.conditionValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
