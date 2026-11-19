package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.PublicInvocationProtocol
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import java.io.InputStream
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Build-time projection of the metadata a thin frontend reads locally, beside the installed coordinator.
 *
 * Help documents are produced by the public shell's own preparation and presentation, so the frontend prints the bytes
 * the coordinator would print, without a running server. Every other argument vector is forwarded unchanged.
 */
object PublicFrontendCatalog {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        print(frontendCatalogJson.encodeToString(PublicFrontendCatalogDocument.serializer(), document()))
    }

    internal fun document(): PublicFrontendCatalogDocument =
        PublicFrontendCatalogDocument(
            schemaVersion = FRONTEND_CATALOG_SCHEMA_VERSION,
            invocationProtocol = PublicInvocationProtocol.version,
            socket = "state/run/c.sock",
            bootstrap = listOf("libexec/kast-coordinator", "ensure"),
            handshakeMilliseconds = BrokerOperationalLimits.workerControlHandshake.value,
            outputSetting = PublicFrontendOutputSetting(PUBLIC_OUTPUT_SETTING, admittedOutputSettings()),
            exits = PublicShellExit.entries.associate { it.name to it.code },
            help = helpCandidates().mapNotNull(::helpEntry),
        )
}

@Serializable
internal data class PublicFrontendCatalogDocument(
    val schemaVersion: Int,
    /** The [PublicInvocationProtocol.version] this coordinator serves. */
    val invocationProtocol: Int,
    /** The coordinator socket, relative to the coordinator root. */
    val socket: String,
    /** Starts or joins the coordinator; the first element is relative to the coordinator root. */
    val bootstrap: List<String>,
    /** Bounds the connection handshake. Expiry precedes the request, so the coordinator counts as unreachable. */
    val handshakeMilliseconds: Long,
    val outputSetting: PublicFrontendOutputSetting,
    /** The published shell statuses, including those the frontend emits before any coordinator reply. */
    val exits: Map<String, Int>,
    val help: List<PublicFrontendHelp>,
)

/**
 * The output setting the public shell admits before preparing help. An absent variable or one of [admittedValues]
 * passes; any other value is forwarded, so the coordinator presents the rejection.
 */
@Serializable internal data class PublicFrontendOutputSetting(val variable: String, val admittedValues: List<String>)

/** An exact argument vector and the document the public shell presents for it, once the output setting is admitted. */
@Serializable internal data class PublicFrontendHelp(val arguments: List<String>, val text: String)

/** Every listed value must pass the shell's own admission; a value missing from the list is only forwarded. */
private fun admittedOutputSettings(): List<String> =
    listOf("", "0", "1").onEach { check(selectCliOutputFormat(it) is Refinement.Refined) }

private fun helpCandidates(): List<List<String>> =
    (PublicCliCommandId.entries.map { it.argv } + PublicCliNamespace.entries.map { listOf(it.word) })
        .distinct()
        .flatMap { path -> listOf(path + "--help", path + "-h") }

/** Only vectors the parser lowers to help qualify; the presented document is then taken verbatim. */
private fun helpEntry(arguments: List<String>): PublicFrontendHelp? {
    val parsed = parsePublicCliArguments(arguments)
    val command = (parsed as? Refinement.Refined)?.value ?: return null
    if (command !is PublicCliCommand.Help && command !is PublicCliCommand.NamespaceHelp) return null
    val prepared =
        preparePublicCli(
            arguments,
            InputStream.nullInputStream(),
            Path.of("/"),
            session = { _, _ -> error("help reached a session") },
        )
    check(prepared.content == PublicOutputContent.HELP && prepared.exit == PublicShellExit.COMPLETE)
    // The frontend applies the output-setting rule to every local help vector, so each must be subject to it.
    check(selectPublicOutputFormat(arguments, UNADMITTED_OUTPUT_SETTING) is Refinement.Rejected)
    return PublicFrontendHelp(arguments, presentPublicOutput(prepared, CliOutputFormat.JSON).text)
}

private val frontendCatalogJson = Json { encodeDefaults = true }

internal const val FRONTEND_CATALOG_SCHEMA_VERSION = 1

private const val UNADMITTED_OUTPUT_SETTING = "unadmitted"
