package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.PublicInvocationProtocol
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
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
    /** The published shell statuses, including those the frontend emits before any coordinator reply. */
    val exits: Map<String, Int>,
    val help: List<PublicFrontendHelp>,
)

/** An exact argument vector and the document the public shell presents for it. */
@Serializable internal data class PublicFrontendHelp(val arguments: List<String>, val text: String)

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
    return PublicFrontendHelp(arguments, presentPublicOutput(prepared, CliOutputFormat.JSON).text)
}

private val frontendCatalogJson = Json { encodeDefaults = true }

internal const val FRONTEND_CATALOG_SCHEMA_VERSION = 1
