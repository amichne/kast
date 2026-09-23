package io.github.amichne.kast.cli.command.tool

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolRequestSerializer
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.wire.presentation.OperationRequestPreparer

internal class CliToolCommandSurface(val identity: PublicToolIdentity, val usage: String)

internal class PublicToolCommandFamily(val root: KastCommandGroup, val surface: List<CliToolCommandSurface>)

/** CLI and provider invoke the same schema-bound admission and pure canonical lowering. */
internal fun publicToolCommands(
    preparers: CanonicalCliRequestPreparers,
    input: CliRequestDocumentInput,
): PublicToolCommandFamily {
    val commands =
        PublicToolIdentity.entries.map { identity ->
            SemanticKastCommand(
                name = identity.toolName,
                operation = identity.operation,
                schemaUsage = "tool ${identity.toolName} < request.json",
                description = identity.description,
                serializer = PublicToolRequestSerializer(identity),
                requestInput = input,
                preparer =
                    OperationRequestPreparer { request ->
                        when (val canonical = request.canonical) {
                            is PublicToolCanonical.Query -> preparers.queryRun.prepare(canonical.request)
                            is PublicToolCanonical.Diagnostics -> preparers.diagnosticCheck.prepare(canonical.request)
                        }
                    },
                source = { request -> SemanticSource.PublicTool(request) },
            )
        }
    return PublicToolCommandFamily(
        KastCommandGroup("tool", "Invoke a public search, diagnostic, or advanced symbol tool with JSON stdin.")
            .subcommands(commands),
        PublicToolIdentity.entries.zip(commands) { identity, command ->
            CliToolCommandSurface(identity, command.schemaUsage)
        },
    )
}
