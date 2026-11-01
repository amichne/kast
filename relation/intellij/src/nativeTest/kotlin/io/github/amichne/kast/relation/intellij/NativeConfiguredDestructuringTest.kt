@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.getOrCreateConfiguredFacet
import org.jetbrains.kotlin.psi.KtFile

/** Module settings select the implicit operation; every path begins at its original constructor argument. */
class NativeConfiguredDestructuringTest : NativeDestructuringFixtureTest() {
    override val fixtureSdkName = "configured-destructuring-jdk"

    fun testFullNameBasedFormSelectsTheExactSecondProperty() {
        val fixture = configuredFixture("complete")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredFull",
            selected = "second-payload",
            excluded = "first-payload",
        )
    }

    fun testFullNameBasedFormPreservesSwappedSameTypedFields() {
        val fixture = configuredFixture("complete")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredFullSwapped",
            selected = "second-payload",
            excluded = "first-payload",
        )
    }

    fun testSquareFormRemainsPositionalInCompleteMode() {
        val fixture = configuredFixture("complete")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredSquare",
            selected = "first-payload",
            excluded = "second-payload",
        )
    }

    fun testSquareFormRemainsPositionalInOnlySyntaxMode() {
        val fixture = configuredFixture("only-syntax")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredSquare",
            selected = "first-payload",
            excluded = "second-payload",
        )
    }

    fun testParenthesesSelectNamesInCompleteMode() {
        val fixture = configuredFixture("complete")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredParentheses",
            selected = "second-payload",
            excluded = "first-payload",
        )
    }

    fun testIdenticalParenthesesSelectPositionsInOnlySyntaxMode() {
        val fixture = configuredFixture("only-syntax")
        assertStoredSelection(
            fixture = fixture,
            function = "configuredParentheses",
            selected = "first-payload",
            excluded = "second-payload",
        )
    }

    fun testSquareUnderscoreDoesNotInvokeThrowingComponent() {
        val fixture = configuredFixture("complete")
        val source =
            input(
                fixture = fixture,
                function = "configuredSquareSkipsGetter",
                literal = "first-payload",
                constructor = "ConfiguredGetterBox",
            )
        val result = authoredReturn(fixture, source)
        assertUse(fixture, "configuredSquareSkipsGetter", result)
    }

    fun testCustomGetterUsesItsBodyAndExactMatchedReturn() {
        val fixture = configuredFixture("complete")
        assertGetterSelection(
            fixture = fixture,
            function = "configuredCustomGetter",
            selected = "second-payload",
            excluded = "first-payload",
        )
    }

    fun testCustomGetterKeepsSwappedSameTypedPayloadsDistinct() {
        val fixture = configuredFixture("complete")
        assertGetterSelection(
            fixture = fixture,
            function = "configuredCustomGetterSwapped",
            selected = "first-payload",
            excluded = "second-payload",
        )
    }

    fun testNamedIgnoredNormalGetterExecutesWithoutCreatingABinding() {
        val fixture = configuredFixture("complete")
        val source =
            input(
                fixture = fixture,
                function = "configuredIgnoredNormalGetter",
                literal = "second-payload",
                constructor = "ConfiguredGetterBox",
            )
        val returned = authoredReturn(fixture, source)
        assertTrue(returned.callStack.frames.isEmpty())
        assertExhausted(step(fixture, returned))
    }

    fun testEarlierThrowingGetterPreventsTheSelectedConsumer() {
        assertGetterOrderRejected("configuredEarlierThrowingGetter")
    }

    fun testLaterThrowingGetterPreventsTheSelectedConsumer() {
        assertGetterOrderRejected("configuredLaterThrowingGetter")
    }

    fun testNamedUnderscoreCannotSkipThrowingGetter() {
        val fixture = configuredFixture("complete")
        val source =
            input(
                fixture = fixture,
                function = "configuredDiscardedGetter",
                literal = "first-payload",
                constructor = "ConfiguredGetterBox",
            )
        val read = step(fixture, source)
        assertTrue(
            "Discarded named getter must prevent a successful binding: ${read.transfers}",
            read.transfers.isEmpty(),
        )
        assertUnsupported(read, ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun assertGetterSelection(
        fixture: NativePacketValueFixture,
        function: String,
        selected: String,
        excluded: String,
    ) {
        val source =
            input(fixture = fixture, function = function, literal = selected, constructor = "ConfiguredGetterBox")
        assertUse(fixture, function, authoredReturn(fixture, source))
        val unrelated =
            input(fixture = fixture, function = function, literal = excluded, constructor = "ConfiguredGetterBox")
        val receiver = next(fixture, unrelated, ValueTransferKind.RECEIVER)
        val formal = next(fixture, receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        assertExhausted(step(fixture, formal))
    }

    private fun assertGetterOrderRejected(function: String) {
        val fixture = configuredFixture("complete")
        val source =
            input(
                fixture = fixture,
                function = function,
                literal = "second-payload",
                constructor = "ConfiguredGetterBox",
            )
        val read = step(fixture, source)
        assertTrue("Unproven sibling must prevent every consumer transfer", read.transfers.isEmpty())
        assertUnsupported(read, ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun configuredFixture(mode: String): NativePacketValueFixture {
        val fixture =
            packetFixture(
                paths = listOf(CONFIGURED),
                configureCompiler = { configureDestructuringCompiler(mode) },
            )
        ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ConfiguredDestructuringControls.kt")
                )
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                val sourceModule = useSiteModule as KaSourceModule
                assertCompilerSettings(mode, sourceModule.languageVersionSettings)
            }
        }
        return fixture
    }

    private fun configureDestructuringCompiler(mode: String) {
        val models = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
        try {
            module.getOrCreateConfiguredFacet(
                modelsProvider = models,
                useProjectSettings = false,
                commitModel = true,
            ) {
                configuration.settings.compilerArguments =
                    K2JVMCompilerArguments().apply {
                        languageVersion = "2.4"
                        apiVersion = "2.3"
                        autoAdvanceLanguageVersion = false
                        autoAdvanceApiVersion = false
                        nameBasedDestructuring = mode
                    }
            }
        } finally {
            models.dispose()
        }
        val facet = checkNotNull(KotlinFacet.get(module))
        assertFalse(facet.configuration.settings.useProjectSettings)
        val arguments = checkNotNull(facet.configuration.settings.mergedCompilerArguments)
        assertEquals("2.4", arguments.languageVersion)
        assertEquals("2.3", arguments.apiVersion)
        assertEquals(mode, arguments.nameBasedDestructuring)
        // Check the effective IDE module settings before packetFixture collects any source diagnostics.
        assertCompilerSettings(mode, module.languageVersionSettings)
    }

    private fun assertCompilerSettings(mode: String, settings: LanguageVersionSettings) {
        println(
            "CONFIGURED_DESTRUCTURING mode=$mode language=${settings.languageVersion} " +
                "api=${settings.apiVersion} " +
                "nameBased=${settings.getFeatureSupport(LanguageFeature.NameBasedDestructuring)} " +
                "shortForm=${settings.getFeatureSupport(LanguageFeature.EnableNameBasedDestructuringShortForm)}"
        )
        assertEquals(LanguageVersion.KOTLIN_2_4, settings.languageVersion)
        assertEquals(ApiVersion.KOTLIN_2_3, settings.apiVersion)
        assertTrue(settings.supportsFeature(LanguageFeature.NameBasedDestructuring))
        assertEquals(
            mode == "complete",
            settings.supportsFeature(LanguageFeature.EnableNameBasedDestructuringShortForm),
        )
    }

    private fun assertStoredSelection(
        fixture: NativePacketValueFixture,
        function: String,
        selected: String,
        excluded: String,
    ) {
        val source = input(fixture, function, selected)
        val result = next(fixture, source, ValueTransferKind.FIELD_READ)
        assertTrue(result.valuePath.segments.isEmpty())
        assertEquals(source.callStack, result.callStack)
        assertUse(fixture, function, result)
        assertExhausted(step(fixture, input(fixture, function, excluded)))
    }

    private fun assertUse(fixture: NativePacketValueFixture, function: String, result: ValueSite) {
        val binding = next(fixture, result, ValueTransferKind.LOCAL_BINDING)
        val use = next(fixture, binding, ValueTransferKind.LOCAL_READ)
        assertEquals(fixture.markedExpression(configuredOwner(function), "use").anchor, use.range)
        assertEquals(result.executionOwner, use.executionOwner)
        assertEquals(ValueRole.LocalRead, use.role)
        assertTrue(use.valuePath.segments.isEmpty())
        assertTrue(use.callStack.frames.isEmpty())
    }

    private fun input(
        fixture: NativePacketValueFixture,
        function: String,
        literal: String,
        constructor: String = "ConfiguredBox",
    ): ValueSite {
        val expression = fixture.argumentExpression(configuredOwner(function), constructor, "\"$literal\"")
        val argument = next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
        return next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
    }

    private fun configuredOwner(function: String) = PacketDeclaration(CONFIGURED, "", function)

    private companion object {
        const val CONFIGURED = "src/main/kotlin/fixture/controls/ConfiguredDestructuringControls.kt"
    }
}
