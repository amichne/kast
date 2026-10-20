@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThisExpression

/** Real source symbols distinguish the extension value from the legitimate dispatch value. */
internal class NativePacketThisReadProof(private val project: Project, private val base: VirtualFile) {
    fun requireNotNull() =
        ReadAction.run<RuntimeException> {
            val function = function("requireNotNull")
            val expression = thisExpression(function, REQUIRE_THIS_START, REQUIRE_THIS_END)
            IntellijReadObservation.None.observedAnalyze(expression) {
                val receiver = checkNotNull(function.symbol.receiverParameter)
                assertEquals(KaSymbolOrigin.SOURCE, receiver.origin)
                val reference = expression.instanceReference.references.filterIsInstance<KtReference>().single()
                assertEquals(receiver, reference.resolveToSymbol())
            }
            println("THIS_READ_K2 function=requireNotNull this=1622:1626 symbol=EXACT_EXTENSION")
        }

    fun bind() =
        ReadAction.run<RuntimeException> {
            val component = function("component1")
            val bind = function("bind")
            val expression = thisExpression(bind, BIND_THIS_START, BIND_THIS_END)
            val call = PsiTreeUtil.collectElementsOfType(component, KtCallExpression::class.java).single()
            assertEquals(exact(BIND_CALL_START, BIND_CALL_END), range(call))
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall())
                assertEquals(bind.symbol, resolved.signature.symbol)
                val extension = resolved.extensionReceiver as KaImplicitReceiverValue
                val dispatch = resolved.dispatchReceiver as KaImplicitReceiverValue
                assertEquals(component.symbol.receiverParameter, extension.symbol)
                assertEquals(component.symbol.containingDeclaration, dispatch.symbol)
                assertEquals(bind.symbol.containingDeclaration, dispatch.symbol)
                assertFalse(extension.symbol == dispatch.symbol)
                val reference = expression.instanceReference.references.filterIsInstance<KtReference>().single()
                val actual = reference.resolveToSymbol()
                assertEquals(bind.symbol.receiverParameter, actual)
                assertFalse(dispatch.symbol == actual)
                assertEquals(KaSymbolOrigin.SOURCE, checkNotNull(actual).origin)
            }
            println("THIS_READ_K2 function=bind this=1189:1193 symbol=EXTENSION distinct_dispatch=ASSERTED")
        }

    private fun thisExpression(function: KtNamedFunction, start: Int, end: Int): KtThisExpression {
        val expression =
            PsiTreeUtil.collectElementsOfType(function, KtThisExpression::class.java).single { candidate ->
                range(candidate) == exact(start, end)
            }
        assertTrue(PsiTreeUtil.isAncestor(function, expression, true))
        return expression
    }

    private fun function(name: String): KtNamedFunction {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == name }
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val REQUIRE_THIS_START = 1622
        const val REQUIRE_THIS_END = 1626
        const val BIND_THIS_START = 1189
        const val BIND_THIS_END = 1193
        const val BIND_CALL_START = 1137
        const val BIND_CALL_END = 1143
    }
}
