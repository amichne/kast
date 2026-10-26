@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLoopResult
import io.github.amichne.kast.relation.contract.ValueLoopResultPhase
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.resolution.KaForLoopCall
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Original producers, not synthetic implicit results, establish loop item provenance. */
class NativeLoopInputTest : NativeLoopInputFixtureTest() {
    override val fixtureSdkName = "loop-input-jdk"

    fun testCompilerSelectsExactIteratorGuardAndNextAtDistinctLoopSites() {
        packetFixture(listOf(CONTROLS))
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            val loops =
                mapOf(
                        "firstLoop" to "RepeatingLoop",
                        "secondLoop" to "RepeatingLoop",
                        "emptyLoop" to "EmptyLoop",
                        "throwingLoop" to "ThrowingLoop",
                        "selectedFieldLoop" to "SelectingLoop",
                    )
                    .map { (function, owner) ->
                        selectedLoop(file, function, owner)
                    }
            assertEquals(loops.size, loops.map { range(it) }.toSet().size)
        }
    }

    fun testOriginalProducerCrossesIteratorNextAndReorderedComponentToExactConsumer() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "firstLoop")
        assertRangeArrival(fixture, steps, "firstLoop")
        listOf(
                declaration("iterator", "RepeatingLoop"),
                declaration("next", "RepeatingLoop"),
                declaration("component1", "LoopParts"),
            )
            .forEach { selected ->
                assertTrue("Missing selected source entry: $selected", entries(fixture, steps, selected).isNotEmpty())
            }
        assertPayloadUse(fixture, steps, "firstLoop")
        assertComplete(steps)
    }

    fun testFalseGuardDoesNotInvokeThrowingNextOrAnyComponent() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "emptyLoop")
        assertRangeArrival(fixture, steps, "emptyLoop")
        assertTrue(entries(fixture, steps, declaration("iterator", "EmptyLoop")).isNotEmpty())
        assertNoItemTransfer(fixture, steps, "EmptyLoop")
        assertNoUse(fixture, steps, "emptyLoop")
        assertComplete(steps)
    }

    fun testThrowingGuardRetainsFailureWithoutInvokingNextOrComponents() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "throwingLoop")
        assertRangeArrival(fixture, steps, "throwingLoop")
        assertTrue(entries(fixture, steps, declaration("iterator", "ThrowingLoop")).isNotEmpty())
        assertNoItemTransfer(fixture, steps, "ThrowingLoop")
        assertNoUse(fixture, steps, "throwingLoop")
        val causes = steps.flatMap { it.obligations }.map { it.cause }
        assertTrue("Throwing hasNext lost its explicit failure: $causes", causes.isNotEmpty())
        assertTrue(
            causes.all { cause ->
                cause == ValueFlowUnsupportedCause.ABRUPT_COMPLETION ||
                    cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION
            }
        )
    }

    fun testNextDoesNotMergeUnselectedSameTypedStoredField() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "selectedFieldLoop")
        assertRangeArrival(fixture, steps, "selectedFieldLoop")
        assertTrue(entries(fixture, steps, declaration("iterator", "SelectingLoop")).isNotEmpty())
        assertTrue(entries(fixture, steps, declaration("next", "SelectingLoop")).isNotEmpty())
        assertNoUse(fixture, steps, "selectedFieldLoop")
        assertComplete(steps)
    }

    fun testSelectedSameTypedStoredFieldReachesOnlyItsReorderedComponent() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "selectedFieldLoop", "otherLoopOrigin")
        assertRangeArrival(fixture, steps, "selectedFieldLoop")
        assertTrue(entries(fixture, steps, declaration("next", "SelectingLoop")).isNotEmpty())
        assertPayloadUse(fixture, steps, "selectedFieldLoop")
        assertComplete(steps)
    }

    fun testSharedImplicitCalleesKeepTwoLoopInvocationContextsSeparate() {
        val fixture = packetFixture(listOf(CONTROLS))
        val first = walk(fixture, "firstLoop")
        val second = walk(fixture, "secondLoop")
        assertRangeArrival(fixture, first, "firstLoop")
        assertRangeArrival(fixture, second, "secondLoop")
        assertPayloadUse(fixture, first, "firstLoop")
        assertPayloadUse(fixture, second, "secondLoop")
        assertNoUse(fixture, first, "secondLoop")
        assertNoUse(fixture, second, "firstLoop")
        listOf("iterator", "next").forEach { method ->
            val selected = declaration(method, "RepeatingLoop")
            val left = entries(fixture, first, selected).map { it.frame.invocation }
            val right = entries(fixture, second, selected).map { it.frame.invocation }
            assertTrue("Both exact loop invocations must be observed", left.isNotEmpty() && right.isNotEmpty())
            assertTrue(
                left.all { invocation ->
                    invocation.enclosing.compilerIdentity ==
                        fixture.callableDeclaration(declaration("firstLoop")).compilerIdentity
                }
            )
            assertTrue(
                right.all { invocation ->
                    invocation.enclosing.compilerIdentity ==
                        fixture.callableDeclaration(declaration("secondLoop")).compilerIdentity
                }
            )
            assertTrue(left.map { it.identity }.intersect(right.map { it.identity }.toSet()).isEmpty())
        }
        assertComplete(first)
        assertComplete(second)
    }

    private fun selectedLoop(file: KtFile, function: String, owner: String): KtForExpression {
        val declaration = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }
        val loop = checkNotNull(PsiTreeUtil.findChildOfType(declaration, KtForExpression::class.java))
        val selectedClass = file.declarations.filterIsInstance<KtClass>().single { it.name == owner }
        val methods = selectedClass.declarations.filterIsInstance<KtNamedFunction>().associateBy { it.name }
        IntellijReadObservation.None.observedAnalyze(loop) {
            val call: KaForLoopCall = checkNotNull(loop.resolveCall())
            val selections = listOf(call.iteratorCall, call.hasNextCall, call.nextCall)
            selections.zip(listOf("iterator", "hasNext", "next")).forEach { (selected, name) ->
                assertEquals(checkNotNull(methods[name]).symbol, selected.signature.symbol)
                assertEquals(KaSymbolOrigin.SOURCE, selected.signature.symbol.origin)
                assertNotNull(selected.dispatchReceiver)
                assertNull(selected.extensionReceiver)
            }
        }
        return loop
    }
}

/** Restored capabilities, bounded work and sibling completion cannot bypass the original-input proof. */
class NativeLoopInputBoundaryTest : NativeLoopInputFixtureTest() {
    override val fixtureSdkName = "loop-input-boundary-jdk"

    fun testRestoredElementMatchesTheForwardSelectedLoopProtocol() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "firstLoop")
        assertRangeArrival(fixture, steps, "firstLoop")
        val arrived = elementStep(steps).source
        val restored = restoreElement(fixture, "firstLoop", "RepeatingLoop")
        assertTrue("Normal-true element did not restore: $restored", restored is ValueModelSiteRead.Revalidated)
        val site = (restored as ValueModelSiteRead.Revalidated).position.site
        assertEquals(arrived.role, site.role)
        assertEquals(arrived.range, site.range)
        assertEquals(arrived.executionOwner, site.executionOwner)
        assertTrue(site.callStack.frames.isEmpty())
        val reread = readStep(fixture, site, fixture.budget)
        assertComplete(listOf(reread))
        assertEquals(elementStep(steps).transfers.map { it.target.role }, reread.transfers.map { it.target.role })
        assertPayloadUse(fixture, steps, "firstLoop")
    }

    fun testRestoredElementCannotBypassFalseGuard() {
        val fixture = packetFixture(listOf(CONTROLS))
        assertRangeArrival(fixture, walk(fixture, "emptyLoop"), "emptyLoop")
        assertEquals(
            ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            restoreElement(fixture, "emptyLoop", "EmptyLoop"),
        )
    }

    fun testRestoredElementCannotBypassThrowingGuard() {
        val fixture = packetFixture(listOf(CONTROLS))
        assertRangeArrival(fixture, walk(fixture, "throwingLoop"), "throwingLoop")
        assertEquals(
            ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            restoreElement(fixture, "throwingLoop", "ThrowingLoop"),
        )
    }

    fun testTinyWorkGrantCannotPublishPartialNextOrComponentTransfers() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "firstLoop")
        assertRangeArrival(fixture, steps, "firstLoop")
        assertComplete(steps)
        val resources = fixture.budget.resources as ResourceBudget.Bounded
        val budget =
            fixture.budget.copy(
                resources = resources.copy(workUnitLimit = WorkUnitLimit.parse(BOUNDARY_WORK).nativeRefined()),
                returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined(),
            )
        val phases = steps.map { it.source }.filter { it.role is ValueRole.LoopResult }
        assertEquals(2, phases.size)
        phases.forEach { source ->
            val limited = readStep(fixture, source, budget)
            assertTrue("Work-limited loop published a partial transfer", limited.transfers.isEmpty())
            assertTrue(limited.reviewedTransfers.isEmpty())
            assertEquals(
                listOf(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED),
                limited.obligations.map { it.cause }.distinct(),
            )
            assertTrue(limited.examinedWorkUnits.value <= BOUNDARY_WORK)
        }
    }

    fun testLaterThrowingLoopComponentBlocksEarlierPayloadUse() {
        assertComponentRejection("laterThrowingLoopComponent")
    }

    fun testUnusedNamedThrowingLoopComponentStillBlocksEarlierPayloadUse() {
        assertComponentRejection("unusedThrowingLoopComponent")
    }

    fun testUnderscoreDoesNotInvokeTheThrowingLoopComponent() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "underscoreThrowingLoopComponent")
        assertRangeArrival(fixture, steps, "underscoreThrowingLoopComponent")
        assertPayloadUse(fixture, steps, "underscoreThrowingLoopComponent")
        assertTrue(entries(fixture, steps, declaration("component2", "ThrowingLoopParts")).isEmpty())
        assertComplete(steps)
    }

    fun testIteratorDoesNotMergeTheUnselectedSameTypedStoredIterator() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "selectedIteratorLoop")
        assertRangeArrival(fixture, steps, "selectedIteratorLoop")
        assertTrue(entries(fixture, steps, declaration("iterator", "SelectingIterable")).isNotEmpty())
        assertNoItemTransfer(fixture, steps, "RepeatingLoop")
        assertNoUse(fixture, steps, "selectedIteratorLoop")
        assertComplete(steps)
    }

    fun testSelectedStoredIteratorPreservesItsNestedPayloadToExactUse() {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, "selectedIteratorLoop", "otherLoopOrigin")
        assertRangeArrival(fixture, steps, "selectedIteratorLoop")
        assertTrue(entries(fixture, steps, declaration("iterator", "SelectingIterable")).isNotEmpty())
        assertTrue(entries(fixture, steps, declaration("next", "RepeatingLoop")).isNotEmpty())
        assertPayloadUse(fixture, steps, "selectedIteratorLoop")
        assertComplete(steps)
    }

    fun testExplicitNextResultRemainsDistinctFromTheImplicitElement() {
        val fixture = packetFixture(listOf(CONTROLS))
        val function = "explicitAndImplicitNext"
        val steps = walk(fixture, function)
        assertRangeArrival(fixture, steps, function)
        val explicit = matching(steps, fixture.markedExpression(declaration(function), "explicit"))
        assertTrue(explicit.any { it.role == ValueRole.ExpressionResult })
        assertTrue(explicit.none { it.role is ValueRole.LoopResult })
        val element = elementStep(steps).source
        assertTrue(explicit.none { it.range == element.range })
        val calls = entries(fixture, steps, declaration("next", "RepeatingLoop"))
        assertEquals(2, calls.map { it.frame.invocation.identity }.distinct().size)
        assertTrue(matching(steps, fixture.markedExpression(declaration(function), "explicit-use")).isNotEmpty())
        assertPayloadUse(fixture, steps, function)
        assertComplete(steps)
    }

    private fun restoreElement(
        fixture: NativePacketValueFixture,
        function: String,
        owner: String,
    ): ValueModelSiteRead {
        val position = fixture.markedExpression(declaration(function), "loop")
        val claim =
            ValueSiteRoleClaim.LoopResult(
                invocationAnchor = position.anchor,
                expectedIterator = fixture.callableDeclaration(declaration("iterator", owner)),
                expectedHasNext = fixture.callableDeclaration(declaration("hasNext", owner)),
                expectedNext = fixture.callableDeclaration(declaration("next", owner)),
                phase = ValueLoopResultPhase.ELEMENT,
            )
        return runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(
                    project = project,
                    current = fixture.lease,
                    request =
                        ValueSiteRevalidationRequest.create(
                                enclosing = position.enclosing,
                                anchor = position.anchor,
                                role = claim,
                                budget = fixture.budget,
                            )
                            .nativeRefined(),
                    model = fixture.model,
                )
        }
    }

    private fun elementStep(steps: List<ValueFlowStep>): ValueFlowStep = steps.single { step ->
        (step.source.role as? ValueRole.LoopResult)?.result is ValueLoopResult.Element
    }

    private fun assertComponentRejection(function: String) {
        val fixture = packetFixture(listOf(CONTROLS))
        val steps = walk(fixture, function)
        assertRangeArrival(fixture, steps, function)
        val element = elementStep(steps)
        assertTrue("A failing sibling admitted a partial component transfer", element.transfers.isEmpty())
        assertEquals(
            listOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            element.obligations.map { it.cause }.distinct(),
        )
        assertNoUse(fixture, steps, function)
    }
}

/** Stateless adapters shared by the original-input and restoration cases; all traversal state is case-owned. */
abstract class NativeLoopInputFixtureTest : NativePacketValueFixtureTest() {
    protected fun walk(
        fixture: NativePacketValueFixture,
        function: String,
        producer: String = "loopOrigin",
    ): List<ValueFlowStep> {
        val adapter = IntellijValueFlowCompilerAdapter()
        val seed = runBlocking {
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(declaration(function), producer, declaration(producer)),
                model = fixture.model,
            )
        }
        assertTrue("Original producer was not admitted: $seed", seed is ValueProducerSeedRead.Seeded)
        val pending = ArrayDeque<ValueSite>()
        pending.addLast((seed as ValueProducerSeedRead.Seeded).seed.site)
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined())
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Loop input exceeded its finite site bound", visited.size <= MAX_SITES)
            val step = readStep(fixture, site, budget)
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        return steps
    }

    protected fun assertRangeArrival(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>, function: String) {
        val range = fixture.markedExpression(declaration(function), "range")
        val arrivals = matching(steps, range).filter { it.role == ValueRole.LocalRead }
        assertTrue("Original producer did not reach loop range: $function", arrivals.isNotEmpty())
        assertTrue("Loop range lost stored payload identity", arrivals.all { it.valuePath.segments.isNotEmpty() })
        assertTrue("Loop range retained an unmatched caller", arrivals.all { it.callStack.frames.isEmpty() })
    }

    protected fun assertPayloadUse(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>, function: String) {
        val expected = fixture.callableDeclaration(declaration("loopConsume"))
        val uses =
            matching(steps, fixture.markedExpression(declaration(function), "use")).filter { site ->
                (site.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == expected.compilerIdentity
            }
        assertTrue("Original payload did not reach exact consumer: $function", uses.isNotEmpty())
        assertTrue(uses.all { it.valuePath.segments.isEmpty() && it.callStack.frames.isEmpty() })
        assertTrue(matching(steps, fixture.markedExpression(declaration(function), "forbidden")).isEmpty())
    }

    protected fun assertNoUse(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>, function: String) {
        listOf("use", "forbidden").forEach { marker ->
            assertTrue(
                "Unproven loop consumer was reached: $function/$marker",
                matching(steps, fixture.markedExpression(declaration(function), marker)).isEmpty(),
            )
        }
    }

    protected fun assertNoItemTransfer(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>, owner: String) {
        assertTrue(
            "An unproven implicit element was published",
            steps.none { step ->
                (step.source.role as? ValueRole.LoopResult)?.result is ValueLoopResult.Element
            },
        )
        listOf(
                declaration("next", owner),
                declaration("component1", "LoopParts"),
                declaration("component2", "LoopParts"),
            )
            .forEach { selected ->
                val expected = fixture.callableDeclaration(selected).compilerIdentity
                assertTrue("Forbidden source entry: $selected", entries(fixture, steps, selected).isEmpty())
                val receivers =
                    steps
                        .flatMap { it.transfers }
                        .flatMap { listOf(it.source, it.target) }
                        .mapNotNull { it.role as? ValueRole.Receiver }
                assertTrue(
                    "Forbidden receiver transfer: $selected",
                    receivers.none { it.call.callable.compilerIdentity == expected },
                )
            }
    }

    protected fun entries(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        selected: PacketDeclaration,
    ): List<ValueSourceOperation.Enter> {
        val expected = fixture.callableDeclaration(selected).compilerIdentity
        return steps
            .flatMap { it.transfers }
            .mapNotNull { transfer ->
                (transfer.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.Enter
            }
            .filter { it.frame.invocation.callable.compilerIdentity == expected }
    }

    protected fun matching(steps: List<ValueFlowStep>, expression: PacketExpression): List<ValueSite> =
        steps
            .map { it.source }
            .filter { site ->
                site.enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
                    site.range == expression.anchor
            }

    protected fun assertComplete(steps: List<ValueFlowStep>) {
        val obligations = steps.flatMap { it.obligations }
        assertTrue("Loop input retained unsupported arrivals: $obligations", obligations.isEmpty())
        assertTrue(steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted })
    }

    protected fun readStep(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        budget: RelationBudget,
    ): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native read was not observed at ${source.range}: $read", read is ValueFlowRead.Observed)
        val step = (read as ValueFlowRead.Observed).step
        assertEquals(source, step.source)
        assertTrue("Loop input unexpectedly required a reviewed transfer", step.reviewedTransfers.isEmpty())
        return step
    }

    protected fun declaration(name: String, owner: String = "") = PacketDeclaration(CONTROLS, owner, name)

    protected companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/LoopInputControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/LoopInputControls.kt"
        const val MAX_READ_BYTES = 16_000_000L
        const val MAX_SITES = 256
        const val BOUNDARY_WORK = 3L
    }
}
