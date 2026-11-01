package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import kotlinx.coroutines.runBlocking

/** Repeated executions share source text, not actual arguments, execution identities, or return destinations. */
class NativeRepeatedCallbackInputTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "repeated-callback-input-jdk"

    fun testDifferentActualsReturnOnlyTheSelectedFirstOccurrence() {
        val fixture = fixture()
        val steps = walk(fixture, "repeatDifferentCase")
        assertSelectedRoutes(fixture = fixture, steps = steps, wrapper = "repeatDifferent", markers = listOf("first"))
        assertConsumers(fixture, steps, listOf(fixture.markedExpression(declaration("repeatDifferentCase"), "use")))
    }

    fun testDiscardedFirstInvocationCannotBecomeTheSecondInvocationResult() {
        val fixture = fixture()
        val steps = walk(fixture, "repeatDiscardedCase")
        assertSelectedRoutes(fixture = fixture, steps = steps, wrapper = "repeatDiscarded", markers = listOf("first"))
        assertConsumers(fixture, steps, emptyList())
    }

    fun testBothActualsRetainSeparateSelectedOwnersAndExactReturns() {
        val fixture = fixture()
        val steps = walk(fixture, "repeatSameCase")
        assertSelectedRoutes(
            fixture = fixture,
            steps = steps,
            wrapper = "repeatSame",
            markers = listOf("first", "second"),
        )
        val entries = callbackEntries(steps)
        assertEquals(1, entries.map { it.target.range }.distinct().size)
        assertEquals(2, entries.map { it.target.executionOwner.identity }.distinct().size)
        assertEquals(1, entries.map { it.target.callStack }.distinct().size)
        assertConsumers(
            fixture,
            steps,
            listOf(
                fixture.markedExpression(declaration("repeatSame"), "first-use"),
                fixture.markedExpression(declaration("repeatSameCase"), "use"),
            ),
        )
    }

    fun testExplicitInvokeSelectsTheWholeQualifiedOccurrence() {
        val fixture = fixture()
        val steps = walk(fixture, "repeatExplicitCase")
        assertSelectedRoutes(fixture = fixture, steps = steps, wrapper = "repeatExplicit", markers = listOf("first"))
        assertConsumers(fixture, steps, listOf(fixture.markedExpression(declaration("repeatExplicitCase"), "use")))
    }

    private fun assertSelectedRoutes(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        wrapper: String,
        markers: List<String>,
    ) {
        val expected = markers.map { fixture.markedExpression(declaration(wrapper), it) }
        val entries = callbackEntries(steps)
        assertEquals(callbackRouteSummary(steps), expected.size, entries.size)
        assertEquals(
            expected.map { it.anchor }.toSet(),
            entries.map { selected(it.target).invocation.occurrence.range }.toSet(),
        )
        val returned =
            steps
                .flatMap { it.transfers }
                .filter { transfer ->
                    (transfer.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackReturn
                }
        assertEquals(expected.size, returned.size)
        for (entry in entries) {
            val operation =
                (entry.evidence as ValueSourceTransfer).operation as ValueSourceOperation.CallbackArgumentEntry
            val context = entry.target.executionOwner.context as ValueExecutionContext.Callback
            assertEquals(operation.invocation, context.selectedInvocation)
            assertEquals(2, context.invocations.count { it.body == context.body })
            assertEquals(entry.source.callStack, entry.target.callStack)
            assertEquals(entry.source.valuePath, entry.target.valuePath)
            val exact = returned.single { selected(it.source) == context.selectedInvocation }
            val exit = (exact.evidence as ValueSourceTransfer).operation as ValueSourceOperation.CallbackReturn
            assertEquals(operation.invocation, exit.invocation)
            assertEquals(operation.supplierFrame, exit.frame)
            assertEquals(operation.invocation.invocation.occurrence.range, exact.target.range)
            assertEquals(entry.source.enclosing, exact.target.enclosing)
            assertEquals(entry.source.executionOwner, exact.target.executionOwner)
            assertEquals(entry.source.callStack, exact.target.callStack)
            assertEquals(entry.target.executionOwner, exact.source.executionOwner)
        }
    }

    private fun selected(site: ValueSite) =
        (site.executionOwner.context as ValueExecutionContext.Callback).selectedInvocation

    private fun callbackEntries(steps: List<ValueFlowStep>): List<ValueTransfer> =
        steps
            .flatMap { it.transfers }
            .filter { transfer ->
                (transfer.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackArgumentEntry
            }

    private fun assertConsumers(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        expected: List<PacketExpression>,
    ) {
        val consumers =
            listOf("consume", "forbidden").map { fixture.callableDeclaration(declaration(it)).compilerIdentity }
        val actual =
            steps
                .flatMap { it.transfers }
                .map { it.target }
                .filter { site ->
                    (site.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity in consumers
                }
        assertEquals(callbackRouteSummary(steps), expected.size, actual.size)
        assertEquals(
            expected.map { it.enclosing.compilerIdentity to it.anchor }.toSet(),
            actual.map { it.enclosing.compilerIdentity to it.range }.toSet(),
        )
        assertTrue(actual.all { it.valuePath.segments.isEmpty() })
    }

    private fun walk(fixture: NativePacketValueFixture, function: String): List<ValueFlowStep> = runBlocking {
        val adapter = IntellijValueFlowCompilerAdapter()
        val seeded =
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(declaration(function), "origin", declaration("origin")),
                model = fixture.model,
            )
        assertTrue("Original producer must be admitted: $seeded", seeded is ValueProducerSeedRead.Seeded)
        val pending = ArrayDeque<ValueSite>().apply { addLast((seeded as ValueProducerSeedRead.Seeded).seed.site) }
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(RETURNED_BYTE_LIMIT).nativeRefined())
        while (pending.isNotEmpty()) {
            val source = pending.removeFirst()
            if (!visited.add(source.identity)) continue
            assertTrue("Repeated callback exceeded its site bound", visited.size <= MAXIMUM_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Expected observation at ${source.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertEquals(source, step.source)
            assertTrue(callbackRouteSummary(steps + step), step.obligations.isEmpty())
            assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
            assertTrue(step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        steps
    }

    private fun declaration(name: String) = PacketDeclaration(COVERAGE, "", name)

    private fun fixture(): NativePacketValueFixture {
        val fixture = packetFixture(listOf(COVERAGE))
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                file.setBinaryContent(file.contentsToByteArray() + CONTROLS.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        return fixture
    }

    private companion object {
        const val COVERAGE = "src/main/kotlin/fixture/variants/coverage/COVERAGE.kt"
        const val NATIVE_PATH = "src/fixture/variants/coverage/COVERAGE.kt"
        const val MAXIMUM_SITES = 512
        const val RETURNED_BYTE_LIMIT = 32_000_000L
        val CONTROLS =
            """

            fun repeatDifferent(value: SwappedParts, other: SwappedParts, action: (SwappedParts) -> Payload): Payload {
                val result = /*<first>*/action(value)/*</first>*/
                forbidden(/*<second>*/action(other)/*</second>*/)
                return result
            }
            fun repeatDifferentCase() {
                val result = repeatDifferent(SwappedParts(Payload(), origin()), SwappedParts(Payload(), Payload())) { (value, _) -> value }
                consume(/*<use>*/result/*</use>*/)
            }
            fun repeatDiscarded(value: SwappedParts, other: SwappedParts, action: (SwappedParts) -> Payload): Payload {
                /*<first>*/action(value)/*</first>*/
                return /*<second>*/action(other)/*</second>*/
            }
            fun repeatDiscardedCase() {
                val result = repeatDiscarded(SwappedParts(Payload(), origin()), SwappedParts(Payload(), Payload())) { (value, _) -> value }
                forbidden(result)
            }
            fun repeatSame(value: SwappedParts, action: (SwappedParts) -> Payload): Payload {
                val first = /*<first>*/action(value)/*</first>*/
                consume(/*<first-use>*/first/*</first-use>*/)
                return /*<second>*/action(value)/*</second>*/
            }
            fun repeatSameCase() {
                val result = repeatSame(SwappedParts(Payload(), origin())) { (value, _) -> value }
                consume(/*<use>*/result/*</use>*/)
            }
            fun repeatExplicit(value: SwappedParts, other: SwappedParts, action: (SwappedParts) -> Payload): Payload {
                val result = /*<first>*/action.invoke(value)/*</first>*/
                forbidden(/*<second>*/action.invoke(other)/*</second>*/)
                return result
            }
            fun repeatExplicitCase() {
                val result = repeatExplicit(SwappedParts(Payload(), origin()), SwappedParts(Payload(), Payload())) { (value, _) -> value }
                consume(/*<use>*/result/*</use>*/)
            }
            """
                .trimIndent()
    }
}
