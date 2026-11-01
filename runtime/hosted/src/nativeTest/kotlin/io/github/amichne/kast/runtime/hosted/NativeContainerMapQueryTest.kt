package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Complete-only production queries must retain one contiguous producer/callback/observer route. */
class NativeContainerMapQueryTest : NativeContainerQueryFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testDirectControlCompletesThroughTheProductionQuery() =
        runBlocking<Unit> { positive("direct", callback = false) }

    fun testConcreteMapCompletesThroughRetainedWireEvidence() = runBlocking<Unit> { positive("concrete") }

    fun testAnonymousCallerCompletesThroughRetainedWireEvidence() = runBlocking<Unit> { positive("anonymous") }

    fun testImplicitParameterCompletesThroughRetainedWireEvidence() = runBlocking<Unit> { positive("implicit") }

    fun testInterfaceMapCompletesWithoutErasingItsImplementationFrame() = runBlocking {
        assertImplementationWitness(positive("interface"), forwarded = false)
    }

    fun testForwardedInterfaceMapCompletesWithTheWholeCallbackRoute() = runBlocking {
        assertImplementationWitness(positive("forwarded"), forwarded = true)
    }

    fun testErrorBranchCompletesWithoutExecutingItsCallback() = runBlocking {
        val fixture = containerFixture()
        val producer = fixture.source.producer("", "badCase", "origin")
        val captured = NativeContainerQueryCapture(fixture.services).capture(producer)
        val forbidden = fixture.source.reference(fixture.source.declaration("", "forbidden"))
        val consumer = fixture.source.reference(fixture.source.declaration("", "consume"))
        val badMap = fixture.source.reference(fixture.source.declaration("Bad", "map"))
        val expectedProducer = producerSite(fixture.source, "badCase", producer.anchor)
        val transfers = routes(captured.paths, expectedProducer).flatten()
        assertTrue(
            "Error branch never entered its implementation",
            transfers.any {
                (it.operation() as? ImpactSourceOperationDocument.Enter)?.frame?.callee?.enclosing == badMap
            },
        )
        assertTrue(transfers.none { it.operation() is ImpactSourceOperationDocument.CallbackArgumentEntry })
        assertTrue(
            transfers.none { transfer ->
                (transfer.target.role as? ImpactValueRoleDocument.Argument)?.invocation?.callable in
                    setOf(forbidden, consumer)
            }
        )
        assertWireEvidence(transfers)
    }

    private suspend fun positive(scenario: String, callback: Boolean = true): ContainerQueryWitness {
        val fixture = containerFixture()
        val source = fixture.source
        val producer = source.producer("", "${scenario}Case", "origin")
        val captured = NativeContainerQueryCapture(fixture.services).capture(producer)
        val lexical = producerSite(source, "${scenario}Case", producer.anchor)
        val expectedProducer =
            if (scenario == "anonymous") anonymousProducer(source, lexical, captured.paths) else lexical
        val observer = observer(source, scenario)
        val argument = observer.role as ImpactValueRoleDocument.Argument
        val expected =
            observer.copy(
                executionOwner = expectedProducer.executionOwner,
                role =
                    argument.copy(
                        invocation = argument.invocation.copy(executionOwner = expectedProducer.executionOwner)
                    ),
            )
        val routes = routes(captured.paths, expectedProducer)
        val prefixes = routes.mapNotNull { it.prefixToObserver(expectedProducer, expected) }
        val route = prefixes.firstOrNull { !callback || it.crossesCallback() }
        assertNotNull("No exact contiguous callback-to-observer witness for $scenario", route)
        val selected = checkNotNull(route)
        if (callback) callbackWitness(selected)
        assertWireEvidence(routes.flatten())
        return ContainerQueryWitness(source, selected)
    }

    private suspend fun anonymousProducer(
        source: NativePacketQuerySource,
        expected: ImpactValueSiteReferenceDocument,
        paths: List<ImpactPathDocument>,
    ): ImpactValueSiteReferenceDocument {
        val actual = paths.first().producer
        val owner = actual.executionOwner as ImpactExecutionOwnerDocument.Callback
        assertEquals(source.markedRange("anonymous-body"), owner.body.range)
        val invocation = owner.invocations.values.single { it.body == owner.body }
        assertEquals(source.reference(source.declaration("", "invokeNow")), invocation.supply.callable)
        assertEquals(expected.enclosing, invocation.enclosing)
        assertEquals(expected.copy(executionOwner = owner), actual)
        return actual
    }

    private suspend fun producerSite(
        source: NativePacketQuerySource,
        method: String,
        anchor: ImpactSourceRangeDocument,
    ) =
        ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration("", method)),
            range = anchor,
            role = ImpactValueRoleDocument.ExpressionResult,
        )

    private suspend fun observer(source: NativePacketQuerySource, scenario: String): ImpactValueSiteReferenceDocument {
        val method = "${scenario}Case"
        return ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration("", method)),
            range = source.markedRange("$scenario-use"),
            role =
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        source.invocation("", method, "consume"),
                        source.reference(source.declaration("", "consume")),
                    ),
                    packetQueryOffset(0),
                ),
        )
    }

    private fun routes(
        paths: List<ImpactPathDocument>,
        producer: ImpactValueSiteReferenceDocument,
    ): List<List<ImpactCompilerTransferDocument>> = paths.map { path ->
        assertEquals("Retained path changed its original producer", producer, path.producer)
        assertTrue(
            "Complete container query retained an unresolved terminal: ${path.terminal}",
            path.terminal is ImpactPathTerminalDocument.SupportedDomainEnd,
        )
        assertTrue(
            "Unexpected modeled or reviewed step",
            path.steps.values.all { it is ImpactPathStepDocument.Compiler },
        )
        val transfers = path.steps.values.map { (it as ImpactPathStepDocument.Compiler).transfer }
        val first = transfers.firstOrNull()
        if (first != null) assertEquals("Retained route changed its initial value site", producer, first.source)
        transfers
    }

    private fun List<ImpactCompilerTransferDocument>.prefixToObserver(
        producer: ImpactValueSiteReferenceDocument,
        expected: ImpactValueSiteReferenceDocument,
    ): List<ImpactCompilerTransferDocument>? {
        if (firstOrNull()?.source != producer) return null
        if (zipWithNext().any { (left, right) -> left.target != right.source }) return null
        val observer = indexOfFirst { it.target == expected }
        return if (observer < 0) null else take(observer + 1)
    }

    private fun List<ImpactCompilerTransferDocument>.crossesCallback(): Boolean =
        any { it.operation() is ImpactSourceOperationDocument.CallbackArgumentEntry } &&
            any { it.operation() is ImpactSourceOperationDocument.CallbackReturn }

    private fun callbackWitness(route: List<ImpactCompilerTransferDocument>): ContainerCallbackWitness {
        val inputIndex = route.indexOfFirst { it.operation() is ImpactSourceOperationDocument.CallbackArgumentEntry }
        assertTrue("Observer route never entered its callback", inputIndex >= 0)
        val input = route[inputIndex]
        val entry = input.operation() as ImpactSourceOperationDocument.CallbackArgumentEntry
        val returned =
            checkNotNull(
                route.drop(inputIndex + 1).firstOrNull {
                    (it.operation() as? ImpactSourceOperationDocument.CallbackReturn)?.invocation == entry.invocation
                }
            ) {
                "Observer route never returned from the same supplied callback"
            }
        val exit = returned.operation() as ImpactSourceOperationDocument.CallbackReturn
        val terminal = input.source.callStack.values.last()
        assertEquals(input.source.callStack, input.target.callStack)
        assertEquals(input.target.callStack, returned.source.callStack)
        assertEquals(returned.source.callStack, returned.target.callStack)
        assertEquals(terminal, exit.frame)
        assertEquals(terminal.callee.enclosing, input.source.enclosing)
        assertEquals(terminal.callee.enclosing, returned.target.enclosing)
        assertEquals(entry.invocation, exit.invocation)
        assertEquals(entry.parameter.executionOwner, input.target.executionOwner)
        return ContainerCallbackWitness(input, entry, returned, exit)
    }

    private suspend fun assertImplementationWitness(witness: ContainerQueryWitness, forwarded: Boolean) {
        val callback = callbackWitness(witness.route)
        val source = witness.source
        val terminal = callback.exit.frame
        val implementation = terminal.target as? ImpactCallTargetDocument.Implementation
        assertNotNull("Callback return erased implementation dispatch", implementation)
        implementation as ImpactCallTargetDocument.Implementation
        val concrete = source.reference(source.declaration("Good", "map"))
        val declared = source.reference(source.declaration("Packet", "map"))
        val invocation = (terminal.invocation as ImpactCallOccurrenceDocument.Declared).invocation
        assertEquals(declared, invocation.callable)
        assertEquals(concrete, terminal.callee.enclosing)
        assertEquals(ImpactExecutionOwnerDocument.Declaration, terminal.callee.executionOwner)
        assertEquals(concrete, implementation.relation.source.declaration)
        assertEquals(declared, implementation.relation.target.declaration)
        assertTrue(implementation.relation in implementation.dispatch.facts.values)
        assertEquals(source.ownership(concrete), implementation.ownership)
        assertEquals(terminal, callback.input.source.callStack.values.last())
        if (forwarded) {
            assertForwardingWitness(source, callback)
        } else {
            assertEquals(source.invocation("", "interfaceCase", "map"), invocation.range)
            assertEquals(terminal, callback.entry.supplierFrame)
            assertEquals(implementation, callback.entry.invocation.supplyTarget)
            assertTrue(callback.entry.invocation.forwardings.values.isEmpty())
            assertEquals(packetQueryOffset(0), callback.entry.invocation.position)
        }
    }

    private suspend fun assertForwardingWitness(source: NativePacketQuerySource, callback: ContainerCallbackWitness) {
        val root = callback.entry.supplierFrame
        val terminal = callback.exit.frame
        val forwardings = callback.entry.invocation.forwardings.values
        val stack = callback.input.source.callStack.values
        val rootCallable = source.reference(source.declaration("", "forwardAgain"))
        val intermediate = source.reference(source.declaration("", "forward"))
        assertEquals(ImpactCallTargetDocument.Direct, root.target)
        assertEquals(rootCallable, root.callee.enclosing)
        assertEquals(source.invocation("", "forwardedCase", "forwardAgain"), root.invocation.range)
        assertEquals(source.invocation("", "forward", "map"), terminal.invocation.range)
        assertEquals(2, forwardings.size)
        assertEquals(
            listOf(intermediate, terminal.callee.enclosing),
            forwardings.map { it.targetFrame.callee.enclosing },
        )
        assertEquals(listOf(root) + forwardings.map { it.targetFrame }, stack)
        assertEquals(terminal, forwardings.last().targetFrame)
        assertFalse("Root supplier was replaced by the terminal implementation frame", root == terminal)
        assertEquals(ImpactCallTargetDocument.Direct, callback.entry.invocation.supplyTarget)
        assertEquals(
            (root.invocation as ImpactCallOccurrenceDocument.Declared).invocation,
            callback.entry.invocation.supply,
        )
        assertEquals(packetQueryOffset(1), callback.entry.invocation.position)
        assertEquals(listOf(packetQueryOffset(1), packetQueryOffset(1)), forwardings.map { it.sourcePosition })
        assertEquals(listOf(packetQueryOffset(1), packetQueryOffset(0)), forwardings.map { it.position })
        assertEquals(stack, callback.returned.target.callStack.values)
    }

    private fun assertWireEvidence(transfers: List<ImpactCompilerTransferDocument>) {
        transfers.forEach { transfer ->
            assertTrue("Invalid retained transfer", transfer.admitsEvidence())
            val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(transfer))
            assertEquals(transfer, decoded)
            assertTrue("Decoded transfer lost admission", decoded.admitsEvidence())
        }
    }

    private fun ImpactCompilerTransferDocument.operation() =
        (evidence as? ImpactTransferEvidenceDocument.Source)?.operation
}

private data class ContainerQueryWitness(
    val source: NativePacketQuerySource,
    val route: List<ImpactCompilerTransferDocument>,
)

private data class ContainerCallbackWitness(
    val input: ImpactCompilerTransferDocument,
    val entry: ImpactSourceOperationDocument.CallbackArgumentEntry,
    val returned: ImpactCompilerTransferDocument,
    val exit: ImpactSourceOperationDocument.CallbackReturn,
)
