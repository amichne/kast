@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/** A fixed actual source prefix restores the original Let payload. It does not replace the original S2 query. */
internal class NativePacketElvisOriginalSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val source = NativePacketLetCallbackSource(project, base, fixture)

    fun capture() =
        ReadAction.run<RuntimeException> {
            val file = checkNotNull(base.findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val host =
                PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single {
                    it.name == "requireNotNull"
                }
            val elvis = host.bodyExpression as KtBinaryExpression
            assertEquals(KtTokens.ELVIS, elvis.operationToken)
            val left = elvis.left as KtSafeQualifiedExpression
            assertEquals(exact(LET_START, LET_END), range(left))
            assertEquals(exact(LET_START, ELVIS_END), range(elvis))
            assertEquals(exact(FALLBACK_START, ELVIS_END), range(checkNotNull(elvis.right)))
            assertSame(elvis, left.parent)
            IntellijReadObservation.None.observedAnalyze(left) {
                val source = left.expressionType as KaClassType
                val target = elvis.expressionType as KaClassType
                assertEquals("fixture.outcome.Outcome", source.classId.asSingleFqName().asString())
                assertEquals("fixture.outcome.Outcome", target.classId.asSingleFqName().asString())
                assertTrue(source.isMarkedNullable)
                assertFalse(target.isMarkedNullable)
                assertEquals(
                    host.symbol,
                    checkNotNull(PsiTreeUtil.getParentOfType(elvis, KtNamedFunction::class.java)).symbol,
                )
                println(
                    "ELVIS_ORIGINAL_K2 left=1622:1655 whole=1622:1681 fallback=1659:1681 " +
                        "sourceNullable=true targetNullable=false"
                )
            }
        }

    fun payloadResult(): ValueSite {
        val facts = source.original()
        val entry = read(facts.receiver).applications.single { it.target.role is ValueRole.Formal }.target
        val it = next(entry, ValueTransferKind.LOCAL_READ)
        assertEquals(facts.references.single(), it.range)
        val argument = next(it, ValueTransferKind.ARGUMENT)
        assertEquals(facts.result, (argument.role as ValueRole.Argument).call.range)
        val callbackResult = constructedSuccess(argument)
        assertEquals(facts.result, callbackResult.range)
        val normal = next(callbackResult, ValueTransferKind.RETURN)
        val result = read(normal).applications.single { it.target.range == facts.binding.invocation.range }.target
        assertEquals(exact(LET_START, LET_END), result.range)
        assertEquals(ValueRole.ExpressionResult, result.role)
        assertEquals(facts.receiver.enclosing, result.enclosing)
        assertEquals(facts.receiver.executionOwner, result.executionOwner)
        assertEquals(facts.receiver.callStack, result.callStack)
        assertTrue(result.predicates.entries.containsAll(facts.receiver.predicates.entries))
        val fields = NativePacketCompositeSuccessSource(project, base, fixture)
        val expected =
            ValueAccessPath.fromSegments(
                    listOf(
                        ValuePathSegment.Field(
                            fields.field(NativePacketCompositeSuccessSource.OUTCOME, "Success", "value")
                        )
                    )
                )
                .nativeRefined()
        assertEquals(expected, result.valuePath)
        println(
            "ELVIS_ORIGINAL_SETUP kind=ISOLATED_ORIGINAL_THIS source_factory_and_model_prefix=ACTUAL " +
                "path=Success.value full_S2=NOT_ASSERTED"
        )
        return result
    }

    private fun constructedSuccess(argument: ValueSite): ValueSite {
        val formal = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY)
        val value = next(formal, ValueTransferKind.LOCAL_READ)
        val constructor = next(value, ValueTransferKind.ARGUMENT)
        val stored = next(constructor, ValueTransferKind.CONSTRUCTOR_FIELD)
        val returned = next(stored, ValueTransferKind.RETURN)
        return next(returned, ValueTransferKind.SOURCE_CALL_RETURN)
    }

    fun read(site: ValueSite): ValueFlowStep = source.read(site)

    private fun next(site: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = read(site)
        val selected = step.transfers.filter { it.kind == kind }
        assertEquals(
            "The original payload prefix needs $kind at ${site.range}; causes=${step.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun range(element: com.intellij.psi.PsiElement) =
        exact(element.textRange.startOffset, element.textRange.endOffset)

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val LET_START = 1622
        const val LET_END = 1655
        const val FALLBACK_START = 1659
        const val ELVIS_END = 1681
    }
}
