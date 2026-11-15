package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CompleteCallbackSourceRelation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import kotlinx.coroutines.runBlocking

/** SAM and class constructors are never inline, so their lambdas are callbacks like any non-inline argument. */
class NativeSamConstructorCallerTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "sam-constructor-caller-jdk"

    override fun runInDispatchThread() = false

    fun testConstructorLambdasAreExcludedCallbacksLikeANonInlineArgument() {
        val fixture = fixture()
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers) }
        assertTrue(
            "A constructor lambda must not leave the caller read incomplete, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val batch = (compilation as RelationCompilation.Complete).batch
        assertEquals("Neither enclosing function executes the stored body", emptyList<Any>(), batch.facts)
        val callbacks = batch.callbackObservations.associateBy { it.occurrence.range.startInclusive }
        assertEquals(
            listOf("samCaller", "constructorCaller", "nonInlineCaller")
                .map { caller -> SAM_SOURCE.indexOf("target()", SAM_SOURCE.indexOf("fun $caller")) }
                .toSet(),
            callbacks.keys,
        )
        callbacks.values.forEach { callback ->
            val policy = callback.policy
            assertTrue("Expected an excluded named call, received $policy", policy is CallbackNamedCallPolicy.Excluded)
            assertEquals(
                CallbackExclusionReason.NON_INLINE_ARGUMENT,
                (policy as CallbackNamedCallPolicy.Excluded).reason,
            )
            // TRACE admits these as source relationships; the unknown execution effect stays explicit evidence.
            val source = CompleteCallbackSourceRelation.admit(callback)
            assertTrue("Expected an admitted source relationship, received $source", source is Refinement.Refined)
        }
        val sam = callbacks.getValue(SAM_SOURCE.indexOf("target()", SAM_SOURCE.indexOf("fun samCaller")))
        val flow = (sam.flow as CallbackInvocationFlowRead.Observed).flow
        assertEquals(setOf(CallbackInvocationFlowCause.EXTERNAL_CALLABLE), flow.obligations)
    }

    private fun fixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(SAM_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}

private val SAM_SOURCE =
    """
    package consumer
    fun interface Gateway { fun open(): Int }
    fun stored(block: () -> Int): () -> Int = block
    class Holder(val block: () -> Int)
    fun samCaller(): Gateway = Gateway { proof.target() }
    fun constructorCaller(): Holder = Holder { proof.target() }
    fun nonInlineCaller(): () -> Int = stored { proof.target() }
    """
        .trimIndent()
