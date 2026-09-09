package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationChild
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentFailure
import io.github.amichne.kast.kernel.Refinement

/** Exact selected sidecar settings; ambient process state is captured only at composition. */
class SidecarEnvironmentInputs private constructor(private val selected: Map<String, String>) {
    fun importEnvironment(): Refinement<GradleImportEnvironment, GradleImportEnvironmentFailure> =
        currentGradleImportEnvironment(selected)

    internal fun processVariables(): Map<String, String> = selected.toMap()
    override fun toString(): String = "SidecarEnvironmentInputs(keys=${selected.keys})"

    companion object {
        val Empty: SidecarEnvironmentInputs = SidecarEnvironmentInputs(emptyMap())
        fun from(configuration: ResolvedKastConfiguration, inheritedJavaHome: String? = null): SidecarEnvironmentInputs {
            val selected = configuration.childEnvironment(ConfigurationChild.SIDECAR).toMutableMap()
            if (inheritedJavaHome != null && GradleImportEnvironment.INHERITED_JAVA_HOME_SETTING !in selected) {
                selected["JAVA_HOME"] = inheritedJavaHome
            }
            return SidecarEnvironmentInputs(selected.toMap())
        }
    }
}
