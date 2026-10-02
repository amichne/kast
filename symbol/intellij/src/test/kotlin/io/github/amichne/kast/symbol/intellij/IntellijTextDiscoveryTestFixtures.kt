package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Two native boundary test consumers share ownership of the disposable parser application. */
@OptIn(
    CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.K1Deprecation::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
)
internal fun withTextDiscoveryParser(home: Path, assertion: (Project) -> Unit) {
    val properties = listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
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
        ApplicationManager.getApplication().runReadAction { assertion(environment.project) }
    } finally {
        ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
        properties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }
}
