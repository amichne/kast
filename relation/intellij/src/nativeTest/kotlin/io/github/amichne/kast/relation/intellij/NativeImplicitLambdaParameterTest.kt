@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Compiler symbol identity only; matching a captured symbol does not by itself admit nested execution. */
class NativeImplicitLambdaParameterTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "implicit-lambda-parameter-jdk"

    fun testImplicitParameterUsesItsCompilerOwnedLiteralWithoutRequiringParameterPsi() = checkFixture { file ->
        val literal = literals(file, "implicit").single()
        assertTrue(literal.valueParameters.isEmpty())
        IntellijReadObservation.None.observedAnalyze(literal) {
            val selected = checkNotNull(nativeImplicitLambdaParameter(literal, zero))
            assertTrue(selected.isImplicitLambdaParameter)
            assertEquals(literal.symbol, selected.containingDeclaration)
            assertNull(nativeImplicitLambdaParameter(literal, ValueArgumentPosition.parse(1).nativeRefined()))
        }
        assertEquals(LocalBindingReferenceConfirmation.EXACT_BINDING, confirm(reads(literal).single(), literal))
    }

    fun testNestedImplicitParameterCannotSubstituteTheOuterInput() = checkFixture { file ->
        val (outer, inner) = literals(file, "shadowed")
        val innerRead = reads(inner).single()
        val outerRead = reads(outer).single { it !== innerRead }
        assertEquals(LocalBindingReferenceConfirmation.OTHER_BINDING, confirm(innerRead, outer))
        assertEquals(LocalBindingReferenceConfirmation.EXACT_BINDING, confirm(innerRead, inner))
        assertEquals(LocalBindingReferenceConfirmation.EXACT_BINDING, confirm(outerRead, outer))
        assertEquals(LocalBindingReferenceConfirmation.OTHER_BINDING, confirm(outerRead, inner))
    }

    fun testAnOuterImplicitReadRetainsItsIdentityInsideAnExplicitInnerLambda() = checkFixture { file ->
        val (outer, inner) = literals(file, "captured")
        assertEquals("ignored", inner.valueParameters.single().name)
        assertEquals(LocalBindingReferenceConfirmation.EXACT_BINDING, confirm(reads(inner).single(), outer))
    }

    fun testAnAuthoredParameterNamedItIsNotAnImplicitFormal() = checkFixture { file ->
        val literal = literals(file, "explicit").single()
        assertEquals("it", literal.valueParameters.single().name)
        IntellijReadObservation.None.observedAnalyze(literal) {
            assertNull(nativeImplicitLambdaParameter(literal, zero))
        }
        assertEquals(LocalBindingReferenceConfirmation.UNRESOLVED, confirm(reads(literal).single(), literal))
    }

    fun testEqualLambdaTextKeepsDistinctCompilerOwners() = checkFixture { file ->
        val (first, second) = literals(file, "repeated")
        assertEquals(first.text, second.text)
        assertEquals(LocalBindingReferenceConfirmation.EXACT_BINDING, confirm(reads(first).single(), first))
        assertEquals(LocalBindingReferenceConfirmation.OTHER_BINDING, confirm(reads(second).single(), first))
    }

    private fun confirm(expression: KtNameReferenceExpression, literal: KtFunctionLiteral) =
        confirmImplicitLambdaParameterReference(
            expression = expression,
            literal = literal,
            position = zero,
            observation = IntellijReadObservation.None,
        )

    private fun reads(literal: KtFunctionLiteral): List<KtNameReferenceExpression> =
        PsiTreeUtil.collectElementsOfType(literal, KtNameReferenceExpression::class.java).filter {
            it.getReferencedName() == "it"
        }

    private fun literals(file: KtFile, function: String): List<KtFunctionLiteral> {
        val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }
        return PsiTreeUtil.collectElementsOfType(owner, KtFunctionLiteral::class.java).sortedBy {
            it.textRange.startOffset
        }
    }

    private fun checkFixture(check: (KtFile) -> Unit) {
        val fixture = packetFixture(listOf(CONTROLS))
        val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) { virtual.setBinaryContent(SOURCE.toByteArray()) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        ReadAction.run<RuntimeException> { check(PsiManager.getInstance(project).findFile(virtual) as KtFile) }
    }

    private companion object {
        val zero = ValueArgumentPosition.parse(0).nativeRefined()
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
        val SOURCE =
            """
            package fixture.implicitinput
            class Payload
            fun <T> invoke(value: T, action: (T) -> T): T = action(value)
            fun implicit(value: Payload): Payload = invoke(value) { it }
            fun shadowed(value: Payload): Payload = invoke(value) { invoke(value) { it }; it }
            fun captured(value: Payload): Payload = invoke(value) { invoke(value) { ignored -> it } }
            fun explicit(value: Payload): Payload = invoke(value) { it -> it }
            fun repeated(value: Payload) { invoke(value) { it }; invoke(value) { it } }
            """
                .trimIndent()
    }
}
