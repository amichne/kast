@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativePrimitiveCompilerTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "primitive-compiler-jdk"

    fun testSubtractionSelectsBothIntInputs() {
        val path = "fixture/controls/PrimitiveControls.kt"
        packetFixture(listOf("src/main/kotlin/$path"))
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/$path"))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            val functions = file.declarations.filterIsInstance<KtNamedFunction>()
            val names =
                mapOf(
                    "subtract" to "kotlin.Int.minus",
                    "explicitSubtract" to "kotlin.Int.minus",
                    "add" to "kotlin.Int.plus",
                    "multiply" to "kotlin.Int.times",
                    "minimum" to "kotlin.math.min",
                    "maximum" to "kotlin.math.max",
                )
            assertEquals(names.keys, functions.map { it.name }.toSet())
            functions.forEach { function ->
                val expression = checkNotNull(function.bodyExpression)
                IntellijReadObservation.None.observedAnalyze(expression) {
                    val call = resolvedReviewedOrdinaryCall(expression) as KaFunctionCall<*>
                    val symbol = call.signature.symbol as KaNamedFunctionSymbol
                    val expected = names.getValue(checkNotNull(function.name))
                    assertEquals(expected, symbol.callableId?.asSingleFqName()?.asString())
                    assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
                    assertTrue(symbol.returnType.isIntType && !symbol.returnType.isMarkedNullable)
                    assertTrue(
                        symbol.valueParameters.all { it.returnType.isIntType && !it.returnType.isMarkedNullable }
                    )
                    val bindings =
                        call.valueArgumentMapping.entries.associate { (input, parameter) ->
                            symbol.valueParameters.indexOf(parameter.symbol) to input.text
                        }
                    val fileUrl = checkNotNull(symbol.psi?.containingFile?.virtualFile?.url)
                    if (call.dispatchReceiver != null) {
                        assertTrue(fileUrl, fileUrl.endsWith("!/kotlin/kotlin.kotlin_builtins"))
                        assertEquals("left", (call.dispatchReceiver as KaExplicitReceiverValue).expression.text)
                        assertEquals(mapOf(0 to "right"), bindings)
                    } else {
                        assertTrue(fileUrl, fileUrl.endsWith("!/kotlin/math/MathKt.class"))
                        assertEquals(mapOf(0 to "left", 1 to "right"), bindings)
                    }
                }
            }
        }
    }
}
