@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractHoldsInEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractInvocationKind
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractReturnsResultOfEffectDeclaration
import org.jetbrains.kotlin.analysis.api.contracts.description.booleans.KaContractBooleanValueParameterExpression
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.ReturnValueCheckerMode
import org.jetbrains.kotlin.idea.base.util.invalidateProjectRoots
import org.jetbrains.kotlin.idea.facet.getOrCreateConfiguredFacet
import org.jetbrains.kotlin.psi.KtCallExpression
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

    fun testReturnsResultOfBindsTheSelectedFormalAndActualCallback() {
        returnsFixture()
        inspectReturnsFixture { functions ->
            val expected =
                listOf(
                    "preserve" to KaContractInvocationKind.EXACTLY_ONCE,
                    "choose" to KaContractInvocationKind.AT_MOST_ONCE,
                )
            for ((name, kind) in expected) {
                val symbol = functions.single { it.name == name }.symbol as KaNamedFunctionSymbol
                val parameter = symbol.valueParameters.single { it.name.asString() == "block" }
                val effects = symbol.contractEffects
                assertEquals(2, effects.size)
                val returned = effects.filterIsInstance<KaContractReturnsResultOfEffectDeclaration>().single()
                val invoked = effects.filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>().single()
                assertEquals(parameter, returned.valueParameterReference.symbol)
                assertEquals(parameter, invoked.valueParameterReference.symbol)
                assertEquals(kind, invoked.invocationKind)
                val caller = functions.single { it.name == if (name == "preserve") "keptPayload" else "callbackChoice" }
                val call =
                    PsiTreeUtil.collectElementsOfType(caller, KtCallExpression::class.java).single {
                        it.calleeExpression?.text == name
                    }
                val resolved = checkNotNull(call.resolveCall())
                assertEquals(symbol, resolved.signature.symbol)
                val argument = checkNotNull(call.lambdaArguments.single().getLambdaExpression())
                assertEquals(parameter, checkNotNull(resolved.valueArgumentMapping[argument]).symbol)
            }
            val ignored = functions.single { it.name == "discard" }.symbol as KaNamedFunctionSymbol
            assertEquals(1, ignored.contractEffects.size)
            assertTrue(ignored.contractEffects.single() is KaContractCallsInPlaceContractEffectDeclaration)
        }
    }

    fun testZeroAndRepeatedBodiesKeepTheirDeclaredInvocationKinds() {
        returnsFixture()
        inspectReturnsFixture { functions ->
            val expected =
                listOf(
                    "dormant" to KaContractInvocationKind.AT_MOST_ONCE,
                    "twice" to KaContractInvocationKind.AT_LEAST_ONCE,
                )
            for ((name, kind) in expected) {
                val function = functions.single { it.name == name }
                val symbol = function.symbol as KaNamedFunctionSymbol
                val parameter = symbol.valueParameters.single()
                val effect = symbol.contractEffects.single() as KaContractCallsInPlaceContractEffectDeclaration
                assertEquals(parameter, effect.valueParameterReference.symbol)
                assertEquals(kind, effect.invocationKind)
                val invocations =
                    PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java).count { call ->
                        val reference = call.calleeExpression as? KtNameReferenceExpression
                        reference?.resolveCall()?.signature?.symbol == parameter
                    }
                assertEquals(if (name == "dormant") 0 else 2, invocations)
            }
        }
    }

    fun testReturnsResultOfSourceBodyPreservesTheCallbackPayload() {
        val fixture = returnsFixture()
        val owner = returnsDeclaration("keptPayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue(
            "Callback result did not reach its consumer",
            reached(steps, fixture.markedExpression(owner, "output")),
        )
    }

    fun testIgnoredCallbackResultDoesNotAcquireTheEnclosingResult() {
        val fixture = returnsFixture()
        val owner = returnsDeclaration("ignoredPayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue(
            "Ignored callback never reached its source owner",
            steps.any { it.source.enclosing.name.value == "discard" },
        )
        assertFalse("Ignored result acquired a consumer", reached(steps, fixture.markedExpression(owner, "output")))
    }

    fun testConditionalCallbackResultDoesNotBecomeTheFallbackValue() {
        val fixture = returnsFixture()
        val owner = returnsDeclaration("callbackChoice")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue("Selected callback result was lost", reached(steps, fixture.markedExpression(owner, "output")))
        assertFalse(
            "Callback result became the fallback",
            reached(steps, fixture.markedExpression(returnsDeclaration("choose"), "fallback")),
        )
    }

    fun testReturnsResultOfDoesNotEraseTheIndependentFallbackResult() {
        val fixture = returnsFixture()
        val owner = returnsDeclaration("fallbackChoice")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue(
            "Fallback branch was not reached",
            reached(steps, fixture.markedExpression(returnsDeclaration("choose"), "fallback")),
        )
        assertTrue("Fallback result was lost", reached(steps, fixture.markedExpression(owner, "output")))
    }

    fun testReturnsResultOfRejectsDisabledFeature() {
        assertRejectedSetting(ContractResultMode.FEATURE_DISABLED)
    }

    fun testReturnsResultOfRejectsDisabledChecker() {
        assertRejectedSetting(ContractResultMode.CHECKER_DISABLED)
    }

    fun testReturnsResultOfSelectsOneOfTwoInvokedCallbackParameters() {
        val fixture = returnsFixture()
        inspectReturnsFixture { functions ->
            val symbol = functions.single { it.name == "selectedOnly" }.symbol as KaNamedFunctionSymbol
            val effect = symbol.contractEffects.filterIsInstance<KaContractReturnsResultOfEffectDeclaration>().single()
            assertEquals(symbol.valueParameters[0], effect.valueParameterReference.symbol)
            assertFalse(symbol.valueParameters[1] == effect.valueParameterReference.symbol)
        }
        val owner = returnsDeclaration("selectedCallbackPayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue(reached(steps, fixture.markedExpression(returnsDeclaration("selectedOnly"), "selected-result")))
        assertTrue(reached(steps, fixture.markedExpression(owner, "output")))
    }

    fun testReturnsResultOfCannotImportAnotherInvokedCallbackPayload() {
        val fixture = returnsFixture()
        val owner = returnsDeclaration("ignoredCallbackPayload")
        val steps = walk(fixture, seed(fixture, owner))
        val ignored = fixture.markedExpression(returnsDeclaration("selectedOnly"), "ignored-call")
        assertTrue(
            "Ignored callback result never reached its exact invocation",
            reached(steps, ignored, ValueRole.ExpressionResult),
        )
        assertFalse(reached(steps, fixture.markedExpression(returnsDeclaration("selectedOnly"), "selected-result")))
        assertFalse(reached(steps, fixture.markedExpression(owner, "output")))
    }

    private fun assertRejectedSetting(mode: ContractResultMode) {
        // First prove valid source and library admission; only then make the named configuration transition.
        returnsFixture()
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                module.configureReturnsFacet(mode)
                project.invalidateProjectRoots(RootsChangeRescanningInfo.NO_RESCAN_NEEDED)
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        inspectReturnsFixture { functions ->
            val settings = (useSiteModule as KaSourceModule).languageVersionSettings
            assertEquals(LanguageVersion.KOTLIN_2_4, settings.languageVersion)
            assertEquals(ApiVersion.KOTLIN_2_4, settings.apiVersion)
            assertEquals(mode.feature, settings.getFeatureSupport(LanguageFeature.AllowReturnsResultOfContract))
            assertEquals(mode.checker, settings.getFlag(AnalysisFlags.returnValueCheckerMode))
            val expected =
                listOf("preserve", "choose", "selectedOnly").map { name ->
                    val function = functions.single { it.name == name }
                    val symbol = function.symbol as KaNamedFunctionSymbol
                    assertTrue(symbol.contractEffects.none { it is KaContractReturnsResultOfEffectDeclaration })
                    val call =
                        PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java).single {
                            it.calleeExpression?.text == "returnsResultOf"
                        }
                    "ERROR_IN_CONTRACT_DESCRIPTION" to call.textRange
                }
            val errors =
                functions
                    .first()
                    .containingKtFile
                    .collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
            assertEquals(expected.toSet(), errors.map { it.factoryName to it.psi.textRange }.toSet())
            assertEquals(expected.size, errors.size)
            assertTrue(
                errors.all {
                    "-Xallow-returns-result-of" in it.defaultMessage && "-Xreturn-value-checker" in it.defaultMessage
                }
            )
        }
    }

    private fun returnsFixture(): NativePacketValueFixture {
        val fixture = packetFixture(listOf(RETURNS_CONTROLS), configureCompiler = ::configureReturnsCompiler)
        inspectReturnsFixture {
            val settings = (useSiteModule as KaSourceModule).languageVersionSettings
            assertEquals(LanguageVersion.KOTLIN_2_4, settings.languageVersion)
            assertEquals(ApiVersion.KOTLIN_2_4, settings.apiVersion)
            assertEquals(
                LanguageFeature.State.ENABLED,
                settings.getFeatureSupport(LanguageFeature.AllowReturnsResultOfContract),
            )
            assertEquals(ReturnValueCheckerMode.CHECKER, settings.getFlag(AnalysisFlags.returnValueCheckerMode))
        }
        return fixture
    }

    private fun configureReturnsCompiler() {
        val stdlib = Path.of(checkNotNull(javaClass.getResource("/contract-libraries/kotlin-stdlib.jar")).toURI())
        check(Files.isRegularFile(stdlib)) { "Provisioned contract standard library is missing: $stdlib" }
        ModuleRootModificationUtil.updateModel(module) { model ->
            val library = checkNotNull(model.moduleLibraryTable.getLibraryByName("packet-stdlib"))
            val roots = library.modifiableModel
            roots.getUrls(OrderRootType.CLASSES).forEach { roots.removeRoot(it, OrderRootType.CLASSES) }
            roots.addRoot("jar://${stdlib.toAbsolutePath()}!/", OrderRootType.CLASSES)
            roots.commit()
        }
        WriteCommandAction.runWriteCommandAction(project) {
            module.configureReturnsFacet(ContractResultMode.ENABLED)
        }
    }

    private fun inspectReturnsFixture(action: KaSession.(List<KtNamedFunction>) -> Unit) {
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(COPIED_RETURNS_CONTROLS))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                action(file.declarations.filterIsInstance<KtNamedFunction>())
            }
        }
    }

    private fun seed(fixture: NativePacketValueFixture, owner: PacketDeclaration): ValueSite = runBlocking {
        val request = fixture.selection(owner, "produced", returnsDeclaration("produced"))
        val read =
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        assertTrue("Contract result producer was not admitted: $read", read is ValueProducerSeedRead.Seeded)
        (read as ValueProducerSeedRead.Seeded).seed.site
    }

    private fun walk(fixture: NativePacketValueFixture, origin: ValueSite): List<ValueFlowStep> = runBlocking {
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_BYTES).nativeRefined())
        val pending = ArrayDeque<ValueSite>()
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        pending.addLast(origin)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Contract result fixture exceeded its finite site bound", visited.size <= MAX_SITES)
            val read =
                IntellijValueFlowCompilerAdapter()
                    .read(
                        project = project,
                        current = fixture.lease,
                        request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
            assertTrue("Contract result read failed: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertTrue("Contract result retained obligations: ${step.obligations}", step.obligations.isEmpty())
            assertTrue(step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        assertTrue(steps.isNotEmpty())
        steps
    }

    private fun reached(
        steps: List<ValueFlowStep>,
        expression: PacketExpression,
        role: ValueRole = ValueRole.LocalRead,
    ): Boolean = steps.any { step ->
        step.source.enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
            step.source.range == expression.anchor &&
            step.source.role == role
    }

    private fun returnsDeclaration(name: String) = PacketDeclaration(RETURNS_CONTROLS, "", name)

    private companion object {
        const val INPUT_REFERENCE_COUNT = 4
        const val CONTROLS = "src/main/kotlin/fixture/controls/ContractScopeControls.kt"
        const val COPIED_CONTROLS = "src/fixture/controls/ContractScopeControls.kt"
        const val RETURNS_CONTROLS = "src/main/kotlin/fixture/controls/ReturnsResultControls.kt"
        const val COPIED_RETURNS_CONTROLS = "src/fixture/controls/ReturnsResultControls.kt"
        const val MAX_SITES = 128
        const val MAX_BYTES = 16_000_000L
    }
}

private enum class ContractResultMode(val feature: LanguageFeature.State, val checker: ReturnValueCheckerMode) {
    ENABLED(LanguageFeature.State.ENABLED, ReturnValueCheckerMode.CHECKER),
    FEATURE_DISABLED(LanguageFeature.State.DISABLED, ReturnValueCheckerMode.CHECKER),
    CHECKER_DISABLED(LanguageFeature.State.ENABLED, ReturnValueCheckerMode.DISABLED),
}

private fun Module.configureReturnsFacet(mode: ContractResultMode) {
    val models = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
    try {
        getOrCreateConfiguredFacet(models, useProjectSettings = false, commitModel = true) {
            configuration.settings.compilerArguments =
                K2JVMCompilerArguments().apply {
                    languageVersion = "2.4"
                    apiVersion = "2.4"
                    autoAdvanceLanguageVersion = false
                    autoAdvanceApiVersion = false
                    allowReturnsResultOf = mode.feature == LanguageFeature.State.ENABLED
                    returnValueChecker = mode.checker.state
                }
            val arguments = checkNotNull(configuration.settings.mergedCompilerArguments)
            assertEquals("2.4", arguments.languageVersion)
            assertEquals("2.4", arguments.apiVersion)
            assertEquals(mode.feature == LanguageFeature.State.ENABLED, arguments.allowReturnsResultOf)
            assertEquals(mode.checker.state, arguments.returnValueChecker)
            println(
                "CONTRACT_FIXTURE_ARGUMENTS language=${arguments.languageVersion} api=${arguments.apiVersion} " +
                    "returnsResultOf=${arguments.allowReturnsResultOf} returnValueChecker=${arguments.returnValueChecker}"
            )
        }
    } finally {
        models.dispose()
    }
}
