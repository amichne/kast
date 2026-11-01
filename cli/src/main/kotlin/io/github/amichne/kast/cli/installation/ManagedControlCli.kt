package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal sealed interface ManagedControlOutcome {
    @Serializable
    @SerialName("PREPARED")
    data class Prepared(val transaction: String, val staged: String) : ManagedControlOutcome

    @Serializable @SerialName("COMMITTED") data class Committed(val transaction: String) : ManagedControlOutcome

    @Serializable @SerialName("ABORTED") data class Aborted(val transaction: String) : ManagedControlOutcome

    @Serializable @SerialName("REJECTED") data class Rejected(val reason: InstallationFailure) : ManagedControlOutcome

    @Serializable
    @SerialName("RECOVERY_REQUIRED")
    data class RecoveryRequired(val reason: InstallationFailure) : ManagedControlOutcome
}

/** Private component adapter. The managed installer owns the complete composition and terminal result. */
object ManagedControlCli {
    fun run(arguments: List<String>, environment: Map<String, String>): Int {
        if (
            arguments.size != 2 ||
                arguments[0] !in setOf("prepare", "commit", "abort") ||
                !canonicalManagedTransaction(arguments[1])
        ) {
            println(
                installationManifestJson.encodeToString<ManagedControlOutcome>(
                    ManagedControlOutcome.Rejected(InstallationFailure.REQUEST_REJECTED)
                )
            )
            return 2
        }
        val request =
            when (val parsed = ControlInstallRequest.parse(environment)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> {
                    println(
                        installationManifestJson.encodeToString<ManagedControlOutcome>(
                            ManagedControlOutcome.Rejected(InstallationFailure.REQUEST_REJECTED)
                        )
                    )
                    return 1
                }
            }
        val result =
            when (arguments[0]) {
                "prepare" -> ManagedControlAdapter.prepare(request, arguments[1])
                "abort" -> ManagedControlAdapter.abort(request, arguments[1])
                else -> ManagedControlAdapter.commit(request, arguments[1])
            }
        println(installationManifestJson.encodeToString(result))
        return when (result) {
            is ManagedControlOutcome.Prepared,
            is ManagedControlOutcome.Aborted,
            is ManagedControlOutcome.Committed -> 0
            is ManagedControlOutcome.Rejected,
            is ManagedControlOutcome.RecoveryRequired -> 1
        }
    }
}

private fun canonicalManagedTransaction(value: String): Boolean =
    try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }
