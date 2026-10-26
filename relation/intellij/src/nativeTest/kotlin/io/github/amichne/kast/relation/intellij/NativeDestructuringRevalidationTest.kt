package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationLimit
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Exact implicit results can be reacquired only with bounded compiler proof of their admitted declaration. */
class NativeDestructuringRevalidationTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "destructuring-revalidation-jdk"

    fun testGeneratedAndAuthoredResultsRevalidateAtTheirExactEntries() {
        val fixture = controls()
        val generated =
            stored(
                fixture = fixture,
                function = "generatedComponents",
                constructor = "ComponentPacket",
                literal = "first-value",
            )
        val authored =
            stored(
                fixture = fixture,
                function = "oneAuthoredComponent",
                constructor = "AuthoredComponents",
                literal = "second-value",
            )
        val generatedResult = next(fixture, generated, ValueTransferKind.FIELD_READ)
        val authoredResult = authoredReturn(fixture, authored)
        for ((function, site) in
            listOf("generatedComponents" to generatedResult, "oneAuthoredComponent" to authoredResult)) {
            val result = revalidateResult(fixture, function, site)
            assertTrue("Component result was not revalidated: $result", result is ValueModelSiteRead.Revalidated)
            assertEquals(site.identity, (result as ValueModelSiteRead.Revalidated).position.site.identity)
        }
    }

    fun testComponentResultRevalidationBoundsSiblingProofWork() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "generatedComponents",
                constructor = "ComponentPacket",
                literal = "first-value",
            )
        val site = next(fixture, stored, ValueTransferKind.FIELD_READ)
        val resources = fixture.budget.resources as ResourceBudget.Bounded
        val budget =
            fixture.budget.copy(resources = resources.copy(workUnitLimit = WorkUnitLimit.parse(3L).nativeRefined()))
        assertEquals(
            ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED),
            revalidateResult(fixture = fixture, function = "generatedComponents", site = site, budget = budget),
        )
    }

    private fun controls() = packetFixture(listOf(CONTROLS))

    private fun owner(function: String) = PacketDeclaration(CONTROLS, "", function)

    private fun stored(
        fixture: NativePacketValueFixture,
        function: String,
        constructor: String,
        literal: String,
    ): ValueSite {
        val expression = fixture.argumentExpression(owner(function), constructor, "\"$literal\"")
        val argument = next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
        return next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
    }

    private fun authoredReturn(fixture: NativePacketValueFixture, stored: ValueSite): ValueSite {
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

    private fun resolve(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
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

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, source)
        assertEquals(
            "Missing $kind at ${source.range}: ${read.obligations}",
            listOf(kind),
            read.transfers.map { it.kind },
        )
        assertTrue("Unexpected obligations: ${read.obligations}", read.obligations.isEmpty())
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
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

    private fun revalidateResult(
        fixture: NativePacketValueFixture,
        function: String,
        site: ValueSite,
        budget: RelationBudget = fixture.budget,
    ): ValueModelSiteRead {
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = fixture.callableDeclaration(owner(function)),
                    anchor = site.range,
                    role = ValueSiteRoleClaim.ExpressionResult,
                    budget = budget,
                )
                .nativeRefined()
        return runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
    }
}
