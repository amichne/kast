package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackReadmission
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePort
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Thin K2 qualification; hosted modeled/unmodeled cache attribution is proved separately by the pure fixture. */
class NativeCallbackCoverageTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-coverage-case-jdk"

    override fun runInDispatchThread() = false

    fun testFreshCompilerCallbackCoverageSurvivesOptionalCacheMiss() {
        val fixture = callbackFixture("action()")
        val disabled = read(fixture, CallbackSummaryCachePort.Disabled)
        val cache = MissOnlyCache()
        val missed = read(fixture, cache)
        cache.assertConsumed(expectedRetains = 1)
        val expected = disabled.first.flow
        val actual = missed.first.flow
        assertEquals(expected.basis, actual.basis)
        assertEquals(expected.body, actual.body)
        assertEquals(expected.binding, actual.binding)
        assertEquals(expected.invocations, actual.invocations)
        assertEquals(expected.obligations, actual.obligations)
        assertEquals(expected.ownerBindings, actual.ownerBindings)
        assertEquals(expected.scan, actual.scan)
        val expectedGraph = (expected.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
        val actualGraph = (actual.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
        assertEquals(expectedGraph.root, actualGraph.root)
        assertEquals(expectedGraph.formals, actualGraph.formals)
        assertEquals(expectedGraph.forwardings, actualGraph.forwardings)
        assertEquals(emptySet<CallbackInvocationFlowCause>(), missed.first.flow.obligations)
        assertEquals(1, missed.first.flow.invocations.size)
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_COMPLETE,
            ),
            missed.second.coverage,
        )
    }

    fun testEscapingFormalIsTheFirstIncompleteCompilerTransition() {
        val fixture = callbackFixture("var stored = action")
        val cache = MissOnlyCache()
        val (observed, counters) = read(fixture, cache)
        cache.assertConsumed(expectedRetains = 0)
        assertEquals(
            setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES, CallbackInvocationFlowCause.NO_INVOCATION_PROVEN),
            observed.flow.obligations,
        )
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
                IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
            ),
            counters.coverage,
        )
        assertEquals(
            listOf(
                IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES,
                IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES,
                IntellijReadTermination.CALLBACK_SUPPLIER_NO_INVOCATION_PROVEN,
            ),
            counters.reasons,
        )
    }

    private fun callbackFixture(wrapperBody: String): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(
                    "package consumer\nfun wrapper(action: () -> Unit) { $wrapperBody }\nfun caller() { wrapper { proof.target() } }\n"
                        .toByteArray()
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private fun read(
        fixture: NativeReferenceFixture,
        cache: CallbackSummaryCachePort,
    ): Pair<CallbackInvocationFlowRead.Observed, NativeCoverageObservation> =
        ReadAction.compute<Pair<CallbackInvocationFlowRead.Observed, NativeCoverageObservation>, RuntimeException> {
            val observation = NativeCoverageObservation()
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            val caller = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "caller" }
            val literal =
                checkNotNull(PsiTreeUtil.findChildOfType(caller, KtLambdaExpression::class.java)).functionLiteral
            val scope = fixture.scope(observation)
            val projection =
                IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, observation)
            val owner = (projection.project(caller) as IntellijRelationDeclarationProjection.Projected).evidence
            val collector = IntellijRelationCollector(fixture.request, observation = observation)
            val result =
                readCallbackInvocationFlow(
                    boundary = literal,
                    lexicalOwner = owner,
                    scope = scope,
                    projection = projection,
                    admitWork = collector::admitCallbackWork,
                    summaries = CallbackParameterSummaries(fixture.request.budget, observation, cache),
                    callbackExpiry = collector::callbackExpiry,
                )
            assertTrue(
                "Expected compiler callback flow, received $result",
                result is CallbackInvocationFlowRead.Observed,
            )
            (result as CallbackInvocationFlowRead.Observed) to observation
        }
}

private class MissOnlyCache : CallbackSummaryCachePort {
    private var finds = 0
    private var retains = 0

    override fun find(
        formal: CallbackParameterIdentity,
        readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
    ): CallbackSummaryCacheLookup {
        finds++
        if (finds > 1) throw AssertionError("Only the selected formal may query optional reuse")
        return CallbackSummaryCacheLookup.Miss
    }

    override fun retain(summary: CallbackParameterSummary) {
        retains++
        if (retains > 1) throw AssertionError("Only one fresh exhaustive formal may be published")
    }

    override fun admitted(summary: CallbackParameterSummary) = error("Miss-only cache cannot supply admitted facts")

    fun assertConsumed(expectedRetains: Int) {
        org.junit.Assert.assertEquals("Optional cache lookup count", 1, finds)
        org.junit.Assert.assertEquals("Optional fresh publication count", expectedRetains, retains)
    }
}

private class NativeCoverageObservation : IntellijReadObservation {
    val coverage = mutableListOf<IntellijReadCounter>()
    val reasons = mutableListOf<IntellijReadTermination>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        if (
            counter in
                setOf(
                    IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
                    IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
                    IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_COMPLETE,
                    IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
                )
        )
            repeat(amount) { coverage += counter }
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        reasons += reason
    }
}
