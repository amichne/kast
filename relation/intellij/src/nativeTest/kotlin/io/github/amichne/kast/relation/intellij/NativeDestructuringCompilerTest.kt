@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Characterizes the pinned compiler's implicit operator binding before any flow projection is trusted. */
class NativeDestructuringCompilerTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "destructuring-compiler-jdk"

    fun testAuthoredComponentsResolveToTheirOwnFunctionsAndDistinctEntryAnchors() {
        components("authoredComponents") { declaration ->
            IntellijReadObservation.None.observedAnalyze(declaration) {
                val calls = declaration.entries.map { checkNotNull(it.resolveCall()) }
                val symbols = calls.map { it.signature.symbol as KaNamedFunctionSymbol }
                assertEquals(listOf("component1", "component2"), symbols.map { it.name.asString() })
                assertTrue(symbols.all { it.origin == KaSymbolOrigin.SOURCE })
                assertTrue(symbols.all { it.psi is KtNamedFunction })
                assertTrue(calls.all { it.dispatchReceiver != null && it.extensionReceiver == null })
                assertFalse(range(declaration.entries[0]) == range(declaration.entries[1]))
            }
        }
    }

    fun testGeneratedComponentsHaveCompilerGeneratedOrigin() {
        components("generatedComponents") { declaration ->
            IntellijReadObservation.None.observedAnalyze(declaration) {
                val symbols = declaration.entries.map { checkNotNull(it.resolveCall()).signature.symbol }
                assertTrue(symbols.all { it is KaNamedFunctionSymbol })
                assertEquals(
                    listOf("component1", "component2"),
                    symbols.map { (it as KaNamedFunctionSymbol).name.asString() },
                )
                assertTrue(symbols.all { it.origin == KaSymbolOrigin.SOURCE_MEMBER_GENERATED })
                assertTrue(symbols.all { it.containingDeclaration == symbols.first().containingDeclaration })
            }
        }
    }

    private fun components(function: String, inspect: (KtDestructuringDeclaration) -> Unit) {
        packetFixture(listOf(CONTROLS))
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }
            inspect(checkNotNull(PsiTreeUtil.findChildOfType(owner, KtDestructuringDeclaration::class.java)))
        }
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
    }
}
