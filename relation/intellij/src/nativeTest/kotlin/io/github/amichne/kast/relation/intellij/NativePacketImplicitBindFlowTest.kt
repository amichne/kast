@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Each case keeps the real component call context. Supplemental dispatch input uses only its proved whole receiver. */
class NativePacketImplicitBindFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-implicit-bind-flow-jdk"

    fun testOriginalComponentExtensionFormalReachesOnlyTheBindExtensionReceiver() {
        val case = sourceCase()
        assertReceiver(case, case.formal, ValueReceiverKind.EXTENSION)
    }

    fun testK2ProvedComponentDispatchFormalReachesOnlyTheBindDispatchReceiver() {
        val case = sourceCase()
        assertReceiver(case, dispatchFormal(case), ValueReceiverKind.DISPATCH)
    }

    fun testK2ProvedBindExtensionReceiverEntersOnlyTheBindExtensionFormal() {
        val case = sourceCase()
        assertEntry(case, case.formal, ValueReceiverKind.EXTENSION)
    }

    fun testK2ProvedBindDispatchReceiverEntersOnlyTheBindDispatchFormal() {
        val case = sourceCase()
        assertEntry(case, dispatchFormal(case), ValueReceiverKind.DISPATCH)
    }

    private fun assertReceiver(case: ImplicitBindCase, source: ValueSite, kind: ValueReceiverKind) {
        val result = step(case.fixture, source)
        val receivers = result.transfers.filter { it.kind == ValueTransferKind.RECEIVER }.map { it.target }
        assertTrue(
            "C2.7 needs component1 Formal($kind) to bind Receiver($kind)1137:1143. " +
                "Frontier=${result.obligations.map { it.cause }}. Bind payload and matched return remain NOT_REACHED",
            receiver(case, source, kind) in receivers,
        )
        val forbidden = ValueRole.Receiver(case.call, opposite(kind))
        assertTrue(
            "The same-call opposite receiver slot must not receive this formal",
            receivers.none { it.role == forbidden },
        )
    }

    private fun assertEntry(case: ImplicitBindCase, source: ValueSite, kind: ValueReceiverKind) {
        val actual = receiver(case, source, kind)
        println("IMPLICIT_BIND_SETUP kind=K2_PROVED_TYPED_RECEIVER slot=$kind first_transfer=NOT_ASSERTED")
        val result = step(case.fixture, actual)
        val entries = result.transfers.filter { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        val position = ValueFormalPosition.Receiver(kind)
        val correct = entries.filter { it.target.role == ValueRole.Formal(position) }
        assertEquals(
            "C2.7 needs the exact bind $kind formal. Frontier=${result.obligations.map { it.cause }}",
            1,
            correct.size,
        )
        val transfer = correct.single()
        val callee = ValueExecutionOwner.declaration(case.call.callable)
        val frame = ValueCallFrame.direct(case.call, callee).nativeRefined()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Enter
        assertEquals(frame, operation.frame)
        assertEquals(position, operation.position)
        assertEquals(callee, transfer.target.executionOwner)
        assertEquals(callee.range, transfer.target.range)
        assertEquals(actual.valuePath, transfer.target.valuePath)
        assertEquals(actual.predicates, transfer.target.predicates)
        assertEquals(actual.callStack.frames + frame, transfer.target.callStack.frames)
        val forbidden = ValueRole.Formal(ValueFormalPosition.Receiver(opposite(kind)))
        assertTrue(
            "The same-call opposite formal must not receive this receiver",
            entries.none { it.target.role == forbidden },
        )
    }

    private fun sourceCase(): ImplicitBindCase {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val component = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "component1"))
        val bind = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "bind"))
        ReadAction.run<RuntimeException> { proveSlots(component, bind) }
        val original = NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture)
        val expected = ReadAction.compute<DestructuringProbeExpectation, RuntimeException> { original.expectation() }
        val fields = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val tracked =
            ValueAccessPath.fromSegments(
                    listOf(
                        ValuePathSegment.Field(fields.field(OUTCOME, "Success", "value")),
                        ValuePathSegment.Field(fields.field(CLIENT, "ClientResponse", "links")),
                    )
                )
                .nativeRefined()
        val formal = componentFormal(fixture = fixture, expected = expected, tracked = tracked, component = component)
        val call =
            ValueInvocation.fromCompiler(
                    enclosing = formal.enclosing,
                    range = exact(BIND_START, BIND_END),
                    callable = RelationEndpoint.subject(bind),
                    executionOwner = formal.executionOwner,
                )
                .nativeRefined()
        return ImplicitBindCase(fixture, formal, call)
    }

    private fun componentFormal(
        fixture: NativePacketValueFixture,
        expected: DestructuringProbeExpectation,
        tracked: ValueAccessPath,
        component: SymbolSelector,
    ): ValueSite {
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(expected.enclosing),
                RelationMeaning.References,
                fixture.budget,
            )
        val found = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expected.enclosing,
                    anchor = expected.initializer,
                    domain = domain,
                    model = fixture.model,
                )
        }
        assertTrue("Original initializer prerequisite did not admit", found is ValueSiteResolution.Found)
        val initializer = (found as ValueSiteResolution.Found).site.withState(valuePath = tracked).nativeRefined()
        val receiver =
            step(fixture, initializer)
                .transfers
                .single { transfer ->
                    transfer.kind == ValueTransferKind.RECEIVER &&
                        transfer.target.range == exact(ENTRY_START, ENTRY_END)
                }
                .target
        val role = receiver.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(component.compilerIdentity, role.call.callable.compilerIdentity)
        val formal = step(fixture, receiver).transfers.single { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }.target
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertEquals(ValueExecutionOwner.declaration(RelationEndpoint.subject(component)), formal.executionOwner)
        assertEquals(tracked, formal.valuePath)
        assertEquals(expected.executionOwner, formal.callStack.frames.last().invocation.executionOwner)
        return formal
    }

    private fun dispatchFormal(case: ImplicitBindCase): ValueSite {
        println("IMPLICIT_BIND_SETUP kind=K2_PROVED_TYPED_DISPATCH_FORMAL path=WHOLE")
        return ValueSite.fromCompiler(
                enclosing = case.formal.enclosing,
                range = case.formal.range,
                role = ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)),
                executionOwner = case.formal.executionOwner,
                valuePath = ValueAccessPath.Whole,
                callStack = case.formal.callStack,
                predicates = case.formal.predicates,
            )
            .nativeRefined()
    }

    private fun receiver(case: ImplicitBindCase, source: ValueSite, kind: ValueReceiverKind): ValueSite =
        ValueSite.fromCompiler(
                enclosing = source.enclosing,
                range = case.call.range,
                role = ValueRole.Receiver(case.call, kind),
                executionOwner = source.executionOwner,
                valuePath = source.valuePath,
                callStack = source.callStack,
                predicates = source.predicates,
            )
            .nativeRefined()

    private fun proveSlots(component: SymbolSelector, bind: SymbolSelector) {
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        val function =
            PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == "component1" }
        val call = PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java).single()
        assertEquals(component.range, range(function))
        assertEquals(exact(BIND_START, BIND_END), range(call))
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            val projected = resolved.signature.symbol.compilerProjection(this)
            assertTrue(projected is IntellijCompilerProjectionResult.Projected)
            assertEquals(
                bind.compilerIdentity,
                (projected as IntellijCompilerProjectionResult.Projected).projection.identity,
            )
            val extension = resolved.extensionReceiver as? KaImplicitReceiverValue
            val dispatch = resolved.dispatchReceiver as? KaImplicitReceiverValue
            assertNotNull("Original bind extension needs the proved implicit receiver", extension)
            assertNotNull("Original bind dispatch needs the proved implicit receiver", dispatch)
            assertEquals(function.symbol.receiverParameter, checkNotNull(extension).symbol)
            assertEquals(function.symbol.containingDeclaration, checkNotNull(dispatch).symbol)
            assertFalse(extension.symbol == dispatch.symbol)
        }
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Exact implicit-bind setup did not produce a native read", read is ValueFlowRead.Observed)
        val result = (read as ValueFlowRead.Observed).step
        val sourceRole =
            when (val role = source.role) {
                is ValueRole.Receiver -> "RECEIVER:${role.kind}"
                is ValueRole.Formal -> "FORMAL:${role.position}"
                else -> "EXPRESSION_RESULT_PREREQUISITE"
            }
        println(
            "IMPLICIT_BIND_FLOW source=${source.range} sourceRole=$sourceRole pathDepth=${source.valuePath.depth} " +
                "transfers=${result.transfers.map { it.kind to it.target.range }} " +
                "causes=${result.obligations.map { it.cause }} work=${result.examinedWorkUnits.value}"
        )
        return result
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private fun opposite(kind: ValueReceiverKind): ValueReceiverKind =
        when (kind) {
            ValueReceiverKind.EXTENSION -> ValueReceiverKind.DISPATCH
            ValueReceiverKind.DISPATCH -> ValueReceiverKind.EXTENSION
        }

    private companion object {
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val ENTRY_START = 806
        const val ENTRY_END = 814
        const val BIND_START = 1137
        const val BIND_END = 1143
    }
}

private data class ImplicitBindCase(
    val fixture: NativePacketValueFixture,
    val formal: ValueSite,
    val call: ValueInvocation,
)
