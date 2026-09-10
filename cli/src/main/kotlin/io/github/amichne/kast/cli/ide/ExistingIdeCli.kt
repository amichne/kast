package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection
import java.nio.file.Path

/** Hosted reads are selected before bootstrap can demand an isolated product or worker. */
internal fun executeExistingIdeCli(
    argv: List<String>, start: Path, roots: CanonicalRootDiscoverer, client: ExistingIdeClient,
): CliExit = when (val parsed = CliCommandGraphFactory.parseExistingIde(argv)) {
    is CliCommandParsing.Help -> CliExit.Complete(parsed.document)
    is CliCommandParsing.Rejected -> CliExit.BoundaryRejected(
        CliBoundaryExitStatus.USAGE,
        io.github.amichne.kast.cli.projection.CliBoundaryDocuments.usageRejected(parsed.failure, parsed.diagnostic),
    )
    is CliCommandParsing.ProjectionRejected -> boundaryExit(CliBoundaryExitStatus.PROTOCOL, "ide-projection-rejected")
    is CliCommandParsing.Parsed -> when (val action = parsed.action) {
        is CliAction.Local.ExistingIde -> executeExistingIdeAction(action, start, roots, client)
        else -> boundaryExit(CliBoundaryExitStatus.USAGE, "ide-command-required")
    }
}

internal fun executeExistingIdeAction(
    action: CliAction.Local.ExistingIde, start: Path, roots: CanonicalRootDiscoverer, client: ExistingIdeClient,
): CliExit {
    val selected = when (val selection = action.root) {
        ExistingIdeRootSelection.CurrentDirectory -> start
        is ExistingIdeRootSelection.Explicit -> selection.path
    }
    val root = when (val admitted = roots.discover(selected)) {
        is CanonicalRootDiscovery.Discovered -> admitted.root
        is CanonicalRootDiscovery.Rejected -> return boundaryExit(CliBoundaryExitStatus.ROOT, admitted.failure.name.lowercase())
    }
    return when (val exchange = client.query(root, action.operation)) {
        is ExistingIdeExchange.Received -> CliExit.Complete(exchange.document)
        is ExistingIdeExchange.Rejected -> boundaryExit(CliBoundaryExitStatus.RUNTIME, "ide-${exchange.failure.name.lowercase().replace('_', '-')}")
    }
}
