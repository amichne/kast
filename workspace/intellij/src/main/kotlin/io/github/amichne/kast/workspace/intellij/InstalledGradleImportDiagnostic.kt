package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleDistributionVersion
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class GradleInitializationScriptObservation { UNAVAILABLE, UNCLASSIFIED }

/** Preserve Gradle's finite missing-script condition without retaining its path or message. */
internal fun observeGradleInitializationScript(failure: Throwable?): GradleInitializationScriptObservation {
    for (cause in generateSequence(failure) { it.cause }.take(16)) {
        if (cause !is IllegalArgumentException) continue
        val message = cause.message.orEmpty()
        if (message.length <= 4096 && '\n' !in message &&
            message.startsWith("The specified initialization script '") && message.endsWith("' does not exist.")
        ) return GradleInitializationScriptObservation.UNAVAILABLE
    }
    return GradleInitializationScriptObservation.UNCLASSIFIED
}

/** A JVM class-file major observed only in the platform's bounded unsupported-bytecode error. */
@JvmInline
internal value class GradlePayloadClassFileMajor private constructor(val value: Int) {
    companion object {
        fun observe(failure: Throwable?): GradlePayloadCompatibility {
            val patterns = listOf(
                Regex("Unsupported class file major version ([0-9]{2,3})"),
                Regex("class file version ([0-9]{2,3})\\.0"),
            )
            for (cause in generateSequence(failure) { it.cause }.take(16)) {
                val message = cause.message.orEmpty()
                if (message.length > 4096) continue
                for (pattern in patterns) {
                    val major = pattern.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (major in 45..100) return GradlePayloadCompatibility.Unsupported(GradlePayloadClassFileMajor(major))
                }
            }
            return GradlePayloadCompatibility.Unclassified
        }
    }
}

internal sealed interface GradlePayloadCompatibility {
    data class Unsupported(val major: GradlePayloadClassFileMajor) : GradlePayloadCompatibility
    data object Unclassified : GradlePayloadCompatibility
}

internal fun interface InstalledGradleImportDiagnosticObserver {
    fun observe(outcome: InstalledGradleImportOutcome)
}

/** The process and daemon identities remain distinct even when their Java features are equal. */
internal data class InstalledGradleImportExecutionIdentity(
    val distribution: GradleDistributionVersion,
    val clientJava: JavaFeature,
    val clientHomeIdentity: GradleImportEnvironmentIdentity,
    val projectJava: JavaFeature,
    val projectHomeIdentity: GradleImportEnvironmentIdentity,
)

internal fun installedGradleImportDiagnosticObserver(
    sidecar: InstalledSidecarJvm,
    selected: SelectedGradleJvm,
): InstalledGradleImportDiagnosticObserver {
    val identity = InstalledGradleImportExecutionIdentity(
        GradleDistributionVersion.observed(selected.distribution.version),
        JavaFeature.of(Runtime.version().feature()),
        GradleImportEnvironmentIdentity.digest(sidecar.home.toString()),
        selected.feature,
        GradleImportEnvironmentIdentity.digest(selected.home.toString()),
    )
    return InstalledGradleImportDiagnosticObserver { outcome ->
        System.err.println("kast-indexer: Gradle import: ${identity.logFields(outcome)}")
    }
}

internal fun InstalledGradleImportExecutionIdentity.logFields(outcome: InstalledGradleImportOutcome) = buildJsonObject {
    put("stage", "model-import")
    put("distribution", distribution.value)
    put("clientJava", clientJava.value)
    put("clientHomeIdentity", clientHomeIdentity.value)
    put("projectJava", projectJava.value)
    put("projectHomeIdentity", projectHomeIdentity.value)
    when (outcome) {
        InstalledGradleImportOutcome.Completed -> put("outcome", "completed")
        InstalledGradleImportOutcome.Cancelled -> put("outcome", "cancelled")
        InstalledGradleImportOutcome.Failed -> put("outcome", "platform-rejected")
        InstalledGradleImportOutcome.InvalidJvmConfiguration -> put("outcome", "invalid-jvm-configuration")
        InstalledGradleImportOutcome.InitializationScriptUnavailable -> put("outcome", "initialization-script-unavailable")
        is InstalledGradleImportOutcome.IncompatiblePayload -> {
            put("outcome", "incompatible-tooling-payload")
            put("classFileMajor", outcome.major.value)
        }
    }
}
