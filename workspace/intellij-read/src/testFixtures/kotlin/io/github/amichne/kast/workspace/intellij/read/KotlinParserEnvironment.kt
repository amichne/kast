package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Parser and physical PSI fixtures need no removed FE10 analysis services. */
@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
fun kotlinParserProject(disposable: Disposable): Project {
    val application =
        KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForTests(disposable, CompilerConfiguration())
    val environment = KotlinCoreProjectEnvironment(disposable, application)
    KotlinCoreEnvironment.registerProjectExtensionPoints(environment.project.extensionArea)
    KotlinCoreEnvironment.registerProjectServices(environment.project)
    return environment.project
}
