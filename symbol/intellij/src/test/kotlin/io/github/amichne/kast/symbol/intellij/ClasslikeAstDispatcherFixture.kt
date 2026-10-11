package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.junit.jupiter.api.Assertions.assertSame

/** Strict supplies at existing index/clock/environment boundaries; production owns all admission and output. */
internal object ClasslikeAstDispatcherFixture {
    data class Controls(
        val work: Long = 100,
        val records: Int = 100,
        val bytes: Long = 64_000,
        val indexed: Boolean = true,
        val namedFamilies: List<IntellijNamedDeclarationIndex> = listOf(IntellijNamedDeclarationIndex.CLASS),
        val namedComplete: Boolean = true,
        val expectedNamedCalls: Int = 1,
        val expectedFileCalls: Int = 1,
        val environment: () -> IntellijDiscoveryEnvironmentState = { IntellijDiscoveryEnvironmentState.READY },
        val cancellation: () -> Unit = {},
        val clock: IntellijReadNanoClock = IntellijReadNanoClock { 0L },
        val beforeFiles: () -> Unit = {},
        val onCounter: (IntellijReadCounter) -> Unit = {},
    )

    data class Result(val outcome: SymbolDiscoveryOutcome, val observation: RecordingDiscoveryObservation)

    fun discover(
        project: Project,
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        pattern: String,
        controls: Controls = Controls(),
    ): Result =
        ApplicationManager.getApplication().runReadAction<Result> {
            val request = request(source, pattern, controls)
            val recording = RecordingDiscoveryObservation { controls.clock.now() }
            val observed =
                object : IntellijReadObservation by recording {
                    override fun count(
                        counter: IntellijReadCounter,
                        contributor: IntellijReadContributor,
                        amount: Int,
                    ) {
                        recording.count(counter, contributor, amount)
                        controls.onCounter(counter)
                    }
                }
            val supply = Supply(source, pattern, controls)
            val outcome =
                IntellijNativeDiscoveryQuery(
                        environmentState = controls.environment,
                        cancellationCheck = controls.cancellation,
                        clock = controls.clock,
                        observation = observed,
                    )
                    .discoverNative(project, source.scope, request, indexes = supply)
                    .outcome()
            supply.assertConsumed()
            Result(outcome, recording)
        }

    private fun request(
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        pattern: String,
        controls: Controls,
    ): SymbolDiscoveryRequest {
        val initial = source.request(controls.records, controls.work, controls.bytes, null)
        return SymbolDiscoveryRequest(
            initial.scope,
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.CLASS,
                (SymbolDiscoveryPattern.parse(pattern) as Refinement.Refined).value,
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            initial.budget,
            initial.constraints,
        )
    }

    private class Supply(
        private val source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        private val pattern: String,
        private val controls: Controls,
    ) : IntellijDeclarationIndexes {
        private var named = 0
        private var files = 0

        override fun named(
            index: IntellijNamedDeclarationIndex,
            name: String,
            scope: GlobalSearchScope,
            accept: (NavigationItem) -> Boolean,
        ): Boolean {
            expect(index == controls.namedFamilies.getOrNull(named++) && name == pattern)
            expect(scope === source.scope.nativeScope)
            if (controls.indexed) {
                val declaration =
                    source.files.first().declarations.filterIsInstance<KtNamedDeclaration>().first {
                        it.name == pattern
                    }
                expect(accept(declaration))
            }
            return controls.namedComplete
        }

        override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
            expect(named == controls.namedFamilies.size && files++ == 0 && scope === source.scope.nativeScope)
            controls.beforeFiles()
            return source.files.all { accept(it.virtualFile) }
        }

        override fun packageFiles(
            packageName: String,
            scope: GlobalSearchScope,
            accept: (VirtualFile) -> Boolean,
        ): Boolean = throw AssertionError("unexpected package index")

        fun assertConsumed() {
            expect(named == controls.expectedNamedCalls && files == controls.expectedFileCalls)
        }
    }

    fun <T> withUnsupportedParser(action: () -> T): T {
        val definition = object : KotlinParserDefinition() {}
        val original = requireNotNull(LanguageParserDefinitions.INSTANCE.forLanguage(KotlinLanguage.INSTANCE))
        LanguageParserDefinitions.INSTANCE.removeExplicitExtension(KotlinLanguage.INSTANCE, original)
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(KotlinLanguage.INSTANCE, definition)
        try {
            assertSame(definition, LanguageParserDefinitions.INSTANCE.forLanguage(KotlinLanguage.INSTANCE))
            return action()
        } finally {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(KotlinLanguage.INSTANCE, definition)
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(KotlinLanguage.INSTANCE, original)
        }
    }

    fun denseSources() =
        linkedMapOf(
            "Target.kt" to "package fixture.reference.dense\nclass Target\n",
            "Uses.kt" to
                "package fixture.reference.denseusage\nimport fixture.reference.dense.Target\nclass Uses {\n" +
                    (0 until 1000).joinToString("\n") {
                        "    val reference${it.toString().padStart(4, '0')}: Target? = null"
                    } +
                    "\n}\n",
        )

    private fun expect(condition: Boolean) {
        if (!condition) throw AssertionError("unexpected or unconsumed native index effect")
    }
}
