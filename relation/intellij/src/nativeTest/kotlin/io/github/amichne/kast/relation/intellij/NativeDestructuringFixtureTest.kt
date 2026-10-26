package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Shared native reads carry only each case's explicit fixture and source site. */
abstract class NativeDestructuringFixtureTest : NativePacketValueFixtureTest() {
    protected fun controls() = packetFixture(listOf(CONTROLS))

    protected fun marked(fixture: NativePacketValueFixture, function: String, marker: String) =
        fixture.markedExpression(owner(function), marker)

    protected fun owner(function: String) = PacketDeclaration(CONTROLS, "", function)

    protected fun stored(
        fixture: NativePacketValueFixture,
        function: String,
        constructor: String,
        literal: String,
    ): ValueSite {
        val expression = fixture.argumentExpression(owner(function), constructor, "\"$literal\"")
        val argument = next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
        return next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
    }

    protected fun authoredReturn(fixture: NativePacketValueFixture, stored: ValueSite): ValueSite {
        var site = stored
        for (kind in
            listOf(
                ValueTransferKind.RECEIVER,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
            )) {
            site = next(fixture, site, kind)
        }
        return site
    }

    protected fun resolve(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain = RelationRequest.start(expression.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue("Native source was not resolved: $result", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    protected fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, source)
        assertEquals(
            "Missing $kind at ${source.range}: ${read.obligations}",
            listOf(kind),
            read.transfers.map { it.kind },
        )
        assertTrue("Unexpected obligations: ${read.obligations}", read.obligations.isEmpty())
        return read.transfers.single().target
    }

    protected fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native flow was not observed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    protected fun assertExhausted(step: ValueFlowStep) {
        assertTrue("Unexpected transfers: ${step.transfers}", step.transfers.isEmpty())
        assertTrue("Unexpected obligations: ${step.obligations}", step.obligations.isEmpty())
    }

    protected fun assertUnsupported(step: ValueFlowStep, cause: ValueFlowUnsupportedCause) {
        assertTrue("Unsupported flow invented transfers", step.transfers.isEmpty())
        assertEquals(listOf(cause), step.obligations.map { it.cause }.distinct())
    }

    protected companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
    }
}
