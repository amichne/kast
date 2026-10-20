@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Actual K2 receiver slots establish the original map guard expectation independently of native value transfers. */
class NativePacketMapReceiverProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-map-receiver-probe-jdk"

    fun testOriginalMapDispatchReceiverIsTheExactExecuteResult() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val map = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Outcome", "map"))
        val execute = fixture.callableDeclaration(PacketDeclaration(WIRE, "Call", "execute"))
        val host = fixture.callableDeclaration(PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"))
        ReadAction.run<RuntimeException> {
            val file =
                checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/client/OffersClient.kt"))
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val selected =
                PsiTreeUtil.collectElementsOfType(psi, KtCallExpression::class.java).single { call ->
                    call.calleeExpression?.text == "map"
                }
            val qualified = selected.parent as KtDotQualifiedExpression
            val receiver = qualified.receiverExpression as KtDotQualifiedExpression
            val producer = receiver.selectorExpression as KtCallExpression
            assertEquals(exact(MAP_START, MAP_END), range(qualified))
            assertEquals(exact(EXECUTE_START, EXECUTE_END), range(receiver))
            assertEquals("execute", producer.calleeExpression?.text)
            assertEquals(
                host.range,
                range(checkNotNull(PsiTreeUtil.getParentOfType(selected, KtNamedFunction::class.java))),
            )
            IntellijReadObservation.None.observedAnalyze(selected) {
                proveMap(call = selected, receiver = receiver, expected = map)
                proveCallable(producer, execute)
            }
        }
        println("MAP_RECEIVER_PROBE status=ACTUAL_K2_DISPATCH execute=769:954 map=769:989 value_flow=NOT_RUN")
    }

    private fun KaSession.proveMap(
        call: KtCallExpression,
        receiver: KtDotQualifiedExpression,
        expected: SymbolSelector,
    ) {
        val resolved = checkNotNull(call.resolveCall())
        assertNull(resolved.extensionReceiver)
        assertTrue(resolved.dispatchReceiver is KaExplicitReceiverValue)
        val dispatch = resolved.dispatchReceiver as KaExplicitReceiverValue
        assertEquals(receiver.originalElement, dispatch.expression.originalElement)
        assertEquals(exact(EXECUTE_START, EXECUTE_END), range(dispatch.expression))
        proveCallable(call, expected)
        println("MAP_RECEIVER_SLOT extension=NONE dispatch=EXPLICIT dispatchRange=769:954 origin=SOURCE")
    }

    private fun KaSession.proveCallable(call: KtCallExpression, expected: SymbolSelector) {
        val resolved = checkNotNull(call.resolveCall())
        val symbol = resolved.signature.symbol
        assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
        val projected = symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(expected.compilerIdentity, declaration.identity)
        assertEquals(expected.signature, declaration.signature)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val WIRE = NativePacketCompositeSuccessSource.WIRE
        const val EXECUTE_START = 769
        const val EXECUTE_END = 954
        const val MAP_START = 769
        const val MAP_END = 989
    }
}
