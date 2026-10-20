@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** The original Elvis and throw syntax establish branch facts, without a null-value or exception-flow model. */
internal class NativePacketNullableBranchSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun capture() =
        ReadAction.run<RuntimeException> {
            val file = checkNotNull(base.findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val functions = PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java)
            val require = functions.single { it.name == "requireNotNull" }
            val bind = functions.single { it.name == "bind" }
            IntellijReadObservation.None.observedAnalyze(require) { elvis(require) }
            IntellijReadObservation.None.observedAnalyze(bind) { thrown(bind) }
            println("NULLABLE_BRANCH_CAPTURE status=SOURCE_AND_K2 branch_transfer,null_fact,Halt_catch_match=NOT_RUN")
        }

    private fun KaSession.elvis(function: KtNamedFunction) {
        val branch = function.bodyExpression as KtBinaryExpression
        assertEquals(KtTokens.ELVIS, branch.operationToken)
        val safe = branch.left as KtSafeQualifiedExpression
        val fallback = branch.right as org.jetbrains.kotlin.psi.KtDotQualifiedExpression
        assertEquals(exact(SAFE_START, SAFE_END), range(safe))
        assertEquals(exact(FALLBACK_START, FALLBACK_END), range(fallback))
        val factory = fallback.selectorExpression as KtCallExpression
        val invocation = factory.valueArguments.single().getArgumentExpression() as KtCallExpression
        assertEquals(exact(ERROR_INVOKE_START, ERROR_INVOKE_END), range(invocation))
        val expected = function.symbol.valueParameters.single()
        val resolved = checkNotNull(invocation.resolveCall())
        val dispatch = resolved.dispatchReceiver as KaExplicitReceiverValue
        val reference = dispatch.expression as KtNameReferenceExpression
        assertEquals(expected, reference.references.filterIsInstance<KtReference>().single().resolveToSymbol())
        val projected = resolved.signature.symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val signature =
            (projected as IntellijCompilerProjectionResult.Projected).projection.signature
                as CanonicalCompilerSignature.Function
        assertEquals("kotlin.Function0.invoke", signature.qualifiedIdentity.value)
        val expectedFactory = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Companion", "error"))
        val factoryProjection = checkNotNull(factory.resolveCall()).signature.symbol.compilerProjection(this)
        assertTrue(factoryProjection is IntellijCompilerProjectionResult.Projected)
        assertEquals(
            expectedFactory.compilerIdentity,
            (factoryProjection as IntellijCompilerProjectionResult.Projected).projection.identity,
        )
        println(
            "NULLABLE_ELVIS left=1622:1655 right=1659:1681 errorInvoke=1673:1680 " +
                "invokedFormal=EXACT_REQUIRE_ERROR_PARAMETER " +
                "leftTypeDiagnostic=${safe.expressionType.toString().take(MAX_TYPE)} " +
                "rightTypeDiagnostic=${fallback.expressionType.toString().take(MAX_TYPE)}"
        )
    }

    private fun KaSession.thrown(function: KtNamedFunction) {
        val branch = function.bodyExpression as KtWhenExpression
        val thrown = PsiTreeUtil.collectElementsOfType(branch, KtThrowExpression::class.java).single()
        val entry = thrown.parent as KtWhenEntry
        assertEquals(branch, entry.parent)
        val condition = entry.conditions.single() as KtWhenConditionIsPattern
        assertFalse(condition.isNegated)
        val conditionType = checkNotNull(condition.typeReference).type as KaClassType
        assertEquals("fixture.outcome.Outcome.Error", conditionType.classId.asSingleFqName().asString())
        val checked = branch.subjectExpression as KtThisExpression
        val checkedReference = checked.instanceReference.references.filterIsInstance<KtReference>().single()
        assertEquals(function.symbol.receiverParameter, checkedReference.resolveToSymbol())
        assertEquals(exact(THROW_START, THROW_END), range(thrown))
        val call = thrown.thrownExpression as KtCallExpression
        val expected = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Halt", "<init>"))
        val projected = checkNotNull(call.resolveCall()).signature.symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        assertEquals(
            expected.compilerIdentity,
            (projected as IntellijCompilerProjectionResult.Projected).projection.identity,
        )
        val type = thrown.expressionType as KaClassType
        assertEquals("kotlin.Nothing", type.classId.asSingleFqName().asString())
        assertFalse(type.isMarkedNullable)
        println(
            "NULLABLE_THROW source=1261:1278 target=SOURCE.Halt type=KOTLIN_NOTHING_NON_NULL " +
                "condition=K2.Outcome.Error subject=EXACT_BIND_EXTENSION"
        )
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val SAFE_START = 1622
        const val SAFE_END = 1655
        const val FALLBACK_START = 1659
        const val FALLBACK_END = 1681
        const val ERROR_INVOKE_START = 1673
        const val ERROR_INVOKE_END = 1680
        const val THROW_START = 1261
        const val THROW_END = 1278
        const val MAX_TYPE = 160
    }
}
