package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackReferenceReceiver
import io.github.amichne.kast.relation.contract.CallbackResultSite
import io.github.amichne.kast.relation.contract.CallbackResultSites
import io.github.amichne.kast.relation.contract.NamedCallbackReference
import io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativeIterationReferenceTest : NativeDependencyCallbackFixture() {
    override val fixtureSdkName = "iteration-reference-case-jdk"

    override fun runInDispatchThread() = false

    fun testIterableSupplyKeepsTheBoundIntMethodWithoutANamedCall() {
        val fixture =
            fixture(
                expression = "Unit",
                sources = false,
                helper =
                    "class Ledger { fun post(i: Int) = i; fun post(i: Long) = i }\n" +
                        "class StockLine(val cartons: Int)\n" +
                        "fun plan(lines: List<StockLine>, ledger: Ledger) = " +
                        "lines.map { it.cartons }.forEach(ledger::post)\n",
                libraryJar =
                    Path.of(
                        checkNotNull(javaClass.getResource("/reviewed-libraries/kotlin-stdlib-2.4.10.jar")).toURI()
                    ),
            )
        ReadAction.run<RuntimeException> {
            val value = readReference(fixture)
            val flow = value.flow
            assertTrue(
                "Reviewed supply unavailable: " + (flow as? NamedCallbackReferenceFlow.Unavailable)?.causes,
                flow is NamedCallbackReferenceFlow.ReviewedSupply,
            )
            val binding = (flow as NamedCallbackReferenceFlow.ReviewedSupply).binding
            assertEquals("REVIEWED_KOTLIN_2_4_10_ITERABLE_FOR_EACH", binding.provenance.name)
            assertEquals("ZERO_OR_MORE", binding.invocationKind.name)
            val target = value.target.evidence.signature as CanonicalCompilerSignature.Function
            assertEquals("consumer.Ledger.post", target.qualifiedIdentity.value)
            assertEquals(listOf("kotlin/Int"), target.valueParameters.map { it.value })
            assertTrue(value.receivers.dispatch is CallbackReferenceReceiver.Bound)
            assertEquals(
                listOf(CallbackResultSite.Occurrence(value.occurrence)),
                CallbackResultSites.reference(RelationMeaning.Callers, value),
            )
            assertFalse(value.hasStaticInvocation)
        }
    }

    private fun readReference(fixture: NativeReferenceFixture): NamedCallbackReference {
        val file = psiManager.findFile(fixture.callerFile) as KtFile
        val plan = PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single { it.name == "plan" }
        val reference = checkNotNull(PsiTreeUtil.findChildOfType(plan, KtCallableReferenceExpression::class.java))
        val observation = IntellijReadObservation.None
        val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, observation)
        val lexical = (projection.project(plan) as IntellijRelationDeclarationProjection.Projected).evidence
        val collector = IntellijRelationCollector(fixture.request, observation = observation)
        val context =
            IntellijCallbackFlowContext(
                scope = fixture.scope(observation),
                projection = projection,
                admitWork = collector::admitCallbackWork,
                observation = observation,
                callbackExpiry = collector::callbackExpiry,
            )
        val reader =
            IntellijNamedCallbackReference(context, CallbackParameterSummaries(fixture.request.budget, observation))
        val result = reader.read(reference, lexical)
        assertTrue("Expected source reference, received " + result, result is Refinement.Refined)
        return (result as Refinement.Refined).value
    }
}
