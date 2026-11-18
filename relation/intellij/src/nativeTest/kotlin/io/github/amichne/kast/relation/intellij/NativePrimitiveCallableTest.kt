@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativePrimitiveCallableTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "primitive-callable-jdk"

    override fun runInDispatchThread() = false

    fun testIntMinusBindsBothOperandsInBothSyntaxes() {
        val fixture = preparePrimitiveFixture()
        ReadAction.compute<Unit, RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                for (function in file.declarations.filterIsInstance<KtNamedFunction>()) {
                    val body = function.bodyExpression
                    val call =
                        when (body) {
                            is KtBinaryExpression -> body.resolveCall()
                            is KtDotQualifiedExpression -> (body.selectorExpression as KtCallExpression).resolveCall()
                            else -> error("Missing operation in ${function.name}")
                        }
                            as KaFunctionCall<*>
                    val symbol = call.signature.symbol as KaNamedFunctionSymbol
                    val module = symbol.containingModule
                    println(
                        "PRIMITIVE_CALLABLE id=${symbol.callableId} origin=${symbol.origin} " +
                            "module=${module.javaClass.simpleName} psi=${symbol.psi?.containingFile?.virtualFile?.url}"
                    )
                    assertEquals("kotlin.Int.minus", symbol.callableId?.asSingleFqName()?.asString())
                    assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
                    assertTrue(module is KaLibraryModule)
                    assertTrue(
                        symbol.psi?.containingFile?.virtualFile?.url?.endsWith("!/kotlin/kotlin.kotlin_builtins") ==
                            true
                    )
                    assertEquals((builtinTypes.int as KaClassType).symbol, symbol.containingDeclaration)
                    assertTrue(call.signature.returnType.isIntType)
                    assertTrue(call.signature.valueParameters.single().returnType.isIntType)
                    val receiver = call.dispatchReceiver as KaExplicitReceiverValue
                    assertTrue(receiver.type.isIntType)
                    assertEquals("width", receiver.expression.text)
                    assertEquals("margin", call.valueArgumentMapping.keys.single().text)
                    assertEquals(symbol.valueParameters.single(), call.valueArgumentMapping.values.single().symbol)
                }
            }
        }
    }

    fun testKotlinMathSelectsTheJvmStdlibDeclaration() {
        val fixture = preparePrimitiveFixture(MATH)
        ReadAction.compute<Unit, RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                for (function in file.declarations.filterIsInstance<KtNamedFunction>()) {
                    val body = function.bodyExpression as KtDotQualifiedExpression
                    val call = checkNotNull((body.selectorExpression as KtCallExpression).resolveCall())
                    val symbol = call.signature.symbol as KaNamedFunctionSymbol
                    val url = symbol.psi?.containingFile?.virtualFile?.url
                    println("MATH_CALLABLE id=${symbol.callableId} origin=${symbol.origin} psi=$url")
                    assertTrue(url?.endsWith("!/kotlin/math/MathKt.class") == true)
                    assertEquals("kotlin.math.${function.name}", symbol.callableId?.asSingleFqName()?.asString())
                    assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
                    assertTrue(call.signature.returnType.isIntType)
                    assertEquals(2, call.signature.valueParameters.size)
                    assertTrue(call.signature.valueParameters.all { it.returnType.isIntType })
                    assertEquals(listOf("width", "margin"), call.valueArgumentMapping.keys.map { it.text })
                    assertEquals(symbol.valueParameters, call.valueArgumentMapping.values.map { it.symbol })
                }
            }
        }
    }

    private fun preparePrimitiveFixture(source: String = SUBTRACTION): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture(callerSource = source)
            ModuleRootModificationUtil.updateModel(module) { roots ->
                val library = roots.moduleLibraryTable.createLibrary("primitive-stdlib")
                val model = library.modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                model.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                model.commit()
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private companion object {
        val SUBTRACTION =
            """
            fun infixCall(width: Int, margin: Int) = width - margin
            fun dotCall(width: Int, margin: Int) = width.minus(margin)
            """
                .trimIndent()
        val MATH =
            """
            fun min(width: Int, margin: Int) = kotlin.math.min(width, margin)
            fun max(width: Int, margin: Int) = kotlin.math.max(width, margin)
            """
                .trimIndent()
    }
}
