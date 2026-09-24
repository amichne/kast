package io.github.amichne.kast.source.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.matching
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.range
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.snapshot
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** PSI traversal proof; compiler target resolution requires a live K2 fixture. */
class AnonymousObjectSourceScopeTest {
    @Test
    fun `anonymous object retains supertype call and nested member call`(@TempDir home: Path) =
        withParser(home) { factory ->
            val text =
                "fun outer() { val loader = object : ClassLoader() { " +
                    "override fun loadClass(name: String): Class<*> = target() } }"
            val file = factory.createFile(text)
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val page = projectCalls(file, text)
            val calls = page.entities.filterIsInstance<SourceEntity.Call>()
            assertEquals(
                listOf("ClassLoader", "target"),
                calls.map {
                    it.calleeSelector.range.let { r -> text.substring(r.startInclusive.value, r.endExclusive.value) }
                },
            )
            assertEquals(
                listOf("ClassLoader", "target"),
                calls.map { (it.selector.name as SourceEntityName.Present).value },
            )
            assertTrue(page.limitations.isEmpty())
        }

    private fun projectCalls(file: KtFile, text: String): IntellijSourceEntityPage.Complete {
        val snapshot = snapshot(text)
        val region = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.FILE)
        val execution =
            IntellijSourceExecution(
                ResourceBudget(
                    ResultLimit.parse(20).refined(),
                    WorkUnitLimit.parse(1_000).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                )
            )
        val projected =
            IntellijSourceEntityAttempt.collect(
                execution,
                matching(Containment.DESCENDANTS, EntityFilter.Calls),
                IntellijSourceEntityCursor(0),
                SourceEntityLimit.parse(20).refined(),
            ) { attempt ->
                NativeSourceEntityEnumerator(
                        LiveSourceDocument(snapshot, text, file),
                        NativeSourceRegion(file.textRange, SourceRegionKind.FILE, file),
                        region,
                        includeDeclarations = false,
                        includeParameters = false,
                        includeCalls = true,
                        includeReferences = false,
                        containment = Containment.DESCENDANTS,
                        attempt = attempt,
                        visibility = { _, _ -> null },
                        target = {
                            SourceEntityTarget.Unresolved(
                                io.github.amichne.kast.source.contract.CompilerUnresolvedReason.UNSUPPORTED_TARGET
                            )
                        },
                    )
                    .enumerate()
            }
        return (projected as? NativeSourceEntityProjection.Projected)?.page as? IntellijSourceEntityPage.Complete
            ?: error("Unexpected source projection: $projected")
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun withParser(home: Path, assertion: (KtPsiFactory) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            assertion(KtPsiFactory(environment.project))
        } finally {
            val application = ApplicationManager.getApplication()
            if (application == null) Disposer.dispose(disposable)
            else application.runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
