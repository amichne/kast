package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression

/** Native adapter composition starts at origin(), never a component result or callback formal. */
class NativeCallbackInputTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "callback-original-input-jdk"

    fun testOriginalProducerEntersExactDestructuredCallbackFormal() {
        val fixture = fixture()
        val actual = fixture.argumentExpression(declaration("invokeParts"), "action", "value")
        val steps = walk(fixture, "lambdaInputCase", stop = actual)
        val source =
            steps
                .flatMap { it.transfers }
                .map { it.target }
                .single { site ->
                    site.range == actual.anchor &&
                        site.enclosing.compilerIdentity == actual.enclosing.compilerIdentity &&
                        site.role == ValueRole.LocalRead
                }
        assertTrue(source.valuePath.segments.isNotEmpty())
        assertEquals(1, source.callStack.frames.size)
        val transfer = entry(fixture, source).nativeRefined()
        val operation =
            (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.CallbackArgumentEntry
        assertEquals(source, transfer.source)
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        assertEquals(source.range, operation.argumentRange)
        assertEquals(source.callStack.frames.single(), operation.supplierFrame)
        assertEquals(source.valuePath, transfer.target.valuePath)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(source.conditions, transfer.target.conditions)
        val parameter = ((transfer.target.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        assertEquals(0, parameter.position.value)
        assertEquals(parameter.range, transfer.target.range)
        val execution = transfer.target.executionOwner.context as ValueExecutionContext.Callback
        assertEquals(execution.body, operation.invocation.body)
        assertEquals(listOf(operation.invocation), execution.invocations.filter { it.body == execution.body })
    }

    fun testOriginalProducerReachesOnlyExactLambdaInputConsumerThroughComponents() {
        val fixture = fixture()
        val steps = walk(fixture, "lambdaInputCase")
        val transfers = steps.flatMap { it.transfers }
        val expected = fixture.markedExpression(declaration("lambdaInputCase"), "lambdaInput-use")
        val consume = fixture.callableDeclaration(declaration("consume"))
        val forbidden = fixture.callableDeclaration(declaration("forbidden"))
        val consumers =
            transfers
                .map { it.target }
                .filter { site ->
                    val argument = site.role as? ValueRole.Argument
                    argument != null &&
                        argument.call.callable.compilerIdentity in
                            setOf(consume.compilerIdentity, forbidden.compilerIdentity)
                }
        assertEquals("Expected exact consumer; ${callbackRouteSummary(steps)}", 1, consumers.size)
        assertEquals(expected.anchor, consumers.single().range)
        assertEquals(expected.enclosing.compilerIdentity, consumers.single().enclosing.compilerIdentity)
        assertTrue(consumers.single().valuePath.segments.isEmpty())
        assertTrue(consumers.single().callStack.frames.isEmpty())
        assertTrue(
            transfers.any { transfer ->
                (transfer.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackArgumentEntry
            }
        )
        val component = fixture.callableDeclaration(declaration("component1", "SwappedParts"))
        assertTrue(
            transfers.any { transfer ->
                transfer.kind == ValueTransferKind.SOURCE_CALL_ENTRY &&
                    transfer.target.enclosing.compilerIdentity == component.compilerIdentity
            }
        )
        assertComplete(steps)
    }

    fun testDormantCallbackSuppliesNoInputFromOriginalProducer() = negative("dormantCase", "dormantInput")

    fun testDifferentActualArgumentDoesNotImportOriginalProducer() = negative("wrongActualCase", "wrongActualInput")

    private fun negative(function: String, wrapper: String) {
        val fixture = fixture()
        val steps = walk(fixture, function)
        val transfers = steps.flatMap { it.transfers }
        val entered = fixture.callableDeclaration(declaration(wrapper))
        assertTrue(
            "Original producer must reach the wrapper's exact first formal",
            transfers.any { transfer ->
                val formal = (transfer.target.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Parameter
                val exactFormal =
                    transfer.target.enclosing.compilerIdentity == entered.compilerIdentity &&
                        formal?.parameter?.position?.value == 0
                transfer.kind == ValueTransferKind.SOURCE_CALL_ENTRY &&
                    exactFormal &&
                    transfer.target.valuePath.segments.isNotEmpty()
            },
        )
        assertTrue(
            transfers.none { transfer ->
                (transfer.evidence as? ValueSourceTransfer)?.operation is ValueSourceOperation.CallbackArgumentEntry
            }
        )
        val consumers =
            listOf("consume", "forbidden").map { fixture.callableDeclaration(declaration(it)).compilerIdentity }
        assertTrue(
            transfers.none { (it.target.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity in consumers }
        )
        assertComplete(steps)
    }

    private fun entry(
        fixture: NativePacketValueFixture,
        source: ValueSite,
    ): Refinement<ValueTransfer, NativeCallbackInputFailure> =
        ReadAction.compute<Refinement<ValueTransfer, NativeCallbackInputFailure>, RuntimeException> {
            val domain =
                RelationRequest.start(
                    selector = fixture.callableDeclaration(declaration("invokeParts")),
                    meaning = RelationMeaning.References,
                    budget = budget(fixture),
                    boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
            val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
            assertTrue(prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
            prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
            val expression = checkNotNull(prepared.owner.exactValueElement(source.range))
            val call = checkNotNull(PsiTreeUtil.getParentOfType(expression, KtCallExpression::class.java, true))
            val mapped =
                nativeCallbackArgumentBinding(call, expression, prepared.projection.observation).nativeRefined()
            assertTrue(mapped is NativeCallbackArgumentBinding.Mapped)
            NativeCallbackArgumentInput(
                    source = source,
                    scope = prepared.scope,
                    projection = prepared.projection,
                    admitWork = { CallbackWorkAdmission.READY },
                    expiry = { CallbackExpiryAdmission.CURRENT },
                )
                .read(mapped as NativeCallbackArgumentBinding.Mapped)
        }

    private fun walk(
        fixture: NativePacketValueFixture,
        function: String,
        stop: PacketExpression? = null,
    ): List<ValueFlowStep> = runBlocking {
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
        while (pending.isNotEmpty()) {
            val source = pending.removeFirst()
            if (!visited.add(source.identity) || source.isStop(stop)) continue
            assertTrue("Callback route exceeded its finite site bound", visited.size <= MAXIMUM_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget(fixture), RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Expected native observation at ${source.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertEquals(source, step.source)
            assertTrue(step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        steps
    }

    private fun ValueSite.isStop(stop: PacketExpression?): Boolean =
        stop?.let { expression ->
            range == expression.anchor &&
                enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
                role == ValueRole.LocalRead
        } == true

    private fun assertComplete(steps: List<ValueFlowStep>) {
        assertTrue(
            "Unsupported callback input: ${callbackRouteSummary(steps)}",
            steps.all { it.obligations.isEmpty() },
        )
        assertTrue(steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted })
    }

    private fun budget(fixture: NativePacketValueFixture) =
        fixture.budget.copy(returnedBytes = RelationByteLimit.parse(RETURNED_BYTE_LIMIT).nativeRefined())

    private fun declaration(name: String, owner: String = "") = PacketDeclaration(COVERAGE, owner, name)

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
        const val MAXIMUM_SITES = 256
        const val RETURNED_BYTE_LIMIT = 16_000_000L
        const val COVERAGE = "src/main/kotlin/fixture/variants/coverage/COVERAGE.kt"
        const val NATIVE_PATH = "src/fixture/variants/coverage/COVERAGE.kt"
        val CONTROLS =
            """

            fun dormantInput(value: SwappedParts, action: (SwappedParts) -> Payload): Payload = Payload()
            fun dormantCase() {
                val result = dormantInput(SwappedParts(Payload(), origin())) { (value, unrelated) -> forbidden(value); value }
                consume(result)
            }
            fun wrongActualInput(value: SwappedParts, other: SwappedParts, action: (SwappedParts) -> Payload): Payload = action(other)
            fun wrongActualCase() {
                val result = wrongActualInput(SwappedParts(Payload(), origin()), SwappedParts(Payload(), Payload())) { (value, unrelated) -> forbidden(value); value }
                consume(result)
            }
            fun otherSupplierCase() {
                val result = invokeParts(SwappedParts(Payload(), Payload())) { (value, unrelated) -> forbidden(value); value }
                forbidden(result)
            }
            """
                .trimIndent()
    }
}
