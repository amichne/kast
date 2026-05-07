package io.github.amichne.kast.cli.results

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class VerifyExtensionResult(
    val ok: Boolean,
    @SerialName("cli_version")
    val cliVersion: String,
    @SerialName("extension_version")
    val extensionVersion: String,
)
