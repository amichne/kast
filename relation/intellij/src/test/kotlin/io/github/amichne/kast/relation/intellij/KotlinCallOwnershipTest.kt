package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class, org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
class KotlinCallOwnershipTest {
    @Test
    fun `local initializer call belongs to enclosing callable`() {
        val home = java.nio.file.Files.createTempDirectory("kast-psi-test")
        java.nio.file.Files.createDirectories(home.resolve("bin"))
        java.nio.file.Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForTests(
                disposable, CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val file = KtPsiFactory(environment.project).createFile(
                "fun outer(): String { val value = target(); return value }; fun target(): String = \"value\"")
            val outer = file.declarations.filterIsInstance<KtNamedFunction>().first()
            val call = PsiTreeUtil.findChildOfType(outer, KtCallExpression::class.java)!!
            assertSame(outer, (call.nearestDeclaration() as ContainingDeclaration.Found).declaration)
        } finally {
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
        }
    }
}
