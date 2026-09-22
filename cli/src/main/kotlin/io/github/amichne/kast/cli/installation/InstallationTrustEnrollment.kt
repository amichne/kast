package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.ide.BrokerTrustFailure
import io.github.amichne.kast.cli.ide.BrokerTrustRegistrar
import io.github.amichne.kast.cli.ide.BrokerTrustResult
import io.github.amichne.kast.cli.ide.BrokerTrustStatus
import io.github.amichne.kast.cli.ide.FilesystemBrokerTrustRegistrar
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Runs under installation admission before retirement or activation. No key bytes enter observations. */
internal fun enrollInstallationTrust(
    home: Path,
    registrar: BrokerTrustRegistrar = FilesystemBrokerTrustRegistrar(home),
    observe: (InstallationTrustObservation) -> Unit = { System.err.println(it.toJson()) },
): BrokerTrustResult {
    val result = registrar.enroll()
    observe(
        InstallationTrustObservation(
            when (result) {
                is BrokerTrustResult.Complete -> InstallationTrustOutcome.Complete(result.status)
                is BrokerTrustResult.Rejected -> InstallationTrustOutcome.Rejected(result.failure)
            }
        )
    )
    return result
}

@Serializable
internal data class InstallationTrustObservation(
    val outcome: InstallationTrustOutcome,
    val event: String = "kast_installation_trust",
) {
    fun toJson(): String = trustJson.encodeToString(this)
}

@Serializable
internal sealed interface InstallationTrustOutcome {
    @Serializable
    @kotlinx.serialization.SerialName("complete")
    data class Complete(val status: BrokerTrustStatus) : InstallationTrustOutcome

    @Serializable
    @kotlinx.serialization.SerialName("rejected")
    data class Rejected(val failure: BrokerTrustFailure) : InstallationTrustOutcome
}

private val trustJson = Json { encodeDefaults = true }

internal fun installationTrustRejection(failure: BrokerTrustFailure): io.github.amichne.kast.cli.CliExit =
    io.github.amichne.kast.cli.CliExit.BoundaryRejected(
        io.github.amichne.kast.cli.CliBoundaryExitStatus.BOOTSTRAP,
        trustRejectionFactory.create(InstallationTrustRejectionDocument(failure)),
    )

@Serializable
private data class InstallationTrustRejectionDocument(
    val trust: BrokerTrustFailure,
    val operation: String = "installation.install",
    val status: String = "rejected",
    val reason: String = "broker-trust-rejected",
)

private val trustRejectionFactory =
    io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument.generated(
        InstallationTrustRejectionDocument.serializer()
    )
