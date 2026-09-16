package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** PSI-only lexical policy proof. K1 supplies the parser; this does not claim K2 resolution. */
class KotlinCallOwnershipTest {
    @Test
    fun `local initializers share callable ownership and lambdas retain deferred boundaries`(@TempDir home: Path) =
        withParser(home) { factory ->
            val file = factory.createFile("fun outer(): String { val value = target(); return value }")
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val outer = file.declarations.filterIsInstance<KtNamedFunction>().single()
            val call = PsiTreeUtil.findChildOfType(outer, KtCallExpression::class.java)!!
            val observation = RecordedObservation()
            assertSame(outer, (call.nearestDeclaration(observation) as ContainingDeclaration.Found).declaration)
            val callback = factory.createFile("fun callback() = { target() }")
            assertNull(PsiTreeUtil.findChildOfType(callback, PsiErrorElement::class.java))
            val callbackCall = PsiTreeUtil.findChildOfType(callback, KtCallExpression::class.java)!!
            assertFalse(callbackCall.nearestDeclaration(observation) is ContainingDeclaration.Found)
            assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_CALL_OWNERS_FOUND to 1,
                    IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE to 1,
                ),
                observation.counts,
            )
            assertEquals(setOf(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED), observation.reasons)
            val deferred = callbackCall.nearestDeclaration() as ContainingDeclaration.Deferred
            assertSame(
                callback.declarations.single(),
                (deferred.enclosingDeclaration() as ContainingDeclaration.Found).declaration,
            )
            assertNestedOwner(factory)
        }

    @Test
    fun `target stage observations retain every finite decision`() {
        val observation = RecordedObservation()
        for (decision in IntellijK2TargetConfirmation.entries) {
            assertSame(decision, decision.observedBy(observation))
        }
        assertEquals(
            mapOf(
                IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS to 1,
                IntellijReadCounter.RELATION_K2_DIFFERENT_TARGETS to 1,
                IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS to 1,
            ),
            observation.counts,
        )
    }

    @Test
    fun `owner admission preserves named proof and finite unavailable observations`(@TempDir home: Path) =
        withParser(home) { factory ->
            val declaration = factory.createFile("fun named() = 1").declarations.single() as KtNamedFunction
            val lexical = ContainingDeclaration.Found(declaration)
            val observation = RecordedObservation()
            val admitted = refineCallOwnership(lexical, observation)
            assertSame(lexical, (admitted as io.github.amichne.kast.kernel.Refinement.Refined).value)
            assertEquals(
                io.github.amichne.kast.kernel.Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY),
                refineCallOwnership(ContainingDeclaration.Unsupported, observation),
            )
            assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_CALL_OWNERS_FOUND to 1,
                    IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE to 1,
                ),
                observation.counts,
            )
            assertEquals(setOf(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED), observation.reasons)
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationLimitation.UNSUPPORTED_ITEM,
                CallOwnershipFailure.UNSUPPORTED_BOUNDARY.limitation,
            )
            assertEquals(
                io.github.amichne.kast.relation.contract.RelationLimitation.UNRESOLVED_TARGET,
                CallOwnershipFailure.UNRESOLVED_ARGUMENT_MAPPING.limitation,
            )
        }

    private fun assertNestedOwner(factory: KtPsiFactory) {
        val file = factory.createFile("fun outer() {\n fun nested() = target()\n nested()\n }")
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
        val nested = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).single { it.name == "nested" }
        val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
        assertSame(
            nested,
            (calls.single { it.calleeExpression?.text == "target" }.nearestDeclaration() as ContainingDeclaration.Found)
                .declaration,
        )
        assertSame(
            file.declarations.single(),
            (calls.single { it.calleeExpression?.text == "nested" }.nearestDeclaration() as ContainingDeclaration.Found)
                .declaration,
        )
    }

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
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private class RecordedObservation : IntellijReadObservation {
        val counts = mutableMapOf<IntellijReadCounter, Int>()
        val reasons = mutableSetOf<IntellijReadTermination>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = counts.getOrDefault(counter, 0) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            reasons += reason
        }
    }
}
