package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import kotlinx.coroutines.runBlocking

/** Original payloads cross stored properties, member callbacks, and newly constructed result containers. */
class NativeContainerMapFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "container-map-flow-jdk"

    fun testDirectProducerReachesExactObserverArgument() = positive("directCase", callback = false)

    fun testPlainStoredValueCrossesExplicitLambdaAndResultContainer() = positive("plainExplicitCase")

    fun testPlainStoredValueCrossesImplicitLambdaAndResultContainer() = positive("plainImplicitCase")

    fun testFinalOverrideValueCrossesConcreteMemberMap() = positive("finalOverrideCase")

    fun testOverrideValueInFinalClassCrossesConcreteMemberMap() = positive("overrideCase")

    fun testInterfaceTypedReceiverPreservesPayloadAcrossMemberMap() = positive("interfaceCase", implementation = true)

    fun testGenericForwarderPreservesPayloadAndCallbackSupplier() = positive("forwardingCase", implementation = true)

    fun testImplicitParameterRetainsInterfaceImplementation() = positive("interfaceImplicitCase", implementation = true)

    fun testImplicitParameterRetainsForwardedInterfaceImplementation() =
        positive("forwardingImplicitCase", implementation = true)

    fun testConcreteGenericForwarderPreservesTheSuppliedCallback() = positive("plainForwardingCase")

    fun testTwoForwardingCallsPreserveTheRootCallbackSupplier() = positive("twoHopForwardingCase")

    fun testCovariantOutcomeFactoryPreservesPayloadTypeTransition() =
        positive("covariantOutcomeCase", observer = "observeWidened")

    fun testBadBranchDoesNotInvokeTheSuppliedCallback() {
        val fixture = fixture()
        val steps = walk(fixture, "badCase")
        val expected = fixture.callableDeclaration(declaration("map", "Bad"))
        assertTrue(
            "Bad branch never entered its map implementation: ${callbackRouteSummary(steps)}",
            steps
                .flatMap { it.transfers }
                .any { transfer ->
                    val operation =
                        (transfer.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.Enter
                    operation != null &&
                        operation.frame.callee.enclosing.compilerIdentity == expected.compilerIdentity &&
                        operation.position == ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)
                },
        )
        assertTrue("Dormant callback received the payload", callbackEntries(steps).isEmpty())
        assertNoObserver(fixture, steps, "forbidden")
        assertNoObserver(fixture, steps, "observe")
        assertComplete(steps)
    }

    fun testConstantCallbackResultDoesNotReturnItsInputPayload() = assertConstantResult("constantCase")

    fun testForwardedConstantCallbackResultDoesNotReturnItsInputPayload() =
        assertConstantResult("forwardedConstantCase")

    private fun assertConstantResult(function: String) {
        val fixture = fixture()
        val steps = walk(fixture, function)
        assertObserver(
            fixture = fixture,
            steps = steps,
            expression = fixture.markedExpression(declaration(function), "input"),
            observer = "inputObserver",
            returnedToCaller = false,
        )
        assertTrue("Constant callback was not entered", callbackEntries(steps).isNotEmpty())
        assertNoObserver(fixture, steps, "observe")
        assertComplete(steps)
    }

    private fun positive(
        function: String,
        callback: Boolean = true,
        implementation: Boolean = false,
        observer: String = "observe",
    ) {
        val fixture = fixture()
        val steps = walk(fixture, function)
        assertObserver(
            fixture = fixture,
            steps = steps,
            expression = fixture.markedExpression(declaration(function), "observer"),
            observer = observer,
        )
        if (callback) {
            assertTrue(
                "No callback-to-observer route: ${callbackRouteSummary(steps)}",
                callbackReachesObserver(fixture, steps, observer),
            )
        }
        if (implementation) assertImplementationCallback(fixture, steps)
        assertComplete(steps)
    }

    private fun assertImplementationCallback(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>) {
        val declared = fixture.callableDeclaration(declaration("map", "PacketResult"))
        val selected = fixture.callableDeclaration(declaration("map", "Good"))
        val transfers = steps.flatMap { it.transfers }
        val entries = transfers.filter {
            (it.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackArgumentEntry
        }
        assertTrue(entries.isNotEmpty())
        entries.forEach { transfer ->
            val entered =
                (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.CallbackArgumentEntry
            val frame = transfer.source.callStack.frames.last()
            assertTrue(frame.target is ValueCallTarget.Implementation)
            assertEquals(declared.compilerIdentity, frame.invocation.callable.compilerIdentity)
            assertEquals(selected.compilerIdentity, frame.callee.enclosing.compilerIdentity)
            assertEquals(entered.invocation.supply.frame, entered.supplierFrame)
            assertTrue(
                "Callback did not return to its exact implementation frame",
                transfers.any { returned ->
                    val operation =
                        (returned.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.CallbackReturn
                    operation?.invocation == entered.invocation && operation.frame == frame
                },
            )
        }
    }

    private fun callbackReachesObserver(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        observer: String,
    ): Boolean {
        val observers = observerArguments(fixture, steps, observer).map { it.identity }.toSet()
        val successors = steps.associate {
            it.source.identity to it.transfers.map { transfer -> transfer.target.identity }
        }
        val pending = ArrayDeque<ValueSiteIdentity>()
        steps
            .flatMap { it.transfers }
            .forEach { transfer ->
                if (
                    (transfer.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackArgumentEntry
                ) {
                    pending.addLast(transfer.target.identity)
                }
            }
        val visited = mutableSetOf<ValueSiteIdentity>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (current in observers) return true
            successors[current].orEmpty().forEach(pending::addLast)
        }
        return false
    }

    private fun assertObserver(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        expression: PacketExpression,
        observer: String,
        returnedToCaller: Boolean = true,
    ) {
        val expected = fixture.callableDeclaration(declaration(observer))
        val arguments = observerArguments(fixture, steps, observer)
        assertTrue("Exact $observer argument was not reached: ${callbackRouteSummary(steps)}", arguments.isNotEmpty())
        arguments.forEach { site ->
            val argument = site.role as ValueRole.Argument
            assertEquals(expected.compilerIdentity, argument.call.callable.compilerIdentity)
            assertEquals(expected.file, argument.call.callable.file)
            assertEquals(expected.range, argument.call.callable.range)
            assertEquals(0, argument.position.value)
            assertEquals(expression.enclosing.compilerIdentity, site.enclosing.compilerIdentity)
            assertEquals(expression.anchor, site.range)
            assertTrue("Observer received a container instead of its payload", site.valuePath.segments.isEmpty())
            if (returnedToCaller) {
                assertTrue("Observer retained an unmatched source call", site.callStack.frames.isEmpty())
            }
        }
    }

    private fun assertNoObserver(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        observer: String,
    ) {
        assertTrue(
            "Unrelated $observer argument received the original payload: ${callbackRouteSummary(steps)}",
            observerArguments(fixture, steps, observer).isEmpty(),
        )
    }

    private fun observerArguments(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        observer: String,
    ): List<ValueSite> {
        val expected = fixture.callableDeclaration(declaration(observer))
        return steps
            .map { it.source }
            .filter { site ->
                (site.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == expected.compilerIdentity
            }
    }

    private fun callbackEntries(steps: List<ValueFlowStep>) =
        steps
            .flatMap { it.transfers }
            .mapNotNull { transfer ->
                (transfer.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.CallbackArgumentEntry
            }

    private fun assertComplete(steps: List<ValueFlowStep>) {
        assertTrue("Container fixture did not execute", steps.isNotEmpty())
        assertTrue(
            "Container flow retained unresolved obligations: ${callbackRouteSummary(steps)}",
            steps.all { it.obligations.isEmpty() },
        )
        assertTrue(
            "Container flow did not exhaust every native read: ${callbackRouteSummary(steps)}",
            steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted },
        )
    }

    private fun walk(fixture: NativePacketValueFixture, function: String): List<ValueFlowStep> = runBlocking {
        val adapter = IntellijValueFlowCompilerAdapter()
        val seed =
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(declaration(function), "produce", declaration("produce")),
                model = fixture.model,
            )
        assertTrue("Original producer was not admitted: $seed", seed is ValueProducerSeedRead.Seeded)
        val pending = ArrayDeque<ValueSite>().apply { addLast((seed as ValueProducerSeedRead.Seeded).seed.site) }
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined())
        while (pending.isNotEmpty()) {
            val source = pending.removeFirst()
            if (!visited.add(source.identity)) continue
            assertTrue("Container flow exceeded its finite site bound", visited.size <= MAX_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Expected native observation at ${source.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertEquals(source, step.source)
            assertTrue("Container flow required a reviewed transfer", step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        steps
    }

    private fun fixture(): NativePacketValueFixture {
        val fixture = packetFixture(listOf(CONTROLS))
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) { file.setBinaryContent(SOURCE.toByteArray()) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        return fixture
    }

    private fun declaration(name: String, owner: String = "") = PacketDeclaration(CONTROLS, owner, name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
        const val MAX_READ_BYTES = 16_000_000L
        const val MAX_SITES = 256
        val SOURCE =
            """
            package fixture.containermap

            class Payload
            fun produce(): Payload = Payload()
            fun observe(value: Payload?) {}
            fun observeWidened(value: Any?) {}
            fun inputObserver(value: Payload) {}
            fun forbidden(value: Payload) {}

            interface Slot<out S> { val value: S }

            sealed interface PacketResult<out S> {
                fun <T> map(transform: (S) -> T): PacketResult<T>
                fun payload(): S?
            }

            data class PlainGood<S>(val value: S) {
                fun <T> map(transform: (S) -> T): PlainGood<T> = transform(value).asPlainPacket()
            }

            data class FinalGood<S>(final override val value: S) : Slot<S>, PacketResult<S> {
                override fun <T> map(transform: (S) -> T): FinalGood<T> = transform(value).asFinalPacket()
                override fun payload(): S = value
            }

            data class Good<S>(override val value: S) : Slot<S>, PacketResult<S> {
                override fun <T> map(transform: (S) -> T): Good<T> = transform(value).asPacket()
                override fun payload(): S = value
            }

            data class Bad(val reason: Payload) : PacketResult<Nothing> {
                override fun <T> map(transform: (Nothing) -> T): PacketResult<T> = this
                override fun payload(): Nothing? = null
            }

            fun <S> S.asPlainPacket(): PlainGood<S> = PlainGood(this)
            fun <S> S.asFinalPacket(): FinalGood<S> = FinalGood(this)
            fun <S> S.asPacket(): Good<S> = Good(this)

            fun <S, T> forward(value: PacketResult<S>, transform: (S) -> T): PacketResult<T> = value.map(transform)

            fun <S, T> forwardAgain(value: PacketResult<S>, transform: (S) -> T): PacketResult<T> = forward(value, transform)

            fun <S, T> forwardPlain(value: PlainGood<S>, transform: (S) -> T): PlainGood<T> = value.map(transform)

            fun <S, T> forwardPlainAgain(value: PlainGood<S>, transform: (S) -> T): PlainGood<T> = forwardPlain(value, transform)

            sealed interface Outcome<out E, out S> {
                val value: Any?
                fun <T> map(transform: (S) -> T): Outcome<E, T>
                fun getOrNull(): S?
            }

            @ConsistentCopyVisibility
            data class Pass<E, S> internal constructor(override val value: S) : Outcome<E, S> {
                override fun <T> map(transform: (S) -> T): Outcome<E, T> = transform(value).asPass()
                override fun getOrNull(): S = value
            }

            @ConsistentCopyVisibility
            data class Fail<E, S> internal constructor(override val value: E) : Outcome<E, S> {
                override fun <T> map(transform: (S) -> T): Outcome<E, T> = Fail(value)
                override fun getOrNull(): S? = null
            }

            @JvmName("asPassValue")
            fun <S> asPass(value: S): Outcome<Nothing, S> = Pass(value)
            fun <S> S.asPass(): Outcome<Nothing, S> = asPass(this)

            fun <E, S, T> forwardOutcome(value: Outcome<E, S>, transform: (S) -> T): Outcome<E, T> = value.map(transform)

            fun directCase() {
                val produced = produce()
                observe(/*<observer>*/produced/*</observer>*/)
            }

            fun plainExplicitCase() {
                val initial = PlainGood(produce())
                val mapped = initial.map { item -> item }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun plainImplicitCase() {
                val initial = PlainGood(produce())
                val mapped = initial.map { it }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun finalOverrideCase() {
                val initial = FinalGood(produce())
                val mapped = initial.map { item -> item }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun overrideCase() {
                val initial = Good(produce())
                val mapped = initial.map { item -> item }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun interfaceCase() {
                val initial: PacketResult<Payload> = Good(produce())
                val mapped = initial.map { item -> item }
                observe(/*<observer>*/mapped.payload()/*</observer>*/)
            }

            fun forwardingCase() {
                val initial: PacketResult<Payload> = Good(produce())
                val mapped = forward(initial) { item -> item }
                observe(/*<observer>*/mapped.payload()/*</observer>*/)
            }

            fun interfaceImplicitCase() {
                val initial: PacketResult<Payload> = Good(produce())
                val mapped = initial.map { it }
                observe(/*<observer>*/mapped.payload()/*</observer>*/)
            }

            fun forwardingImplicitCase() {
                val initial: PacketResult<Payload> = Good(produce())
                val mapped = forwardAgain(initial) { it }
                observe(/*<observer>*/mapped.payload()/*</observer>*/)
            }

            fun plainForwardingCase() {
                val initial = PlainGood(produce())
                val mapped = forwardPlain(initial) { item -> item }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun twoHopForwardingCase() {
                val initial = PlainGood(produce())
                val mapped = forwardPlainAgain(initial) { item -> item }
                observe(/*<observer>*/mapped.value/*</observer>*/)
            }

            fun covariantOutcomeCase() {
                val initial: Outcome<String, Payload> = produce().asPass()
                val mapped: Outcome<String, Any> = forwardOutcome<String, Payload, Any>(initial) { item -> item }
                observeWidened(/*<observer>*/mapped.getOrNull()/*</observer>*/)
            }

            fun badCase() {
                val initial: PacketResult<Payload> = Bad(produce())
                val mapped = initial.map { item -> forbidden(item); item }
                observe(mapped.payload())
            }

            fun constantCase() {
                val initial = PlainGood(produce())
                val mapped = initial.map { item -> inputObserver(/*<input>*/item/*</input>*/); Payload() }
                observe(mapped.value)
            }

            fun forwardedConstantCase() {
                val initial = PlainGood(produce())
                val mapped = forwardPlainAgain(initial) { item -> inputObserver(/*<input>*/item/*</input>*/); Payload() }
                observe(mapped.value)
            }
            """
                .trimIndent()
    }
}
