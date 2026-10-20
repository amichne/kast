@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** A user-defined operator keeps its legitimate declared call while the builtin callback gate rejects it. */
class NativePacketUserInvokeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-user-invoke-jdk"

    fun testResolvedUserInvokeFailsTheBuiltinGateButKeepsItsDeclaredProducer() {
        val fixture = packetFixture(listOf(PATH))
        val expected = fixture.callableDeclaration(INVOKE)
        val request = fixture.selection(APPLY, "operation", INVOKE)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("Declared operator producer prerequisite failed", result is ValueProducerSeedRead.Seeded)
        val invocation =
            ((result as ValueProducerSeedRead.Seeded).seed.origin as ValueProducerOrigin.Declared).invocation
        assertEquals(expected.compilerIdentity, invocation.callable.compilerIdentity)
        assertEquals(request.anchor, invocation.range)
        ReadAction.run<RuntimeException> {
            val call = operation()
            IntellijReadObservation.None.observedAnalyze(call) {
                val symbol = checkNotNull(call.resolveCall()).signature.symbol
                val projected = symbol.compilerProjection(this)
                assertTrue(projected is IntellijCompilerProjectionResult.Projected)
                val evidence = (projected as IntellijCompilerProjectionResult.Projected).projection
                assertEquals(expected.compilerIdentity, evidence.identity)
                assertEquals("fixture.controls.ConstantInvocation.invoke", evidence.qualifiedIdentity)
                assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
            }
            val builtin = nativeBuiltinCallable(call, IntellijReadObservation.None)
            assertTrue("A source operator acquired builtin FunctionN proof", builtin is Refinement.Rejected)
            assertEquals(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE, (builtin as Refinement.Rejected).failure)
        }
    }

    fun testUserInvokeArgumentEntersItsDeclaredUnusedFormalWithoutAnInputResultEdge() {
        val fixture = packetFixture(listOf(PATH))
        val input = fixture.argumentExpression(APPLY, "operation", "source")
        val site = resolve(fixture, input)
        val argumentStep = step(fixture, site)
        assertEquals(listOf(ValueTransferKind.ARGUMENT), argumentStep.transfers.map { it.kind })
        val argument = argumentStep.transfers.single().target
        assertTrue(argument.role is ValueRole.Argument)
        assertFalse(argument.role is ValueRole.FunctionArgument)
        assertEquals(0, (argument.role as ValueRole.Argument).position.value)
        val invocation = (argument.role as ValueRole.Argument).call
        assertEquals(fixture.callableDeclaration(INVOKE).compilerIdentity, invocation.callable.compilerIdentity)
        val entered = step(fixture, argument)
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_ENTRY), entered.transfers.map { it.kind })
        val formal = entered.transfers.single().target
        val parameter = ((formal.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        val expected = fixture.formalParameter(INVOKE, 0)
        assertEquals(expected.enclosing.compilerIdentity, parameter.callable.compilerIdentity)
        assertEquals(expected.enclosing.compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(expected.anchor, parameter.range)
        assertEquals(ValueFormalOrigin.Declared(expected.anchor), parameter.origin)
        assertEquals("value", parameter.name.value)
        assertEquals(0, parameter.position.value)
        assertEquals(ValueCallTarget.Direct, formal.callStack.frames.last().target)
        assertEquals(invocation, formal.callStack.frames.last().invocation)
        val unused = step(fixture, formal)
        assertTrue("The genuine unused input acquired a constant-result edge", unused.transfers.isEmpty())
        assertTrue("The genuine unused formal did not exhaust", unused.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, unused.terminal)
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
        assertTrue("Real source-reference prerequisite failed", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
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
        assertTrue("Real source-hop prerequisite failed", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun operation(): KtCallExpression {
        val relative = "src/" + PATH.removePrefix("src/main/kotlin/")
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(relative))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtCallExpression::class.java).single {
            it.calleeExpression?.text == "operation"
        }
    }

    private companion object {
        const val PATH = "src/main/kotlin/fixture/controls/ConstantInvokeControls.kt"
        val APPLY = PacketDeclaration(PATH, "ConstantInvokeControls", "apply")
        val INVOKE = PacketDeclaration(PATH, "ConstantInvocation", "invoke")
    }
}
