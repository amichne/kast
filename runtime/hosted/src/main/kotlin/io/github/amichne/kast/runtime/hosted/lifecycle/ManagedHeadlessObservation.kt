package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class HeadlessProjectStoreFailure {
    INVALID_PROJECT_ROOT,
    WRONG_PROJECT_IDENTITY,
    WRONG_METADATA_PATH,
    EXTERNAL_STORAGE_UNAVAILABLE,
}

@Serializable
internal enum class HeadlessJbrFailure {
    INVALID_HOME,
    AMBIGUOUS_REGISTRATION,
}

@Serializable
internal sealed interface ManagedHeadlessObservation {
    @Serializable @SerialName("jbr_configured") data object JbrConfigured : ManagedHeadlessObservation

    @Serializable
    @SerialName("jbr_rejected")
    data class JbrRejected(val reason: HeadlessJbrFailure) : ManagedHeadlessObservation

    @Serializable @SerialName("startup_admitted") data object StartupAdmitted : ManagedHeadlessObservation

    @Serializable
    @SerialName("startup_rejected")
    data class StartupRejected(val reason: HeadlessHostFailure) : ManagedHeadlessObservation

    @Serializable @SerialName("storage_admitted") data object StorageAdmitted : ManagedHeadlessObservation

    @Serializable
    @SerialName("storage_rejected")
    data class StorageRejected(val reason: HeadlessProjectStoreFailure) : ManagedHeadlessObservation
}

internal fun recordManagedHeadless(observation: ManagedHeadlessObservation) {
    Logger.getInstance(KastHeadlessStarter::class.java)
        .info("kast_managed_headless " + Json.encodeToString(ManagedHeadlessObservation.serializer(), observation))
}
