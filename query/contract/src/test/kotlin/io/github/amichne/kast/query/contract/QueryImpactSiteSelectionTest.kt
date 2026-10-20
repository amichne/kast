package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueConstructorStorage
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactSiteSelectionTest {
    @Test
    fun `requested occurrences preserve both entered mapper routes and their actual value states`() {
        val f = Fixture()
        val requested =
            listOf(
                f.requested(f.constructorArgument.withState(callStack = ValueCallStack.Empty).value()),
                f.requested(f.destination.withState(valuePath = ValueAccessPath.Whole).value()),
            )
        val accounting =
            QueryImpactSiteAccounting.fromRequestedSites(requested, f.paths, f.base.domain.boundary).value()
        for (entry in accounting) {
            val reached = assertInstanceOf(QueryImpactSiteOutcome.Reached::class.java, entry.outcome)
            assertEquals(listOf(0, 1), reached.pathOrdinals.map { it.value })
        }
        val firstFormal = f.paths[0].steps.map { it.target }.first { it.role is ValueRole.Formal }
        val secondFormal = f.paths[1].steps.map { it.target }.first { it.role is ValueRole.Formal }
        assertNotEquals(firstFormal.callStack, secondFormal.callStack)
        assertNotEquals(firstFormal.identity, secondFormal.identity)
        assertEquals(listOf(f.destination, f.destination), f.paths.map { it.destination })
        assertEquals(f.fieldPath, f.paths[0].destination.valuePath)
        assertEquals(1, f.constructorArgument.callStack.frames.size)
        assertTrue(
            QueryImpactRetainedGraph().site(f.destination) >
                QueryImpactRetainedGraph().site(f.destination.withState(valuePath = ValueAccessPath.Whole).value())
        )
    }

    @Test
    fun `field specific native selection keeps exact value equality and cannot match a sibling field`() {
        val f = Fixture()
        val requested =
            listOf(
                f.requested(f.destination),
                f.requested(f.destination.withState(valuePath = f.siblingPath).value()),
            )
        val accounting =
            QueryImpactSiteAccounting.fromRequestedSites(requested, f.paths, f.base.domain.boundary).value()
        assertEquals(
            listOf(0, 1),
            (accounting[0].outcome as QueryImpactSiteOutcome.Reached).pathOrdinals.map { it.value },
        )
        assertEquals(QueryImpactSiteOutcome.RelationshipUnproven, accounting[1].outcome)
        assertEquals(
            (f.field.declaration.signature as CanonicalCompilerSignature.Property).returnType,
            (f.sibling.declaration.signature as CanonicalCompilerSignature.Property).returnType,
        )
    }

    private class Fixture {
        val base = QueryImpactLedgerFixture()
        private val mapper = endpoint("mapper", 300, 400, listOf("String"))
        private val constructor = endpoint("Box", 500, 550, listOf("String"))
        private val sink = endpoint("consume", 700, 750, listOf("String"))
        private val zero = ValueArgumentPosition.parse(0).value()
        private val formal = ValueFormalParameter.fromCompiler(mapper, zero, name("input"), range(310, 320)).value()
        private val storageParameter =
            ValueFormalParameter.fromCompiler(constructor, zero, name("value"), range(510, 520)).value()
        val field = ValueField.fromCompiler(endpoint("Box.value", 510, 520, property = true)).value()
        val sibling = ValueField.fromCompiler(endpoint("Box.other", 530, 540, property = true)).value()
        val fieldPath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).value()
        val siblingPath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(sibling))).value()
        private val storage = ValueConstructorStorage.fromCompiler(storageParameter, field).value()
        private val constructorCall = ValueInvocation.fromCompiler(mapper, range(335, 360), constructor).value()
        private val sinkCall = ValueInvocation.fromCompiler(base.owner, range(130, 170), sink).value()
        val destination =
            ValueSite.fromCompiler(
                    base.owner,
                    range(140, 145),
                    ValueRole.Argument(sinkCall, zero),
                    valuePath = fieldPath,
                )
                .value()
        lateinit var constructorArgument: ValueSite
        val paths = listOf(route(20), route(80))

        private fun route(offset: Int): QueryImpactPath {
            val invocation = ValueInvocation.fromCompiler(base.owner, range(offset, offset + 40), mapper).value()
            val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(mapper)).value()
            val stack = ValueCallStack.Empty.enter(frame).value()
            val argument =
                ValueSite.fromCompiler(base.owner, range(offset + 5, offset + 10), ValueRole.Argument(invocation, zero))
                    .value()
            val position = ValueFormalPosition.Parameter(formal)
            val entered =
                ValueSite.fromCompiler(mapper, formal.range, ValueRole.Formal(position), callStack = stack).value()
            val constructedArgument =
                ValueSite.fromCompiler(
                        mapper,
                        range(340, 345),
                        ValueRole.Argument(constructorCall, zero),
                        callStack = stack,
                    )
                    .value()
            constructorArgument = constructedArgument
            val constructed = constructorCall.resultSite().withState(valuePath = fieldPath, callStack = stack).value()
            val returned = returnedSite(stack)
            val result = invocation.resultSite().withState(valuePath = fieldPath).value()
            val edges =
                listOf(
                    edge(base.producer, argument, ValueTransferKind.ARGUMENT),
                    edge(
                        argument,
                        entered,
                        ValueTransferKind.SOURCE_CALL_ENTRY,
                        ValueSourceOperation.Enter(frame, position),
                    ),
                    edge(entered, constructedArgument, ValueTransferKind.ARGUMENT),
                    edge(
                        constructedArgument,
                        constructed,
                        ValueTransferKind.CONSTRUCTOR_FIELD,
                        ValueSourceOperation.ConstructField(constructorCall, storage),
                    ),
                    edge(constructed, returned, ValueTransferKind.RETURN),
                    edge(returned, result, ValueTransferKind.SOURCE_CALL_RETURN, ValueSourceOperation.Return(frame)),
                    edge(result, destination, ValueTransferKind.ARGUMENT),
                )
            return path(edges)
        }

        private fun returnedSite(stack: ValueCallStack): ValueSite =
            ValueSite.fromCompiler(
                    mapper,
                    range(380, 390),
                    ValueRole.Return,
                    valuePath = fieldPath,
                    callStack = stack,
                )
                .value()

        private fun path(edges: List<ValueTransfer>): QueryImpactPath =
            QueryImpactPath.fromEvidence(
                    base.producer,
                    edges.map(QueryImpactStep::Compiler),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(
                        ValueFlowObligation(destination, ValueFlowUnsupportedCause.UNMODELED_CALL)
                    ),
                )
                .value()

        fun requested(site: ValueSite): QueryImpactRequestedSite {
            val role = site.role as ValueRole.Argument
            val owner = site.enclosing as RelationEndpoint.Resolved
            val callable = role.call.callable as RelationEndpoint.Resolved
            val request =
                ValueSiteRevalidationRequest.create(
                        SymbolSelector.issue(owner.lease, owner.scope, owner.evidence),
                        site.range,
                        ValueSiteRoleClaim.Argument(
                            role.call.range,
                            SymbolSelector.issue(callable.lease, callable.scope, callable.evidence),
                            role.position,
                        ),
                        base.domain.budget,
                    )
                    .value()
            return QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, site).value(),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        }

        private fun edge(
            source: ValueSite,
            target: ValueSite,
            kind: ValueTransferKind,
            operation: ValueSourceOperation? = null,
        ): ValueTransfer =
            ValueTransfer.fromCompiler(
                    source,
                    target,
                    kind,
                    operation?.let(ValueSourceTransfer::fromCompiler) ?: ValueTransferEvidence.Direct,
                )
                .value()

        private fun endpoint(
            name: String,
            start: Int,
            end: Int,
            parameters: List<String> = emptyList(),
            property: Boolean = false,
        ): RelationEndpoint {
            val signature =
                if (property) CanonicalCompilerSignature.property("fixture.$name", null, emptyList(), "String").value()
                else CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), parameters, 0).value()
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        base.file,
                        start,
                        end,
                        name.substringAfterLast('.'),
                        "fixture.$name",
                        if (property) CompilerSymbolKind.PROPERTY else CompilerSymbolKind.FUNCTION,
                        signature,
                    )
                    .value()
            return RelationEndpoint.resolve(base.lease, base.scope, evidence).value()
        }

        private fun name(value: String) = SymbolDiscoveryCandidateName.parse(value).value()

        private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
