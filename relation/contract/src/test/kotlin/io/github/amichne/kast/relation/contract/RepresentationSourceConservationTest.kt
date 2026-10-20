package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepresentationSourceConservationTest {
    @Test
    fun `field projection keeps history without assigning the object representation to its field`() {
        val f = Fixture()
        val source = f.origin()
        val field = flowField("Box.value", 320, 340)
        val target = ValueSite.fromCompiler(f.owner, flowRange(70, 80), ValueRole.ExpressionResult).flowRefined()
        val read =
            ValueTransfer.fromCompiler(
                    source = source.site,
                    target = target,
                    kind = ValueTransferKind.FIELD_READ,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.ReadField(field)),
                )
                .flowRefined()

        val projected = source.transfer(read).flowRefined()

        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            projected.branches.single().current,
        )
        assertEquals(
            source.branches.single().history + RepresentationHistory.CompilerTransfer(read),
            projected.branches.single().history,
        )
        assertEquals(target, projected.site)
    }

    @Test
    fun `constructor storage keeps history without assigning the argument representation to the constructed object`() {
        val f = Fixture()
        val constructor = boxConstructor()
        val call = ValueInvocation.fromCompiler(f.owner, flowRange(20, 60), constructor).flowRefined()
        val field = flowField("Box.value", 320, 340)
        val parameter =
            ValueFormalParameter.fromCompiler(
                    callable = constructor,
                    position = f.zero,
                    name = name("value"),
                    range = field.declaration.range,
                )
                .flowRefined()
        val storage = ValueConstructorStorage.fromCompiler(parameter, field).flowRefined()
        val argument =
            ValueSite.fromCompiler(f.owner, flowRange(30, 40), ValueRole.Argument(call, f.zero)).flowRefined()
        val source = f.at(argument)
        val target = call.resultSite().withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined()
        val construction =
            ValueTransfer.fromCompiler(
                    source = argument,
                    target = target,
                    kind = ValueTransferKind.CONSTRUCTOR_FIELD,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.ConstructField(call, storage)),
                )
                .flowRefined()

        val constructed = source.transfer(construction).flowRefined()

        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            constructed.branches.single().current,
        )
        assertEquals(
            source.branches.single().history + RepresentationHistory.CompilerTransfer(construction),
            constructed.branches.single().history,
        )
        assertEquals(target.valuePath, constructed.site.valuePath)
    }

    @Test
    fun `matched source entry and return preserve the value representation and exact history`() {
        val f = Fixture()
        val call = SourceCallFixture(f)
        val source = f.at(call.argument)

        val entered = source.transfer(call.entry).flowRefined()
        val result = entered.transfer(call.returnValue).flowRefined().transfer(call.exit).flowRefined()

        assertEquals(source.branches.single().current, entered.branches.single().current)
        assertEquals(source.branches.single().current, result.branches.single().current)
        assertEquals(
            source.branches.single().history +
                listOf(call.entry, call.returnValue, call.exit).map(RepresentationHistory::CompilerTransfer),
            result.branches.single().history,
        )
    }

    private class SourceCallFixture(f: Fixture) {
        val callee = flowEndpoint(name = "identity", start = 300, end = 400, parameters = listOf("String"))
        val call = ValueInvocation.fromCompiler(f.owner, flowRange(20, 60), callee).flowRefined()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(callee)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        val parameter =
            ValueFormalParameter.fromCompiler(
                    callable = callee,
                    position = f.zero,
                    name = name("input"),
                    range = flowRange(310, 320),
                )
                .flowRefined()
        val position = ValueFormalPosition.Parameter(parameter)
        val argument =
            ValueSite.fromCompiler(f.owner, flowRange(30, 40), ValueRole.Argument(call, f.zero)).flowRefined()
        val formal =
            ValueSite.fromCompiler(
                    enclosing = callee,
                    range = parameter.range,
                    role = ValueRole.Formal(position),
                    callStack = stack,
                )
                .flowRefined()
        val returned =
            ValueSite.fromCompiler(
                    enclosing = callee,
                    range = flowRange(350, 360),
                    role = ValueRole.Return,
                    callStack = stack,
                )
                .flowRefined()
        val entry =
            ValueTransfer.fromCompiler(
                    source = argument,
                    target = formal,
                    kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(frame, position)),
                )
                .flowRefined()
        val returnValue = ValueTransfer.fromCompiler(formal, returned, ValueTransferKind.RETURN).flowRefined()
        val exit =
            ValueTransfer.fromCompiler(
                    source = returned,
                    target = call.resultSite(),
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
                )
                .flowRefined()
    }

    private class Fixture {
        val owner = flowEndpoint("owner", 0, 200)
        val zero = ValueArgumentPosition.parse(0).flowRefined()
        private val seed =
            ValueInvocation.fromCompiler(owner, flowRange(5, 15), flowEndpoint("produce", 600, 650)).flowRefined()
        private val identity =
            ContractModelIdentity(
                id("representation"),
                ModelVersion.parse(1).flowRefined(),
                id("review:source-conservation"),
            )
        private val domain = RepresentationDomain.admit(identity, listOf(id("OPAQUE"))).flowRefined()
        private val origin =
            RepresentationRule.Origin.admit(
                    ModelRuleReference(identity, id("origin")),
                    bind(seed.callable, ModelValuePosition.Result),
                    domain.state(id("OPAQUE")).flowRefined(),
                )
                .flowRefined()

        fun origin(): RepresentationEvidence =
            RepresentationEvidence.origin(seed.resultSite(), seed, origin).flowRefined()

        fun at(site: ValueSite): RepresentationEvidence {
            val origin = origin()
            return origin
                .transfer(ValueTransfer.fromCompiler(origin.site, site, ValueTransferKind.ARGUMENT).flowRefined())
                .flowRefined()
        }

        private fun bind(endpoint: RelationEndpoint, position: ModelValuePosition): ExactModelCallablePosition =
            ExactModelCallablePosition.admit(
                    ModelCallableReference(
                        basis = endpoint.lease.identity,
                        callable = endpoint.compilerIdentity,
                        file = endpoint.file,
                        range = endpoint.range,
                        position = position,
                    ),
                    RevalidatedRelationEndpoint.validate(endpoint, (endpoint as RelationEndpoint.Resolved).evidence)
                        .flowRefined(),
                )
                .flowRefined()
    }
}

private fun boxConstructor(): RelationEndpoint {
    val declaration =
        flowEndpoint(name = "Box", start = 300, end = 400, parameters = listOf("String")) as RelationEndpoint.Resolved
    return RelationEndpoint.resolve(
            declaration.lease,
            declaration.scope,
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = declaration.file,
                    rawStartInclusive = 300,
                    rawEndExclusive = 400,
                    rawName = "Box",
                    rawQualifiedIdentity = "fixture.Box",
                    kind = CompilerSymbolKind.CONSTRUCTOR,
                    signature = declaration.signature,
                )
                .flowRefined(),
        )
        .flowRefined()
}

private fun id(value: String) = ModelIdentifier.parse(value).flowRefined()

private fun name(value: String) = SymbolDiscoveryCandidateName.parse(value).flowRefined()
