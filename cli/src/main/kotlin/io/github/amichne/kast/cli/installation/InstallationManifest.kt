package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class InstallationManifest(
    val schemaVersion: Int = INSTALLATION_MANIFEST_SCHEMA_VERSION,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadIdentity: String,
    val controlSha256: String,
    val hostedPluginSha256: String? = null,
    val codexHome: String,
    val configuration: String,
    val workspaceRegistry: String,
    val stateRoot: String,
    val externalAnchors: List<ExternalAnchor>,
    val payloadFiles: List<PayloadFile>,
    val retention: Retention = Retention(),
)

@Serializable
internal data class ExternalAnchor(
    val kind: String,
    val path: String,
    val expectedLinkTarget: String? = null,
    val requiresCurrentTarget: String? = null,
    val expectedExecutable: String? = null,
    val expectedLabel: String? = null,
    val identityReceipt: String? = null,
    val expectedPhysicalDirectory: String? = null,
    val ownership: String = "declared-not-observed",
)

@Serializable internal data class PayloadFile(val path: String, val sha256: String, val mode: Int)

@Serializable
internal data class Retention(
    val payload: String = "until-successful-upgrade-or-explicit-uninstall",
    val config: String = "until-successful-upgrade-or-explicit-uninstall",
    val state: String = "after-exact-process-retirement",
    val externalAnchors: String = "after-live-identity-match",
)
