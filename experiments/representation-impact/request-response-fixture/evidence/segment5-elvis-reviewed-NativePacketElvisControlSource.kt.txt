@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTryExpression

/** Source/K2 setup fixes the immediate operand, actual nullability and expression context before native assertions. */
internal class NativePacketElvisControlSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun control(control: ElvisControl): ElvisControlCase {
        val syntax = syntax(control)
        val request =
            fixture.selection(
                PacketDeclaration(SOURCE, OWNER, control.method),
                control.callee,
                PacketDeclaration(SOURCE, OWNER, control.callee),
            )
        val found = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("The actual source call must admit before the Elvis control", found is ValueProducerSeedRead.Seeded)
        val seed = (found as ValueProducerSeedRead.Seeded).seed
        assertEquals(syntax.call, seed.site.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        return ElvisControlCase(seed.site, syntax.branch, syntax.right)
    }

    fun read(site: ValueSite): ValueFlowStep = NativePacketLetCallbackSource(project, base, fixture).read(site)

    private fun syntax(control: ElvisControl): ElvisControlSyntax =
        ReadAction.compute<ElvisControlSyntax, RuntimeException> {
            val file = checkNotNull(base.findFileByRelativePath("src/fixture/controls/ElvisResultControls.kt"))
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val host =
                PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == control.method }
            val call =
                PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == control.callee
                }
            val branch = checkNotNull(PsiTreeUtil.getParentOfType(call, KtBinaryExpression::class.java))
            assertEquals(KtTokens.ELVIS, branch.operationToken)
            when (control) {
                ElvisControl.FOREIGN -> {
                    assertFalse(branch.left === call)
                    assertFalse(branch.right === call)
                }
                ElvisControl.RIGHT,
                ElvisControl.RIGHT_NULL,
                ElvisControl.BOTTOM -> assertSame(call, branch.right)
                else -> assertSame(call, branch.left)
            }
            context(control, host, branch)
            types(control = control, call = call, branch = branch)
            ElvisControlSyntax(range(call), range(branch), range(checkNotNull(branch.right)))
        }

    private fun context(control: ElvisControl, host: KtNamedFunction, branch: KtBinaryExpression) {
        when (control) {
            ElvisControl.DISCARDED -> {
                val block = host.bodyExpression as KtBlockExpression
                assertEquals(DISCARDED_STATEMENTS, block.statements.size)
                assertSame(branch, block.statements.first())
            }
            ElvisControl.UNIT -> assertSame(branch, (host.bodyExpression as KtBlockExpression).statements.single())
            ElvisControl.FINALLY -> {
                val tried = host.bodyExpression as KtTryExpression
                assertTrue(tried.finallyBlock != null)
                assertSame(branch, tried.tryBlock.statements.single())
            }
            else -> assertSame(branch, host.bodyExpression)
        }
    }

    private fun types(control: ElvisControl, call: KtCallExpression, branch: KtBinaryExpression) =
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
            val source = call.expressionType as KaClassType
            val target = branch.expressionType as KaClassType
            assertEquals(control.sourceClass, source.classId.asSingleFqName().asString())
            assertEquals(control.nullable, source.isMarkedNullable)
            assertEquals(
                if (control == ElvisControl.UNIT) "kotlin.Unit" else "kotlin.String",
                target.classId.asSingleFqName().asString(),
            )
            assertEquals(control == ElvisControl.RIGHT_NULL, target.isMarkedNullable)
            println(
                "ELVIS_CONTROL_K2 case=${control.name} source=${range(call)} whole=${range(branch)} " +
                    "sourceClass=${source.classId.asSingleFqName()} sourceNullable=${source.isMarkedNullable} " +
                    "targetClass=${target.classId.asSingleFqName()} targetNullable=${target.isMarkedNullable}"
            )
        }

    private fun range(element: com.intellij.psi.PsiElement) =
        ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset).nativeRefined()

    companion object {
        const val SOURCE = "src/main/kotlin/fixture/controls/ElvisResultControls.kt"
        private const val OWNER = "ElvisResultControls"
        private const val DISCARDED_STATEMENTS = 2
    }
}

internal enum class ElvisControl(
    val method: String,
    val callee: String = "leftValue",
    val sourceClass: String = "kotlin.String",
    val nullable: Boolean = true,
) {
    LEFT("normal"),
    RIGHT(method = "normal", callee = "rightValue", nullable = false),
    LEFT_NULL(method = "leftNull", callee = "nullValue", sourceClass = "kotlin.Nothing"),
    RIGHT_NULL(method = "rightNull", callee = "nullValue", sourceClass = "kotlin.Nothing"),
    BOTTOM(method = "rightBottom", callee = "never", sourceClass = "kotlin.Nothing", nullable = false),
    UNIT(method = "unitResult", callee = "nullableUnit", sourceClass = "kotlin.Unit"),
    DISCARDED("discarded"),
    FINALLY("guarded"),
    FOREIGN("foreignOperand"),
}

internal data class ElvisControlCase(
    val source: ValueSite,
    val branch: ExactDeclarationTextRange,
    val right: ExactDeclarationTextRange,
)

private data class ElvisControlSyntax(
    val call: ExactDeclarationTextRange,
    val branch: ExactDeclarationTextRange,
    val right: ExactDeclarationTextRange,
)
