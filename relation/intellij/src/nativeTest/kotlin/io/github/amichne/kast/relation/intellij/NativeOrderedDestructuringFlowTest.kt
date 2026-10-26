package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationLimit
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** All invoked siblings must complete before a local component result can reach its exact use. */
class NativeOrderedDestructuringFlowTest : NativeDestructuringFixtureTest() {
    override val fixtureSdkName = "ordered-destructuring-flow-jdk"

    fun testOriginalPayloadCrossesOnlyTheReorderedAuthoredComponentAndExactUse() {
        val fixture = controls()
        val origin =
            fixture.argumentExpression(owner("reorderedComponents"), "ReorderedComponents", "destructuringOrigin()")
        val argument = next(fixture, resolve(fixture, origin), ValueTransferKind.ARGUMENT)
        val stored = next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
        val receivers = componentReceivers(fixture, stored)
        val selected =
            componentReceiver(
                fixture = fixture,
                receivers = receivers,
                owner = "ReorderedComponents",
                component = "component1",
            )
        val unrelated =
            componentReceiver(
                fixture = fixture,
                receivers = receivers,
                owner = "ReorderedComponents",
                component = "component2",
            )
        assertExhausted(step(fixture, next(fixture, unrelated, ValueTransferKind.SOURCE_CALL_ENTRY)))
        val returned = componentReturn(fixture, selected)
        assertEquals(marked(fixture, "reorderedComponents", "left-binding").anchor, returned.range)
        assertTrue(returned.callStack.frames.isEmpty())
        assertTrue(returned.valuePath.segments.isEmpty())
        val binding = next(fixture, returned, ValueTransferKind.LOCAL_BINDING)
        val use = next(fixture, binding, ValueTransferKind.LOCAL_READ)
        assertEquals(marked(fixture, "reorderedComponents", "left-use").anchor, use.range)
        assertFalse(marked(fixture, "reorderedComponents", "right-use").anchor == use.range)
        assertTrue(
            revalidate(
                fixture = fixture,
                function = "reorderedComponents",
                marker = "left-binding",
                role = ValueSiteRoleClaim.LocalBinding,
            )
                is ValueModelSiteRead.Revalidated
        )
    }

    fun testConstantSiblingCompletesWithoutAcquiringThePayload() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "normalConstantSibling",
                constructor = "AuthoredComponents",
                literal = "origin",
            )
        val receivers = componentReceivers(fixture, stored)
        val selected =
            componentReceiver(
                fixture = fixture,
                receivers = receivers,
                owner = "AuthoredComponents",
                component = "component1",
            )
        val constant =
            componentReceiver(
                fixture = fixture,
                receivers = receivers,
                owner = "AuthoredComponents",
                component = "component2",
            )
        assertExhausted(step(fixture, next(fixture, constant, ValueTransferKind.SOURCE_CALL_ENTRY)))
        val returned = componentReturn(fixture, selected)
        assertEquals(marked(fixture, "normalConstantSibling", "binding").anchor, returned.range)
        val use = next(fixture, next(fixture, returned, ValueTransferKind.LOCAL_BINDING), ValueTransferKind.LOCAL_READ)
        assertEquals(marked(fixture, "normalConstantSibling", "use").anchor, use.range)
    }

    fun testLaterThrowingComponentPreventsEarlierPayloadUse() {
        val fixture = controls()
        assertUnsupported(
            step(
                fixture,
                stored(
                    fixture = fixture,
                    function = "laterThrowingComponents",
                    constructor = "LaterThrowingComponents",
                    literal = "origin",
                ),
            ),
            ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION,
        )
    }

    fun testUnusedNamedThrowingComponentStillPreventsEarlierPayloadUse() {
        val fixture = controls()
        assertUnsupported(
            step(
                fixture,
                stored(
                    fixture = fixture,
                    function = "unusedThrowingComponent",
                    constructor = "LaterThrowingComponents",
                    literal = "origin",
                ),
            ),
            ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION,
        )
    }

    fun testUnclassifiedSiblingCannotBorrowTheSelectedComponentsNormalReturn() {
        val fixture = controls()
        assertUnsupported(
            step(
                fixture,
                stored(
                    fixture = fixture,
                    function = "unclassifiedSibling",
                    constructor = "UnclassifiedComponents",
                    literal = "origin",
                ),
            ),
            ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION,
        )
    }

    fun testSkippedLaterThrowingComponentDoesNotPreventPayloadUse() {
        val fixture = controls()
        val returned =
            authoredReturn(
                fixture,
                stored(
                    fixture = fixture,
                    function = "skippedLaterThrowingComponent",
                    constructor = "LaterThrowingComponents",
                    literal = "origin",
                ),
            )
        val use = next(fixture, next(fixture, returned, ValueTransferKind.LOCAL_BINDING), ValueTransferKind.LOCAL_READ)
        assertEquals(marked(fixture, "skippedLaterThrowingComponent", "use").anchor, use.range)
    }

    fun testSeedReturnCannotReleaseBindingsBeforeLaterThrowingComponents() {
        val fixture = controls()
        val expression =
            fixture.markedExpression(
                PacketDeclaration(CONTROLS, "LaterThrowingComponents", "component1"),
                "later-return",
            )
        val read = step(fixture, next(fixture, resolve(fixture, expression), ValueTransferKind.RETURN))
        assertEquals(setOf(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN), read.obligations.map { it.cause }.toSet())
        assertEquals(
            listOf(marked(fixture, "skippedLaterThrowingComponent", "binding").anchor),
            read.transfers.map { it.target.range },
        )
    }

    fun testRestoredBindingsAndResultsCannotBypassLaterThrowingComponents() {
        val fixture = controls()
        val entry = marked(fixture, "unusedThrowingComponent", "binding")
        val component =
            fixture.callableDeclaration(PacketDeclaration(CONTROLS, "LaterThrowingComponents", "component1"))
        val receiver = ValueSiteRoleClaim.Receiver(entry.anchor, component, ValueReceiverKind.DISPATCH)
        for (claim in listOf(ValueSiteRoleClaim.ExpressionResult, ValueSiteRoleClaim.LocalBinding, receiver)) {
            assertEquals(
                ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                revalidate(fixture = fixture, function = "unusedThrowingComponent", marker = "binding", role = claim),
            )
        }
    }

    fun testDirectResultBindingAndReceiverCannotBypassSiblingAdmission() {
        val fixture = controls()
        val entry = marked(fixture, "unusedThrowingComponent", "binding")
        val enclosing = RelationEndpoint.subject(entry.enclosing)
        val component =
            fixture.callableDeclaration(PacketDeclaration(CONTROLS, "LaterThrowingComponents", "component1"))
        val call =
            ValueInvocation.fromCompiler(enclosing, entry.anchor, RelationEndpoint.subject(component)).nativeRefined()
        val receiver = ValueRole.Receiver(call, ValueReceiverKind.DISPATCH)
        for (role in listOf(ValueRole.ExpressionResult, ValueRole.LocalBinding, receiver)) {
            val site = ValueSite.fromCompiler(enclosing, entry.anchor, role).nativeRefined()
            assertUnsupported(step(fixture, site), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    fun testOrderedSiblingAdmissionConsumesTheRestorationWorkGrant() {
        val fixture = controls()
        val resources = fixture.budget.resources as ResourceBudget.Bounded
        val budget =
            fixture.budget.copy(resources = resources.copy(workUnitLimit = WorkUnitLimit.parse(3L).nativeRefined()))
        assertEquals(
            ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED),
            revalidate(
                fixture = fixture,
                function = "reorderedComponents",
                marker = "left-binding",
                role = ValueSiteRoleClaim.LocalBinding,
                budget = budget,
            ),
        )
    }

    private fun revalidate(
        fixture: NativePacketValueFixture,
        function: String,
        marker: String,
        role: ValueSiteRoleClaim,
        budget: RelationBudget = fixture.budget,
    ): ValueModelSiteRead {
        val expression = marked(fixture, function, marker)
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    role = role,
                    budget = budget,
                )
                .nativeRefined()
        return runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
    }

    private fun componentReceivers(fixture: NativePacketValueFixture, source: ValueSite): List<ValueSite> {
        val read = step(fixture, source)
        assertTrue("Unexpected sibling obligations: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(List(2) { ValueTransferKind.RECEIVER }, read.transfers.map { it.kind })
        return read.transfers.map { it.target }
    }

    private fun componentReceiver(
        fixture: NativePacketValueFixture,
        receivers: List<ValueSite>,
        owner: String,
        component: String,
    ): ValueSite {
        val expected = fixture.callableDeclaration(PacketDeclaration(CONTROLS, owner, component))
        return receivers.single {
            (it.role as ValueRole.Receiver).call.callable.compilerIdentity == expected.compilerIdentity
        }
    }

    private fun componentReturn(fixture: NativePacketValueFixture, receiver: ValueSite): ValueSite {
        var site = receiver
        for (kind in
            listOf(
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
            )) {
            site = next(fixture, site, kind)
        }
        return site
    }
}
