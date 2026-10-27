@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Compiler argument identity only; these cases do not claim supplier execution or payload transport. */
class NativeCallbackArgumentBindingTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "callback-argument-binding-jdk"

    fun testImplicitInvokeMapsBothActualArgumentsWithoutRequiringBuiltinPsi() = checkFixture { file ->
        val call = call(file, "implicit", "action")
        call.valueArguments.forEachIndexed { index, argument ->
            val expression = checkNotNull(argument.getArgumentExpression())
            val binding = nativeCallbackArgumentBinding(call, expression, IntellijReadObservation.None).nativeRefined()
            assertTrue(binding is NativeCallbackArgumentBinding.Mapped)
            binding as NativeCallbackArgumentBinding.Mapped
            assertEquals(index, binding.position.value)
            assertSame(call.calleeExpression, binding.receiver)
            assertEquals(sourceValueRange(call), binding.invocationRange)
            assertEquals(sourceValueRange(expression), binding.argumentRange)
        }
    }

    fun testExplicitInvokeRetainsItsReceiverAndWholeInvocationRange() = checkFixture { file ->
        val call = call(file, "explicit", "invoke")
        val expression = checkNotNull(call.valueArguments.single().getArgumentExpression())
        val binding =
            nativeCallbackArgumentBinding(call, expression, IntellijReadObservation.None).nativeRefined()
                as NativeCallbackArgumentBinding.Mapped
        assertEquals("action", binding.receiver.text)
        assertEquals(sourceValueRange(call.parent), binding.invocationRange)
        assertEquals(0, binding.position.value)
    }

    fun testGenericCallbackPreservesCompilerArgumentIdentity() = checkFixture { file ->
        assertGenericBinding(call(file, "generic", "action"))
    }

    fun testGenericMemberPayloadPreservesCompilerArgumentIdentity() = checkFixture { file ->
        val owner = file.declarations.filterIsInstance<KtClass>().single { it.name == "Box" }
        val function = owner.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "map" }
        val call = PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java).single()
        assertEquals("transform", call.calleeExpression?.text)
        assertGenericBinding(call)
    }

    private fun assertGenericBinding(call: KtCallExpression) {
        val expression = checkNotNull(call.valueArguments.single().getArgumentExpression())
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            val callable = resolved.signature.symbol as KaNamedFunctionSymbol
            assertTrue(confirmsBuiltinFunctionInvoke(callable))
            assertEquals(setOf(expression), resolved.valueArgumentMapping.keys)
            assertEquals(callable.valueParameters.single(), resolved.valueArgumentMapping.getValue(expression).symbol)
        }
        val binding = nativeCallbackArgumentBinding(call, expression, IntellijReadObservation.None).nativeRefined()
        assertTrue(binding is NativeCallbackArgumentBinding.Mapped)
        binding as NativeCallbackArgumentBinding.Mapped
        assertEquals(0, binding.position.value)
        assertSame(call.calleeExpression, binding.receiver)
        assertEquals(sourceValueRange(call), binding.invocationRange)
        assertEquals(sourceValueRange(expression), binding.argumentRange)
    }

    fun testRepeatedInvocationOccurrencesStayDistinct() = checkFixture { file ->
        val calls = calls(file, "repeated", "action")
        val bindings = calls.map { call ->
            nativeCallbackArgumentBinding(
                    call,
                    checkNotNull(call.valueArguments.single().getArgumentExpression()),
                    IntellijReadObservation.None,
                )
                .nativeRefined() as NativeCallbackArgumentBinding.Mapped
        }
        assertEquals(2, bindings.size)
        assertEquals(2, bindings.map { it.invocationRange }.toSet().size)
        assertEquals(2, bindings.map { it.argumentRange }.toSet().size)
    }

    fun testAuthoredOperatorInvokeIsNotAFunctionValueInvocation() = checkFixture { file ->
        val call = call(file, "authored", "action")
        val result =
            nativeCallbackArgumentBinding(
                call,
                checkNotNull(call.valueArguments.single().getArgumentExpression()),
                IntellijReadObservation.None,
            )
        assertEquals(Refinement.Refined(NativeCallbackArgumentBinding.NotCallback), result)
    }

    fun testAnotherInvocationArgumentCannotBeReusedByPositionOrText() = checkFixture { file ->
        val calls = calls(file, "repeated", "action")
        val result =
            nativeCallbackArgumentBinding(
                calls.first(),
                checkNotNull(calls.last().valueArguments.single().getArgumentExpression()),
                IntellijReadObservation.None,
            )
        assertEquals(Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE), result)
    }

    fun testSafeInvocationRetainsUnsupportedConditionalExecution() = checkFixture { file ->
        assertUnsupportedCall(file, "safe", "invoke")
    }

    fun testQualifiedPropertyDoesNotSubstituteItsContainerForTheCallback() = checkFixture { file ->
        assertUnsupportedCall(file, "qualified", "action")
    }

    private fun assertUnsupportedCall(file: KtFile, function: String, callee: String) {
        val call = call(file, function, callee)
        val result =
            nativeCallbackArgumentBinding(
                call,
                checkNotNull(call.valueArguments.single().getArgumentExpression()),
                IntellijReadObservation.None,
            )
        assertEquals(Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION), result)
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

    private fun call(file: KtFile, function: String, callee: String) = calls(file, function, callee).single()

    private fun calls(file: KtFile, function: String, callee: String): List<KtCallExpression> {
        val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }
        return PsiTreeUtil.collectElementsOfType(owner, KtCallExpression::class.java)
            .filter { it.calleeExpression?.text == callee }
            .sortedBy { it.textRange.startOffset }
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
        val SOURCE =
            """
            package fixture.callbackinput
            class Payload
            class Authored { operator fun invoke(value: Payload): Payload = value }
            class Holder(val action: (Payload) -> Payload)
            class Box<S>(val value: S) { fun <T> map(transform: (S) -> T): T = transform(value) }
            fun <S, T> generic(value: S, action: (S) -> T): T = action(value)
            fun implicit(first: Payload, second: Payload, action: (Payload, Payload) -> Payload) = action(second, first)
            fun explicit(value: Payload, action: (Payload) -> Payload) = action.invoke(value)
            fun repeated(value: Payload, action: (Payload) -> Payload) { action(value); action(value) }
            fun authored(value: Payload, action: Authored) = action(value)
            fun safe(value: Payload, action: ((Payload) -> Payload)?) = action?.invoke(value)
            fun qualified(value: Payload, holder: Holder) = holder.action(value)
            """
                .trimIndent()
    }
}
