@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/** Actual K2 receiver symbols prove the alias. An independent native read supplies the contrasting empty context. */
internal class NativePacketCallerNonNullFacts(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun prove(actual: CallerNonNullCase) =
        ReadAction.run<RuntimeException> {
            val frame = actual.returned.callStack.frames.last()
            assertTrue(frame.target is ValueCallTarget.Direct)
            val outcome = psi("src/fixture/outcome/Outcome.kt")
            val callee =
                PsiTreeUtil.collectElementsOfType(outcome, KtNamedFunction::class.java).single {
                    it.name == "requireNotNull"
                }
            val checked =
                PsiTreeUtil.collectElementsOfType(callee, KtThisExpression::class.java).single {
                    range(it) == actual.checked.range
                }
            val feature = psi("src/fixture/feature/OffersProcessor.kt")
            val caller =
                PsiTreeUtil.collectElementsOfType(feature, KtQualifiedExpression::class.java).single {
                    range(it) == frame.invocation.range
                }
            val call = caller.selectorExpression as KtCallExpression
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall())
                assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
                assertEquals(callee.originalElement, checkNotNull(resolved.signature.symbol.psi).originalElement)
                val receiver = resolved.extensionReceiver as KaExplicitReceiverValue
                assertEquals(caller.receiverExpression.originalElement, receiver.expression.originalElement)
                assertEquals(actual.receiver.range, range(receiver.expression))
                assertEquals(null, resolved.dispatchReceiver)
            }
            IntellijReadObservation.None.observedAnalyze(checked) {
                val receiver = callee.symbol.receiverParameter
                assertNotNull(receiver)
                assertEquals(KaSymbolOrigin.SOURCE, checkNotNull(receiver).origin)
                assertEquals(
                    receiver,
                    checked.instanceReference.references.filterIsInstance<KtReference>().single().resolveToSymbol(),
                )
            }
            assertEquals(frame.callee, actual.returned.executionOwner)
            assertEquals(actual.returned.enclosing, actual.checked.enclosing)
            assertEquals(actual.returned.executionOwner, actual.checked.executionOwner)
            assertEquals(actual.returned.callStack, actual.checked.callStack)
            assertEquals(actual.receiver.callStack.frames + frame, actual.returned.callStack.frames)
            println(
                "CALLER_NONNULL_K2 current_this=EXACT_SOURCE_EXTENSION " +
                    "caller_receiver=EXACT_CURRENT_CALL direct_frame=ACTUAL"
            )
        }

    fun unboundThisPredicate(read: (ValueSite) -> ValueFlowStep): ValuePredicate.NonNull {
        val host = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "", "requireNotNull"))
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = host,
                    anchor = exact(THIS_START, THIS_END),
                    role = ValueSiteRoleClaim.LocalRead,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(
            "The contrasting unbound this needs real compiler role admission",
            result is ValueModelSiteRead.Revalidated,
        )
        val site = (result as ValueModelSiteRead.Revalidated).position.site
        assertEquals(ValueRole.LocalRead, site.role)
        assertTrue(site.callStack.frames.isEmpty())
        val receiver = read(site).transfers.single { it.target.role is ValueRole.Receiver }.target
        val facts = receiver.predicates.entries.filterIsInstance<ValuePredicate.NonNull>()
        assertEquals(1, facts.size)
        val predicate = facts.single()
        assertEquals(site.range, predicate.subject.occurrence.range)
        assertTrue(predicate.subject.occurrence.callStack.frames.isEmpty())
        println("CALLER_NONNULL_CONTROL predicate_source=ACTUAL_SAFE_CALL context=EMPTY caller_alias=NOT_ASSUMED")
        return predicate
    }

    private fun psi(path: String) =
        PsiManager.getInstance(project).findFile(checkNotNull(base.findFileByRelativePath(path))) as KtFile

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val THIS_START = 1622
        const val THIS_END = 1626
    }
}
