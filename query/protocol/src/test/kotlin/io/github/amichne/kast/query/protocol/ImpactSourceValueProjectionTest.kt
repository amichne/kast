package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactSourceValueProjectionTest {
    private val projectionReferences = CanonicalQueryReferences()

    @Test
    fun `native entry and return projection preserves the exact field formal and call frame`() {
        val f = SourceValueProjectionFixture()
        val entry = f.entry.impactDocument(references = projectionReferences).value()
        assertTrue(entry.admitsEvidence())
        assertEquals(ImpactTransferKindDocument.SOURCE_CALL_ENTRY, entry.kind)
        val projectedField = entry.target.valuePath.values.single() as ImpactValuePathSegmentDocument.Field
        assertEquals(f.field.declaration.impactDeclaration().value(), projectedField.declaration)
        val projectedParameter =
            ((entry.target.role as ImpactValueRoleDocument.Formal).position as ImpactFormalPositionDocument.Parameter)
                .parameter
        assertEquals("request", projectedParameter.name.value)
        assertEquals(0, projectedParameter.index.value)
        assertEquals(215, projectedParameter.range.start.value)
        assertEquals(
            ImpactCallOccurrenceDocument.Declared(f.base.call.impactDocument().value()),
            entry.target.callStack.values.single().invocation,
        )
        assertTrue(entry.target.matchesSite(f.formal, references = projectionReferences))
        val exit = f.returned().impactDocument(references = projectionReferences).value()
        assertTrue(exit.admitsEvidence())
        assertEquals(emptyList<ImpactCallFrameDocument>(), exit.target.callStack.values)
        assertEquals(entry.target.valuePath, exit.target.valuePath)
        val wrongFrame =
            entry.target.callStack.values
                .single()
                .copy(
                    invocation =
                        entry.target.callStack.values.single().let { frame ->
                            io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument.Declared(
                                (frame.invocation
                                        as
                                        io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument.Declared)
                                    .invocation
                                    .copy(range = range(35, 45).impactDocument().value())
                            )
                        }
                )
        assertFalse(entry.copy(target = entry.target.copy(callStack = bounded(listOf(wrongFrame)))).admitsEvidence())
        val sibling = field(f.base, "sessionToken", 530).declaration.impactDeclaration().value()
        assertFalse(
            entry
                .copy(
                    target =
                        entry.target.copy(valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Field(sibling))))
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `caller supplied nonempty value state cannot become native revalidation evidence`() {
        val f = ImpactPathProjectionFixture()
        val field = field(f, "destination", 500)
        val site =
            f.producer
                .withState(valuePath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).value())
                .value()
                .impactDocument(references = projectionReferences)
                .value()
        val owner = f.owner as RelationEndpoint.Resolved
        val selector = SymbolSelector.issue(owner.lease, owner.scope, owner.evidence)
        val grant =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(10).value(),
                    WorkUnitLimit.parse(100).value(),
                    ElapsedTimeLimitMillis.parse(1000).value(),
                ),
                RelationByteLimit.parse(100000).value(),
            )
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailureCode.UNSUPPORTED_BOUNDARY_SITE),
            site.siteRequest(listOf(selector), grant),
        )
    }
}

private class SourceValueProjectionFixture {
    val base = ImpactPathProjectionFixture()
    val field = field(base, "destination", 500)
    private val path = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).value()
    private val frame = ValueCallFrame.direct(base.call, ValueExecutionOwner.declaration(base.call.callable)).value()
    private val parameter =
        ValueFormalParameter.fromCompiler(
                base.call.callable,
                ValueArgumentPosition.parse(0).value(),
                SymbolDiscoveryCandidateName.parse("request").value(),
                range(215, 220),
            )
            .value()
    private val position = ValueFormalPosition.Parameter(parameter)
    private val argument =
        ValueSite.fromCompiler(
                base.owner,
                range(31, 35),
                ValueRole.Argument(base.call, parameter.position),
                valuePath = path,
            )
            .value()
    val formal =
        ValueSite.fromCompiler(
                base.call.callable,
                parameter.range,
                ValueRole.Formal(position),
                valuePath = path,
                callStack = ValueCallStack.Empty.enter(frame).value(),
            )
            .value()
    val entry =
        ValueTransfer.fromCompiler(
                argument,
                formal,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(frame, position)),
            )
            .value()

    fun returned(): ValueTransfer {
        val returned =
            ValueSite.fromCompiler(
                    base.call.callable,
                    range(240, 245),
                    ValueRole.Return,
                    valuePath = path,
                    callStack = formal.callStack,
                )
                .value()
        val result = base.call.resultSite().withState(valuePath = path).value()
        return ValueTransfer.fromCompiler(
                returned,
                result,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
            )
            .value()
    }
}

private fun field(f: ImpactPathProjectionFixture, name: String, start: Int): ValueField {
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                f.owner.file,
                start,
                start + 10,
                name,
                "fixture.Request.$name",
                CompilerSymbolKind.PROPERTY,
                CanonicalCompilerSignature.property("fixture.Request.$name", null, emptyList(), "String").value(),
            )
            .value()
    return ValueField.fromCompiler(RelationEndpoint.resolve(f.lease, f.owner.scope, evidence).value()).value()
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
