@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/** Source controls isolate return classification. The original S2 seed remains the acceptance case. */
class NativePacketAnonymousReturnTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-anonymous-return-jdk"

    fun testGenuineAnonymousNormalResultEmitsReturn() = assertNormal(ReturnControl.NORMAL)

    fun testExplicitLocalLabelReturnTargetsTheSameAnonymousBody() = assertNormal(ReturnControl.LOCAL)

    fun testDiscardedNonfinalExpressionDoesNotEmitReturn() {
        val step = controlStep(ReturnControl.DISCARDED)
        assertTrue("Discarded value acquired a return: ${step.transfers}", step.transfers.isEmpty())
        assertTrue(
            "Discarded pure value left an unsupported obligation: ${step.obligations}",
            step.obligations.isEmpty(),
        )
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
    }

    fun testNonlocalReturnKeepsAbruptCompletion() =
        assertRejected(ReturnControl.NONLOCAL, ValueFlowUnsupportedCause.ABRUPT_COMPLETION)

    fun testThrownValueKeepsAbruptCompletion() =
        assertRejected(ReturnControl.ABRUPT, ValueFlowUnsupportedCause.ABRUPT_COMPLETION)

    fun testImplicitTryFinallyResultKeepsItsExplicitCause() =
        assertRejected(ReturnControl.IMPLICIT_FINALLY, ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)

    fun testExplicitReturnAcrossFinallyKeepsItsExplicitCause() =
        assertRejected(ReturnControl.EXPLICIT_FINALLY, ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)

    private fun assertNormal(control: ReturnControl) {
        val step = controlStep(control)
        assertEquals(listOf(ValueTransferKind.RETURN), step.transfers.map { it.kind })
        val transfer = step.transfers.single()
        assertEquals(ValueRole.Return, transfer.target.role)
        assertEquals(transfer.source.range, transfer.target.range)
        assertEquals(transfer.source.executionOwner, transfer.target.executionOwner)
        assertEquals(transfer.source.callStack, transfer.target.callStack)
        assertTrue(step.obligations.isEmpty())
    }

    private fun assertRejected(control: ReturnControl, cause: ValueFlowUnsupportedCause) {
        val step = controlStep(control)
        assertTrue("An unsupported completion acquired a normal return: ${step.transfers}", step.transfers.isEmpty())
        assertEquals(listOf(cause), step.obligations.map { it.cause })
        assertTrue(step.obligations.all { it.site == step.source })
        assertEquals(ValueFlowTerminal.Unresolved, step.terminal)
    }

    private fun controlStep(control: ReturnControl): ValueFlowStep {
        val fixture = packetFixture(listOf(SOURCE))
        val expected = syntax(control)
        val request =
            fixture.selection(
                PacketDeclaration(SOURCE, OWNER, control.method),
                control.callee,
                PacketDeclaration(SOURCE, OWNER, control.callee),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("Control seed prerequisite failed: $result", result is ValueProducerSeedRead.Seeded)
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(expected.call, seed.site.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        assertTrue(seed.site.executionOwner.body is ValueExecutionBody.Anonymous)
        assertEquals(expected.body, seed.site.executionOwner.range)
        return step(fixture, seed.site)
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Control read prerequisite failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { read ->
            println(
                "ANONYMOUS_RETURN_CONTROL source=${site.range} transfers=${read.transfers.map { it.kind }} " +
                    "frontier=${read.obligations.map { it.cause }}"
            )
        }
    }

    private fun syntax(control: ReturnControl): AnonymousReturnAnchors =
        ReadAction.compute<AnonymousReturnAnchors, RuntimeException> {
            val file = psi()
            val host =
                PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single {
                    it.name == control.method
                }
            val call =
                PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == control.callee
                }
            val literal = checkNotNull(PsiTreeUtil.getParentOfType(call, KtFunctionLiteral::class.java))
            assertSyntax(control = control, host = host, call = call, literal = literal)
            AnonymousReturnAnchors(range(call), range(literal))
        }

    private fun assertSyntax(
        control: ReturnControl,
        host: KtNamedFunction,
        call: KtCallExpression,
        literal: KtFunctionLiteral,
    ) =
        IntellijReadObservation.None.observedAnalyze(call) {
            when (control) {
                ReturnControl.NORMAL -> {
                    assertEquals(call, literal.bodyExpression?.statements?.single())
                    assertTrue(call.isUsedAsResultOfLambda)
                }
                ReturnControl.LOCAL -> {
                    val returned = call.parent as KtReturnExpression
                    assertEquals(literal.symbol, returned.resolveSymbol())
                }
                ReturnControl.DISCARDED -> {
                    assertEquals(DISCARDED_STATEMENTS, literal.bodyExpression?.statements?.size)
                    assertEquals(call, literal.bodyExpression?.statements?.first())
                    assertFalse(call.isUsedAsResultOfLambda)
                }
                ReturnControl.NONLOCAL -> {
                    val returned = call.parent as KtReturnExpression
                    assertEquals(host.symbol, returned.resolveSymbol())
                    assertFalse(literal.symbol == returned.resolveSymbol())
                }
                ReturnControl.ABRUPT -> assertTrue(call.parent is KtThrowExpression)
                ReturnControl.IMPLICIT_FINALLY,
                ReturnControl.EXPLICIT_FINALLY -> {
                    val tried = checkNotNull(PsiTreeUtil.getParentOfType(call, KtTryExpression::class.java))
                    assertNotNull(tried.finallyBlock)
                }
            }
        }

    private fun psi(): KtFile {
        val path = "src/" + SOURCE.removePrefix("src/main/kotlin/")
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(path))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private companion object {
        const val SOURCE = "src/main/kotlin/fixture/controls/AnonymousReturnControls.kt"
        const val OWNER = "AnonymousReturnControls"
        const val DISCARDED_STATEMENTS = 2
    }
}

private enum class ReturnControl(val method: String, val callee: String = "readValue") {
    NORMAL("genuineNormal"),
    LOCAL("explicitLocal"),
    DISCARDED("discarded"),
    NONLOCAL("nonlocal"),
    ABRUPT("abrupt", "failure"),
    IMPLICIT_FINALLY("implicitFinally"),
    EXPLICIT_FINALLY("explicitFinally"),
}

private data class AnonymousReturnAnchors(val call: ExactDeclarationTextRange, val body: ExactDeclarationTextRange)
