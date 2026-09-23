package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.DaemonQueryClient
import io.github.amichne.kast.appserver.DaemonQueryClientFailure
import io.github.amichne.kast.appserver.DaemonQueryClientRejection
import io.github.amichne.kast.appserver.DaemonQueryResult
import io.github.amichne.kast.appserver.diagnosticCode
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument
import java.nio.file.Path

internal enum class CliRuntimePath {
    EXISTING_IDE,
    INSTALLED,
}

/** Raw command-family selection occurs at ingress, before any installed runtime effects. */
internal fun selectCliRuntimePath(argv: List<String>): CliRuntimePath {
    // Clikt accepts a root option terminator before the subcommand; retain argv for its parser.
    val command = if (argv.firstOrNull() == "--") argv.getOrNull(1) else argv.firstOrNull()
    return when (command) {
        "index",
        "ide",
        "tool",
        "symbol",
        "source",
        "relation",
        "traversal",
        "change" -> CliRuntimePath.EXISTING_IDE
        else -> CliRuntimePath.INSTALLED
    }
}

/** The hosted path receives only its explicit local effects. */
internal class ExistingIdeCliCapabilities(
    val roots: CanonicalRootDiscoverer,
    val client: ExistingIdeClient,
    val query: DaemonQueryClient = DaemonQueryClient { _, _ ->
        DaemonQueryResult.Rejected(DaemonQueryClientRejection.Transport(DaemonQueryClientFailure.UNAVAILABLE))
    },
)

/** Hosted operations are selected before bootstrap can demand an isolated product or worker. */
internal fun executeExistingIdeCli(
    argv: List<String>,
    start: Path,
    roots: CanonicalRootDiscoverer,
    client: ExistingIdeClient,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
    query: DaemonQueryClient = DaemonQueryClient { _, _ ->
        DaemonQueryResult.Rejected(DaemonQueryClientRejection.Transport(DaemonQueryClientFailure.UNAVAILABLE))
    },
): CliExit =
    executeExistingIdeCli(
        argv = argv,
        start = start,
        capabilities = ExistingIdeCliCapabilities(roots, client, query),
        requestInput = requestInput,
    )

internal fun executeExistingIdeCli(
    argv: List<String>,
    start: Path,
    capabilities: ExistingIdeCliCapabilities,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
): CliExit {
    val ingress =
        when (val admitted = admitHostedCliInput(argv, requestInput)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return boundaryExit(
                    CliBoundaryExitStatus.USAGE,
                    "ide-${admitted.failure.name.lowercase().replace('_', '-')}",
                )
        }
    return when (val parsed = parseExistingIdeCommand(ingress.argv, ingress.input)) {
        is CliCommandParsing.Help -> CliExit.Complete(parsed.document)
        is CliCommandParsing.Rejected ->
            CliExit.BoundaryRejected(
                CliBoundaryExitStatus.USAGE,
                io.github.amichne.kast.cli.projection.CliBoundaryDocuments.usageRejected(
                    parsed.failure,
                    parsed.diagnostic,
                ),
            )
        is CliCommandParsing.SourceRejected -> sourceRejectedExit(parsed)
        is CliCommandParsing.ProjectionRejected ->
            boundaryExit(CliBoundaryExitStatus.PROTOCOL, "ide-projection-rejected")
        is CliCommandParsing.Parsed ->
            when (val action = parsed.action) {
                is CliAction.Local.ExistingIde ->
                    executeExistingIdeAction(action, start, capabilities.roots, capabilities.client)
                is CliAction.Semantic -> executeSemanticAction(action, ingress, start, capabilities)
                else -> boundaryExit(CliBoundaryExitStatus.USAGE, "ide-command-required")
            }
    }
}

private fun executeSemanticAction(
    action: CliAction.Semantic,
    ingress: HostedCliInput,
    start: Path,
    capabilities: ExistingIdeCliCapabilities,
): CliExit {
    val operation =
        when (val read = ingress.operation(action.request)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected ->
                return boundaryExit(
                    CliBoundaryExitStatus.USAGE,
                    "ide-${read.failure.name.lowercase().replace('_', '-')}",
                )
        }
    val source = action.source
    return if (source is SemanticSource.PublicTool && source.tool.identity == PublicToolIdentity.QUERY_SYMBOLS)
        executeDaemonQuery(start, capabilities.roots, capabilities.query, source.tool)
    else
        executeExistingIdeAction(
            CliAction.Local.ExistingIde(operation, ExistingIdeRootSelection.CurrentDirectory),
            start,
            capabilities.roots,
            capabilities.client,
        )
}

private fun executeDaemonQuery(
    start: Path,
    roots: CanonicalRootDiscoverer,
    query: DaemonQueryClient,
    tool: io.github.amichne.kast.appserver.query.AdmittedPublicTool,
): CliExit {
    val root =
        when (val selected = roots.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> selected.root
            is CanonicalRootDiscovery.Rejected ->
                return boundaryExit(CliBoundaryExitStatus.ROOT, selected.failure.name.lowercase())
        }
    return when (val result = query.query(root, tool)) {
        is DaemonQueryResult.Complete -> CliExit.Complete(result.document)
        is DaemonQueryResult.Qualified -> CliExit.Qualified(result.document)
        is DaemonQueryResult.OperationRejected -> CliExit.OperationRejected(result.document)
        is DaemonQueryResult.Rejected -> boundaryExit(CliBoundaryExitStatus.RUNTIME, result.failure.diagnosticCode())
    }
}

private fun parseExistingIdeCommand(argv: List<String>, input: CliRequestDocumentInput): CliCommandParsing =
    if (argv.firstOrNull() in setOf("index", "ide")) CliCommandGraphFactory.parseExistingIde(argv)
    else
        when (val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> graph.factory.parse(argv, input)
            is CliCommandGraphConstruction.Rejected ->
                CliCommandParsing.Rejected(
                    CliCommandFailure.COMMAND_GRAPH_AMBIGUOUS,
                    CliTextDocument.commandRejected,
                )
        }

internal fun executeExistingIdeAction(
    action: CliAction.Local.ExistingIde,
    start: Path,
    roots: CanonicalRootDiscoverer,
    client: ExistingIdeClient,
): CliExit {
    val selected =
        when (val selection = action.root) {
            ExistingIdeRootSelection.CurrentDirectory -> start
            is ExistingIdeRootSelection.Explicit -> selection.path
        }
    val root =
        when (val admitted = roots.discover(selected)) {
            is CanonicalRootDiscovery.Discovered -> admitted.root
            is CanonicalRootDiscovery.Rejected ->
                return boundaryExit(CliBoundaryExitStatus.ROOT, admitted.failure.name.lowercase())
        }
    return when (val exchange = client.query(root, action.operation)) {
        is ExistingIdeExchange.Received -> CliExit.Complete(exchange.document)
        is ExistingIdeExchange.HostRejected -> CliExit.OperationRejected(exchange.document)
        is ExistingIdeExchange.Semantic ->
            when (val outcome = exchange.outcome) {
                is ProjectedOperationOutcome.Complete -> CliExit.Complete(outcome.document)
                is ProjectedOperationOutcome.Qualified -> CliExit.Qualified(outcome.document)
                is ProjectedOperationOutcome.Rejected -> CliExit.OperationRejected(outcome.document)
            }
        is ExistingIdeExchange.Rejected ->
            boundaryExit(CliBoundaryExitStatus.RUNTIME, "ide-${exchange.failure.name.lowercase().replace('_', '-')}")
    }
}

private fun sourceRejectedExit(parsed: CliCommandParsing.SourceRejected): CliExit =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.USAGE,
        io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument(parsed.failure),
    )
