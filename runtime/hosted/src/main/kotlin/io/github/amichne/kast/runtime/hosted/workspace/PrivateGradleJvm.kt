package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Detached observations from IntelliJ's wrapper-specific Java-home validator. */
internal enum class GradleJavaHomeCompatibility {
    COMPATIBLE,
    INVALID_HOME,
    UNSUPPORTED_JAVA,
}

@Serializable
internal enum class PrivateGradleJvmAuthority {
    GRADLE_PROPERTY,
    LOCAL_PROPERTY,
    BUNDLED_RUNTIME,
    LOCAL_INSTALLATION,
}

internal sealed interface PrivateGradleJvmDeclaration {
    data object Unspecified : PrivateGradleJvmDeclaration

    data class GradleProperty(val home: String) : PrivateGradleJvmDeclaration

    data class LocalProperty(val home: String) : PrivateGradleJvmDeclaration
}

@Serializable
internal enum class PrivateGradleJvmFailure {
    DECLARED_HOME_INVALID,
    DECLARED_JAVA_UNSUPPORTED,
    NO_COMPATIBLE_LOCAL_JDK,
    AMBIGUOUS_SDK_REGISTRATION,
    SDK_NAME_CONFLICT,
    GRADLE_DISTRIBUTION_UNAVAILABLE,
    HOME_NOT_INSPECTED,
}

/** Selection preserves the accepted home and authority; consumers cannot construct an unchecked selection. */
internal class PrivateGradleJvm private constructor(val home: String, val authority: PrivateGradleJvmAuthority) {
    companion object {
        fun select(
            declaration: PrivateGradleJvmDeclaration,
            bundledHome: String,
            observations: Map<String, GradleJavaHomeCompatibility>,
        ): Refinement<PrivateGradleJvm, PrivateGradleJvmFailure> =
            when (declaration) {
                is PrivateGradleJvmDeclaration.GradleProperty ->
                    declared(declaration.home, PrivateGradleJvmAuthority.GRADLE_PROPERTY, observations)
                is PrivateGradleJvmDeclaration.LocalProperty ->
                    declared(declaration.home, PrivateGradleJvmAuthority.LOCAL_PROPERTY, observations)
                PrivateGradleJvmDeclaration.Unspecified -> automatic(bundledHome, observations)
            }

        private fun automatic(
            bundledHome: String,
            observations: Map<String, GradleJavaHomeCompatibility>,
        ): Refinement<PrivateGradleJvm, PrivateGradleJvmFailure> =
            when (observations[bundledHome]) {
                null -> Refinement.Rejected(PrivateGradleJvmFailure.HOME_NOT_INSPECTED)
                GradleJavaHomeCompatibility.COMPATIBLE ->
                    Refinement.Refined(PrivateGradleJvm(bundledHome, PrivateGradleJvmAuthority.BUNDLED_RUNTIME))
                GradleJavaHomeCompatibility.INVALID_HOME,
                GradleJavaHomeCompatibility.UNSUPPORTED_JAVA -> {
                    val compatible =
                        observations.entries.firstOrNull { it.value == GradleJavaHomeCompatibility.COMPATIBLE }
                    if (compatible == null) Refinement.Rejected(PrivateGradleJvmFailure.NO_COMPATIBLE_LOCAL_JDK)
                    else
                        Refinement.Refined(
                            PrivateGradleJvm(compatible.key, PrivateGradleJvmAuthority.LOCAL_INSTALLATION)
                        )
                }
            }

        private fun declared(
            home: String,
            authority: PrivateGradleJvmAuthority,
            observations: Map<String, GradleJavaHomeCompatibility>,
        ): Refinement<PrivateGradleJvm, PrivateGradleJvmFailure> =
            when (observations[home]) {
                null -> Refinement.Rejected(PrivateGradleJvmFailure.HOME_NOT_INSPECTED)
                GradleJavaHomeCompatibility.COMPATIBLE -> Refinement.Refined(PrivateGradleJvm(home, authority))
                GradleJavaHomeCompatibility.INVALID_HOME ->
                    Refinement.Rejected(PrivateGradleJvmFailure.DECLARED_HOME_INVALID)
                GradleJavaHomeCompatibility.UNSUPPORTED_JAVA ->
                    Refinement.Rejected(PrivateGradleJvmFailure.DECLARED_JAVA_UNSUPPORTED)
            }
    }
}
