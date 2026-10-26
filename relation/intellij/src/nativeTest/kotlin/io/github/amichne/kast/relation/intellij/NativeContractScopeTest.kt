@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractHoldsInEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.booleans.KaContractBooleanValueParameterExpression
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativeContractScopeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "contract-scope-jdk"

    fun testHoldsInRefinesOnlyTheSelectedCallback() {
        packetFixture(listOf(CONTROLS))
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(COPIED_CONTROLS))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                val functions = file.declarations.filterIsInstance<KtNamedFunction>()
                val declaration = functions.single { it.name == "choose" }.symbol as KaNamedFunctionSymbol
                val fact = declaration.contractEffects.filterIsInstance<KaContractHoldsInEffectDeclaration>().single()
                assertEquals(declaration.valueParameters[1], fact.valueParameterReference.symbol)
                val condition = fact.condition as KaContractBooleanValueParameterExpression
                assertEquals(declaration.valueParameters[0], condition.parameterSymbol.symbol)

                val scoped = functions.single { it.name == "scoped" }
                val references =
                    PsiTreeUtil.collectElementsOfType(scoped, KtNameReferenceExpression::class.java)
                        .filter { it.getReferencedName() == "input" }
                        .sortedBy { it.textRange.startOffset }
                assertEquals(INPUT_REFERENCE_COUNT, references.size)
                val refined = checkNotNull(references[1].smartCastInfo)
                assertTrue(refined.isStable)
                assertEquals(
                    "fixture.controls.contractscope.Selected",
                    (refined.smartCastType as KaClassType).classId.asSingleFqName().asString(),
                )
                for (reference in listOf(references.first(), references[2], references.last())) {
                    assertNull("A sibling callback or continuation borrowed holdsIn", reference.smartCastInfo)
                }
            }
        }
    }

    private companion object {
        const val INPUT_REFERENCE_COUNT = 4
        const val CONTROLS = "src/main/kotlin/fixture/controls/ContractScopeControls.kt"
        const val COPIED_CONTROLS = "src/fixture/controls/ContractScopeControls.kt"
    }
}
