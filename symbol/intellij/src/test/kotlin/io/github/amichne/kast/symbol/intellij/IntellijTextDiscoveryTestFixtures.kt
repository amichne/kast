package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path
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
        val project = io.github.amichne.kast.workspace.intellij.read.kotlinParserProject(disposable)
        ApplicationManager.getApplication().runReadAction { assertion(project) }
    } finally {
        ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
        properties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }
}
