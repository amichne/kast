package io.github.amichne.kast.appserver.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ThreadMigrationObservation(
    val outcome: ThreadMigrationOutcome,
    val event: String = "kast_thread_migration",
) {
    fun toJson(): String = migrationJson.encodeToString(this)
}

@Serializable
internal sealed interface ThreadMigrationOutcome {
    @Serializable @SerialName("started") data object Started : ThreadMigrationOutcome

    @Serializable @SerialName("committed") data object Committed : ThreadMigrationOutcome

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: ThreadCatalogStoreFailure) : ThreadMigrationOutcome
}

private val migrationJson = Json { encodeDefaults = true }
