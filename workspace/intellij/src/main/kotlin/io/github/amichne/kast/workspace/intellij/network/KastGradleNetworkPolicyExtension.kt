package io.github.amichne.kast.workspace.intellij.network

import io.github.amichne.kast.distribution.contract.network.NetworkConfiguration
import io.github.amichne.kast.distribution.contract.network.NetworkProperty
import io.github.amichne.kast.distribution.managed.network.DAEMON_PREFIX
import io.github.amichne.kast.distribution.managed.network.GradleNetworkConfiguration
import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext

/** Applies already-admitted fallback at IntelliJ's per-operation daemon argument boundary. */
class KastGradleNetworkPolicyExtension : GradleExecutionHelperExtension {
    override fun configureSettings(settings: GradleExecutionSettings, context: GradleExecutionContext) {
        val raw = NetworkProperty.entries.mapNotNull { property ->
            System.getProperty(DAEMON_PREFIX + property.key)?.let { property.key to it }
        }.toMap()
        val fallback = when (val parsed = NetworkConfiguration.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw com.intellij.openapi.progress.ProcessCanceledException(IllegalStateException("network-policy-invariant-lost"))
        }
        val root = try { java.nio.file.Path.of(context.projectPath).toRealPath() }
            catch (_: java.io.IOException) { throw com.intellij.openapi.progress.ProcessCanceledException() }
        if (root.toString() != System.getProperty("kast.network.workspace.root")) {
            throw com.intellij.openapi.progress.ProcessCanceledException(IllegalStateException("network-root-mismatch"))
        }
        val userHome = java.nio.file.Path.of(System.getenv("GRADLE_USER_HOME")
            ?: java.nio.file.Path.of(System.getProperty("user.home"), ".gradle").toString())
        val owned = when (val read = GradleNetworkConfiguration.read(root, userHome, settings.gradleHome?.let(java.nio.file.Path::of))) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> throw com.intellij.openapi.progress.ProcessCanceledException(IllegalStateException("gradle-network-policy-unavailable"))
        }
        val explicit = when (val parsed = NetworkConfiguration.parse(owned.atJvmBoundary() + GradleNetworkConfiguration.fromArguments(settings.jvmArguments))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw com.intellij.openapi.progress.ProcessCanceledException(IllegalStateException("explicit-network-policy-rejected"))
        }
        settings.withVmOptions(fallback.missingFrom(explicit).atJvmBoundary().map { (key, value) -> "-D$key=$value" })
    }
}
