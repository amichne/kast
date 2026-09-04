package io.github.amichne.kast.distribution.contract.gradle

import kotlinx.serialization.Serializable

/** Bounded detached JVM evidence shared by import, bootstrap persistence, and the installed CLI. */
@Serializable
@JvmInline
value class GradleJavaFeature private constructor(val value: Int) {
    init { require(value > 0) }
    companion object {
        fun of(value: Int): GradleJavaFeature {
            require(value > 0)
            return GradleJavaFeature(value)
        }
    }
}

@Serializable
@JvmInline
value class GradleDistributionVersion private constructor(val value: String) {
    init { require(Regex("[A-Za-z0-9._+-]{1,80}").matches(value)) }
    companion object {
        /** Extracts an already observed Gradle version into its bounded transport identity. */
        fun observed(value: String): GradleDistributionVersion {
            require(Regex("[A-Za-z0-9._+-]{1,80}").matches(value))
            return GradleDistributionVersion(value)
        }
    }
}

@Serializable
sealed interface GradleDistributionEvidence {
    @Serializable data object Unavailable : GradleDistributionEvidence
    @Serializable data class Observed(val version: GradleDistributionVersion) : GradleDistributionEvidence
}

@Serializable
enum class GradleJvmSelectionAuthority {
    DAEMON_JVM_CRITERIA,
    REPOSITORY_GRADLE_PROPERTY,
    SIDECAR_COMPATIBLE,
    PLATFORM_RESOLVER,
}

@Serializable
enum class GradleJvmCandidateDecision {
    SELECTED,
    INCOMPATIBLE_GRADLE,
    SHADOWED_BY_PROJECT_AUTHORITY,
    NOT_SELECTED,
}

@Serializable
data class GradleJvmCandidateEvidence(
    val java: GradleJavaFeature,
    val homeIdentity: GradleImportEnvironmentIdentity,
    val authority: GradleJvmSelectionAuthority,
    val decision: GradleJvmCandidateDecision,
)

@Serializable
enum class GradleJvmSelectionFailure(val correctiveAction: String) {
    GRADLE_DISTRIBUTION_UNAVAILABLE("Restore a supported Gradle wrapper in gradle/wrapper/gradle-wrapper.properties."),
    DAEMON_JVM_CRITERIA_UNSUPPORTED("Use version-only gradle/gradle-daemon-jvm.properties criteria and install that Java feature."),
    REPOSITORY_JAVA_HOME_INVALID("Set repository org.gradle.java.home to a canonical absolute JDK directory containing executable bin/java."),
    LOCAL_JVM_DISCOVERY_FAILED("Install a local JDK admitted for the wrapper version, then restart Kast."),
    NO_COMPATIBLE_RUNTIME("Install a JDK in requiredJava and set repository org.gradle.java.home to its canonical absolute home."),
    SDK_REGISTRATION_FAILED("Ensure the selected local JDK is readable and restart Kast to register it."),
}

@Serializable
sealed interface GradleJvmSelectionOutcome {
    @Serializable data class Selected(val candidate: GradleJvmCandidateEvidence) : GradleJvmSelectionOutcome {
        init { require(candidate.decision == GradleJvmCandidateDecision.SELECTED) }
    }
    @Serializable data class Rejected(val failure: GradleJvmSelectionFailure) : GradleJvmSelectionOutcome
}

@Serializable
data class GradleJvmSelectionReport(
    val distribution: GradleDistributionEvidence,
    val requiredJava: List<GradleJavaFeature>,
    val candidates: List<GradleJvmCandidateEvidence>,
    val outcome: GradleJvmSelectionOutcome,
) {
    init {
        require(requiredJava.size <= 99)
        require(candidates.size <= 32)
        require(requiredJava.distinct() == requiredJava)
        when (outcome) {
            is GradleJvmSelectionOutcome.Selected -> {
                require(distribution is GradleDistributionEvidence.Observed)
                require(outcome.candidate.java in requiredJava)
                require(candidates.filter { it.decision == GradleJvmCandidateDecision.SELECTED } == listOf(outcome.candidate))
            }
            is GradleJvmSelectionOutcome.Rejected -> require(candidates.none { it.decision == GradleJvmCandidateDecision.SELECTED })
        }
    }
}

@Serializable
sealed interface GradleJvmSelectionObservation {
    @Serializable data object Unobserved : GradleJvmSelectionObservation
    @Serializable data class Observed(val report: GradleJvmSelectionReport) : GradleJvmSelectionObservation
}
