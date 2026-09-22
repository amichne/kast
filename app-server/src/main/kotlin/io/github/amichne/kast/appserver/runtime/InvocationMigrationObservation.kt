package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded migration evidence contains no invocation identity, arguments, fingerprint or filesystem path. */
@Serializable
internal data class InvocationMigrationObservation(
    val outcome: InvocationMigrationOutcome,
    val event: String = "kast_invocation_migration",
) {
    fun toJson(): String = migrationJson.encodeToString(this)
}

@Serializable
internal sealed interface InvocationMigrationOutcome {
    @Serializable @SerialName("started") data object Started : InvocationMigrationOutcome

    @Serializable @SerialName("committed") data object Committed : InvocationMigrationOutcome

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: InvocationFenceFailure) : InvocationMigrationOutcome
}

private val migrationJson = Json { encodeDefaults = true }
