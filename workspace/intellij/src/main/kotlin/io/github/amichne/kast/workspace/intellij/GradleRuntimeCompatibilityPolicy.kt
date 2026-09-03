package io.github.amichne.kast.workspace.intellij

import org.gradle.util.GradleVersion

/** Java runtime feature admitted at the Gradle execution boundary. */
@JvmInline
internal value class JavaFeature private constructor(
    val value: Int,
) {
    companion object {
        fun of(value: Int): JavaFeature {
            require(value > 0)
            return JavaFeature(value)
        }
    }
}

internal enum class GradleRuntimeIncompatibility {
    JAVA_FEATURE_UNSUPPORTED,
    GRADLE_TOO_OLD,
    GRADLE_TOO_NEW,
}

/** Closed result of comparing one Gradle distribution with one runtime feature. */
internal sealed interface GradleRuntimeCompatibility {
    data class Compatible(
        val minimumGradle: GradleVersion,
    ) : GradleRuntimeCompatibility

    data class Incompatible(
        val failure: GradleRuntimeIncompatibility,
    ) : GradleRuntimeCompatibility
}

/**
 * Repository-local Gradle runtime policy derived from Gradle's published compatibility matrix.
 *
 * This policy concerns only the JVM that executes Gradle. Project toolchains and bytecode targets
 * remain part of the imported project model and do not participate in this decision.
 */
internal object GradleRuntimeCompatibilityPolicy {
    fun classify(
        gradle: GradleVersion,
        java: JavaFeature,
    ): GradleRuntimeCompatibility {
        val range = supportedRange(java) ?: return GradleRuntimeCompatibility.Incompatible(
            GradleRuntimeIncompatibility.JAVA_FEATURE_UNSUPPORTED,
        )
        if (gradle < range.minimum) {
            return GradleRuntimeCompatibility.Incompatible(
                GradleRuntimeIncompatibility.GRADLE_TOO_OLD,
            )
        }
        if (range.maximumExclusive != null && gradle >= range.maximumExclusive) {
            return GradleRuntimeCompatibility.Incompatible(
                GradleRuntimeIncompatibility.GRADLE_TOO_NEW,
            )
        }
        return GradleRuntimeCompatibility.Compatible(range.minimum)
    }

    private fun supportedRange(java: JavaFeature): SupportedGradleRange? = when (java.value) {
        8 -> range("2.0", "9.0")
        9 -> range("4.3", "9.0")
        10 -> range("4.7", "9.0")
        11 -> range("5.0", "9.0")
        12 -> range("5.4", "9.0")
        13 -> range("6.0", "9.0")
        14 -> range("6.3", "9.0")
        15 -> range("6.7", "9.0")
        16 -> range("7.0", "9.0")
        17 -> range("7.3")
        18 -> range("7.5")
        19 -> range("7.6")
        20 -> range("8.3")
        21 -> range("8.5")
        22 -> range("8.8")
        23 -> range("8.10")
        24 -> range("8.14")
        25 -> range("9.1.0")
        26 -> range("9.4.0")
        else -> null
    }

    private fun range(
        minimum: String,
        maximumExclusive: String? = null,
    ): SupportedGradleRange = SupportedGradleRange(
        minimum = GradleVersion.version(minimum),
        maximumExclusive = maximumExclusive?.let(GradleVersion::version),
    )
}

private data class SupportedGradleRange(
    val minimum: GradleVersion,
    val maximumExclusive: GradleVersion?,
)
