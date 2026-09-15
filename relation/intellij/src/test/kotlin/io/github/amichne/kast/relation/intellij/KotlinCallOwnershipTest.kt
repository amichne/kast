package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.K1Deprecation::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
)
/** PSI-only lexical policy proof. K1 environment supplies the parser; this does not claim K2 resolution. */
class KotlinCallOwnershipTest {
    @Test
    fun `local initializers share callable ownership while nested bodies keep their boundary`(
        @org.junit.jupiter.api.io.TempDir home: java.nio.file.Path
    ) {
        val originalProperties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        java.nio.file.Files.createDirectories(home.resolve("bin"))
        java.nio.file.Files.writeString(home.resolve("bin/idea.properties"), "")
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
            val file =
                KtPsiFactory(environment.project)
                    .createFile(
                        "fun outer(): String { val value = target(); return value }; fun target(): String = \"value\""
                    )
            val outer = file.declarations.filterIsInstance<KtNamedFunction>().first()
            val call = PsiTreeUtil.findChildOfType(outer, KtCallExpression::class.java)!!
            val observedCounts = mutableMapOf<IntellijReadCounter, Int>()
            val observedReasons = mutableSetOf<IntellijReadTermination>()
            val observation =
                object : IntellijReadObservation {
                    override fun count(
                        counter: IntellijReadCounter,
                        contributor: IntellijReadContributor,
                        amount: Int,
                    ) {
                        observedCounts[counter] = observedCounts.getOrDefault(counter, 0) + amount
                    }

                    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
                        observedReasons += reason
                    }
                }
            assertSame(outer, (call.nearestDeclaration(observation) as ContainingDeclaration.Found).declaration)
            val callback = KtPsiFactory(environment.project).createFile("fun callback() = { target() }")
            val callbackCall = PsiTreeUtil.findChildOfType(callback, KtCallExpression::class.java)!!
            org.junit.jupiter.api.Assertions.assertFalse(
                callbackCall.nearestDeclaration(observation) is ContainingDeclaration.Found,
                "A lambda body must not become an unconditional outer invocation",
            )
            org.junit.jupiter.api.Assertions.assertEquals(
                mapOf(
                    IntellijReadCounter.RELATION_CALL_OWNERS_FOUND to 1,
                    IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE to 1,
                ),
                observedCounts,
            )
            org.junit.jupiter.api.Assertions.assertEquals(
                setOf(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED),
                observedReasons,
            )
            val deferred = callbackCall.nearestDeclaration() as ContainingDeclaration.Deferred
            assertSame(
                callback.declarations.single(),
                (deferred.enclosingDeclaration() as ContainingDeclaration.Found).declaration,
            )
            val nestedFile =
                KtPsiFactory(environment.project).createFile("fun outer() {\n fun nested() = target()\n nested()\n }")
            for (parsed in listOf(file, callback, nestedFile)) {
                org.junit.jupiter.api.Assertions.assertNull(
                    PsiTreeUtil.findChildOfType(parsed, com.intellij.psi.PsiErrorElement::class.java)
                )
            }
            val nestedOwner =
                PsiTreeUtil.findChildrenOfType(nestedFile, KtNamedFunction::class.java).single { it.name == "nested" }
            val nestedCalls = PsiTreeUtil.findChildrenOfType(nestedFile, KtCallExpression::class.java)
            assertSame(
                nestedOwner,
                (nestedCalls.single { it.calleeExpression?.text == "target" }.nearestDeclaration()
                        as ContainingDeclaration.Found)
                    .declaration,
            )
            assertSame(
                nestedFile.declarations.single(),
                (nestedCalls.single { it.calleeExpression?.text == "nested" }.nearestDeclaration()
                        as ContainingDeclaration.Found)
                    .declaration,
            )
        } finally {
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                Disposer.dispose(disposable)
            }
            originalProperties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
