package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
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
        "query",
        "symbol",
        "source",
        "relation",
        "traversal",
        "diagnostic",
        "change" -> CliRuntimePath.EXISTING_IDE
        else -> CliRuntimePath.INSTALLED
    }
}

/** The hosted path receives only its explicit local effects. */
internal class ExistingIdeCliCapabilities(
    val roots: CanonicalRootDiscoverer,
    val client: ExistingIdeClient,
    val trust: BrokerTrustRegistrar = BrokerTrustRegistrar.Unavailable,
)

/** Hosted operations are selected before bootstrap can demand an isolated product or worker. */
internal fun executeExistingIdeCli(
    argv: List<String>,
    start: Path,
    roots: CanonicalRootDiscoverer,
    client: ExistingIdeClient,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
): CliExit =
    executeExistingIdeCli(
        argv = argv,
        start = start,
        capabilities = ExistingIdeCliCapabilities(roots, client),
        requestInput = requestInput,
    )

internal fun executeExistingIdeCli(
    argv: List<String>,
    start: Path,
    capabilities: ExistingIdeCliCapabilities,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
): CliExit {
    val roots = capabilities.roots
    val client = capabilities.client
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
        is CliCommandParsing.ProjectionRejected ->
            boundaryExit(CliBoundaryExitStatus.PROTOCOL, "ide-projection-rejected")
        is CliCommandParsing.Parsed ->
            when (val action = parsed.action) {
                CliAction.Local.TrustBroker -> executeBrokerTrustEnrollment(capabilities.trust)
                is CliAction.Local.ExistingIde -> executeExistingIdeAction(action, start, roots, client)
                is CliAction.Semantic ->
                    when (val read = ingress.operation(action.request)) {
                        is Refinement.Refined ->
                            executeExistingIdeAction(
                                CliAction.Local.ExistingIde(read.value, ExistingIdeRootSelection.CurrentDirectory),
                                start,
                                roots,
                                client,
                            )
                        is Refinement.Rejected ->
                            boundaryExit(
                                CliBoundaryExitStatus.USAGE,
                                "ide-${read.failure.name.lowercase().replace('_', '-')}",
                            )
                    }
                else -> boundaryExit(CliBoundaryExitStatus.USAGE, "ide-command-required")
            }
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
                is ProjectedCliOutcome.Complete -> CliExit.Complete(outcome.document)
                is ProjectedCliOutcome.Qualified -> CliExit.Qualified(outcome.document)
                is ProjectedCliOutcome.Rejected -> CliExit.OperationRejected(outcome.document)
            }
        is ExistingIdeExchange.Rejected ->
            boundaryExit(CliBoundaryExitStatus.RUNTIME, "ide-${exchange.failure.name.lowercase().replace('_', '-')}")
    }
}
