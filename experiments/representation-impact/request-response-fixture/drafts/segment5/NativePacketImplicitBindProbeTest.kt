@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** This capture precedes receiver-slot admission. It preserves the original component1 body and call. */
class NativePacketImplicitBindProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-implicit-bind-probe-jdk"

    fun testOriginalImplicitBindKeepsItsActualCompilerReceiverSlots() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val component = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "component1"))
        val expected = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "bind"))
        ReadAction.run<RuntimeException> {
            val function = componentFunction()
            val call = PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java).single()
            assertEquals(exact(COMPONENT_START, COMPONENT_END), range(function))
            assertEquals(exact(BIND_START, BIND_END), range(call))
            IntellijReadObservation.None.observedAnalyze(call) {
                capture(call = call, function = function, component = component, expected = expected)
            }
        }
        println("IMPLICIT_BIND_RESULT capture=COMPLETE adapter_slot_admission=NOT_RUN full_C27=NOT_RUN")
    }

    private fun KaSession.capture(
        call: KtCallExpression,
        function: KtNamedFunction,
        component: SymbolSelector,
        expected: SymbolSelector,
    ) {
        val resolved = checkNotNull(call.resolveCall()) { "The original bind() did not resolve" }
        val projected = resolved.signature.symbol.compilerProjection(this)
        assertTrue("The actual bind target did not project", projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(expected.compilerIdentity, declaration.identity)
        assertEquals(expected.signature, declaration.signature)
        assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
        val owner = function.symbol
        val ownerProjection = owner.compilerProjection(this)
        assertTrue(ownerProjection is IntellijCompilerProjectionResult.Projected)
        val ownerIdentity = (ownerProjection as IntellijCompilerProjectionResult.Projected).projection.identity
        assertEquals(component.compilerIdentity, ownerIdentity)
        recordSignature(declaration.signature as CanonicalCompilerSignature.Function)
        println(
            "IMPLICIT_BIND_ACTUAL call=${range(call)} host=${range(function)} " +
                "diagnosticReceiver=${resolved.signature.receiverType} " +
                "diagnosticResult=${resolved.signature.returnType} " +
                "argumentCount=${resolved.valueArgumentMapping.size}"
        )
        recordReceiver(ValueReceiverKind.EXTENSION, resolved.extensionReceiver, owner)
        recordReceiver(ValueReceiverKind.DISPATCH, resolved.dispatchReceiver, owner)
    }

    private fun componentFunction(): KtNamedFunction {
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == "component1" }
    }

    private fun recordSignature(signature: CanonicalCompilerSignature.Function) {
        println(
            "IMPLICIT_BIND_DECLARED callable=${signature.qualifiedIdentity.value} receiver=${signature.receiver} " +
                "parameters=${signature.valueParameters.map { it.value }} " +
                "contexts=${signature.contextReceivers.map { it.value }} " +
                "typeParameters=${signature.typeParameterCount.value}"
        )
    }

    private fun KaSession.recordReceiver(
        slot: ValueReceiverKind,
        receiver: KaReceiverValue?,
        owner: KaNamedFunctionSymbol,
    ) {
        when (receiver) {
            null -> println("IMPLICIT_BIND_RECEIVER slot=$slot kind=NONE")
            is KaExplicitReceiverValue ->
                println(
                    "IMPLICIT_BIND_RECEIVER slot=$slot kind=EXPLICIT range=${range(receiver.expression)} " +
                        "diagnosticType=${receiver.type}"
                )
            is KaImplicitReceiverValue -> recordImplicit(slot, receiver, owner)
            is KaSmartCastedReceiverValue -> {
                println("IMPLICIT_BIND_RECEIVER slot=$slot kind=SMART_CAST diagnosticType=${receiver.type}")
                when (val original = receiver.original) {
                    is KaExplicitReceiverValue ->
                        println("IMPLICIT_BIND_ORIGINAL slot=$slot kind=EXPLICIT range=${range(original.expression)}")
                    is KaImplicitReceiverValue -> recordImplicit(slot, original, owner)
                    else -> println("IMPLICIT_BIND_ORIGINAL slot=$slot kind=NESTED_SMART_CAST")
                }
            }
        }
    }

    private fun KaSession.recordImplicit(
        slot: ValueReceiverKind,
        receiver: KaImplicitReceiverValue,
        owner: KaNamedFunctionSymbol,
    ) {
        val symbol = receiver.symbol
        val receivingCallable = (symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol
        println(
            "IMPLICIT_BIND_RECEIVER slot=$slot kind=IMPLICIT subtype=${symbol.javaClass.simpleName.take(MAX_NAME)} " +
                "psiRange=${symbol.psi?.let(::range)} origin=${symbol.origin} " +
                "isComponentExtension=${symbol == owner.receiverParameter} " +
                "isComponentDispatch=${symbol == owner.containingDeclaration} " +
                "receiverOwnerIsComponent=${receivingCallable == owner} diagnosticType=${receiver.type}"
        )
        when (val projected = symbol.compilerProjection(this)) {
            is IntellijCompilerProjectionResult.Projected ->
                println(
                    "IMPLICIT_BIND_RECEIVER_SYMBOL slot=$slot status=PROJECTED " +
                        "identity=${projected.projection.identity.value}"
                )
            is IntellijCompilerProjectionResult.LocalRejected ->
                println("IMPLICIT_BIND_RECEIVER_SYMBOL slot=$slot status=LOCAL_REJECTED cause=${projected.failure}")
            IntellijCompilerProjectionResult.Unsupported ->
                println("IMPLICIT_BIND_RECEIVER_SYMBOL slot=$slot status=UNSUPPORTED")
        }
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val COMPONENT_START = 1088
        const val COMPONENT_END = 1143
        const val BIND_START = 1137
        const val BIND_END = 1143
        const val MAX_NAME = 256
    }
}
