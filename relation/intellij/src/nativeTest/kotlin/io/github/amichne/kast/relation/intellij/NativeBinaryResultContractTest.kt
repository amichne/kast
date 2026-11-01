@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractInvocationKind
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractReturnsResultOfEffectDeclaration
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.facet.getOrCreateConfiguredFacet
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** Real metadata only: dependency source is neither copied into the project nor attached to its library. */
class NativeBinaryResultContractTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "binary-result-contract-jdk"

    override fun runInDispatchThread() = false

    fun testBinaryResultEffectRetainsSelectedParameterAndAlternativePath() {
        val fixture = prepareBinaryFixture()
        ReadAction.run<RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                val settings = (useSiteModule as KaSourceModule).languageVersionSettings
                assertEquals(LanguageVersion.KOTLIN_2_4, settings.languageVersion)
                assertEquals(
                    LanguageFeature.State.ENABLED,
                    settings.getFeatureSupport(LanguageFeature.AllowReturnsResultOfContract),
                )
                val errors =
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).filter {
                        it.severity == KaSeverity.ERROR
                    }
                assertTrue("Binary caller failed compilation: ${errors.map { it.defaultMessage }}", errors.isEmpty())
                val expected = mapOf("selectedOnly" to listOf(0), "choose" to listOf(2), "discard" to emptyList())
                val calls =
                    PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).filter {
                        it.calleeExpression?.text in expected
                    }
                assertEquals(expected.size, calls.size)
                for (call in calls) {
                    assertBinaryCall(call, expected.getValue(checkNotNull(call.calleeExpression).text))
                }
            }
        }
    }

    private fun KaSession.assertBinaryCall(call: KtCallExpression, returnedPositions: List<Int>) {
        val resolved = checkNotNull(call.resolveCall())
        val symbol = resolved.signature.symbol as KaNamedFunctionSymbol
        val name = symbol.name.asString()
        assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
        assertEquals("effects.result.$name", symbol.callableId?.asSingleFqName()?.asString())
        assertTrue(checkNotNull(symbol.psi?.containingFile?.virtualFile).url.endsWith("ResultEffectsKt.class"))
        val effects = symbol.contractEffects
        val returned = effects.filterIsInstance<KaContractReturnsResultOfEffectDeclaration>()
        val invoked = effects.filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>()
        println("BINARY_RESULT_EFFECT name=$name effects=${effects.map { it::class.simpleName }}")
        assertEquals(if (name == "selectedOnly") 2 else 1, invoked.size)
        val kind =
            if (name == "choose") KaContractInvocationKind.AT_MOST_ONCE else KaContractInvocationKind.EXACTLY_ONCE
        assertTrue(invoked.all { it.invocationKind == kind })
        assertEquals("Binary result effect missing for $name", returnedPositions.size, returned.size)
        assertEquals(
            returnedPositions.map { symbol.valueParameters[it] },
            returned.map { it.valueParameterReference.symbol },
        )
        for (position in returnedPositions) {
            assertEquals(
                1,
                resolved.valueArgumentMapping.values.count { it.symbol == symbol.valueParameters[position] },
            )
        }
    }

    private fun prepareBinaryFixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            val dependency =
                Path.of(checkNotNull(javaClass.getResource("/contract-libraries/result-effects.jar")).toURI())
            val stdlib = Path.of(checkNotNull(javaClass.getResource("/contract-libraries/kotlin-stdlib.jar")).toURI())
            assertTrue(Files.isRegularFile(dependency))
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("binary-result-effects")
                val roots = library.modifiableModel
                for (path in listOf(dependency, stdlib)) roots.addRoot("jar://$path!/", OrderRootType.CLASSES)
                assertTrue(roots.getUrls(OrderRootType.SOURCES).isEmpty())
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                val models = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
                try {
                    module.getOrCreateConfiguredFacet(models, useProjectSettings = false, commitModel = true) {
                        configuration.settings.compilerArguments =
                            K2JVMCompilerArguments().apply {
                                languageVersion = "2.4"
                                apiVersion = "2.4"
                                allowReturnsResultOf = true
                                returnValueChecker = "check"
                            }
                    }
                } finally {
                    models.dispose()
                }
                fixture.callerFile.setBinaryContent(CALLER.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private companion object {
        val CALLER =
            """
            package consumer
            import effects.result.selectedOnly
            import effects.result.choose
            import effects.result.discard
            fun selected() = selectedOnly(selected = { proof.target() }, ignored = { 2 })
            fun alternative(take: Boolean) = choose(take, 3) { proof.target() }
            fun ignored() = discard(4) { proof.target() }
            """
                .trimIndent()
    }
}
