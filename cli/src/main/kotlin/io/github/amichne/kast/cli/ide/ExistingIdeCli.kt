package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.DaemonChangeAction
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.diagnosticCode
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection
import io.github.amichne.kast.kernel.Refinement
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
        "change" -> CliRuntimePath.EXISTING_IDE
        else -> CliRuntimePath.INSTALLED
    }
}

/** The hosted path receives only its explicit local effects. */
internal class ExistingIdeCliCapabilities(
    val roots: CanonicalRootDiscoverer,
    val client: ExistingIdeClient,
    val read: DaemonOperationClient = DaemonOperationClient { _, _ ->
        DaemonOperationResult.Rejected(
            DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE)
        )
    },
)

/** Hosted operations are selected before bootstrap can demand an isolated product or worker. */
internal fun executeExistingIdeCli(
    argv: List<String>,
    start: Path,
    roots: CanonicalRootDiscoverer,
    client: ExistingIdeClient,
    requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
    read: DaemonOperationClient = DaemonOperationClient { _, _ ->
        DaemonOperationResult.Rejected(
            DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE)
        )
    },
): CliExit =
    executeExistingIdeCli(
        argv = argv,
        start = start,
        capabilities = ExistingIdeCliCapabilities(roots, client, read),
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
    return when (source) {
        is SemanticSource.PublicTool ->
            executeDaemonOperation(
                start,
                capabilities.roots,
                capabilities.read,
                DaemonOperationCall.PublicTool(source.tool),
            )
        is SemanticSource.CanonicalRead ->
            executeDaemonOperation(
                start,
                capabilities.roots,
                capabilities.read,
                DaemonOperationCall.Canonical(source.read),
            )
        is SemanticSource.ChangePlan,
        is SemanticSource.ChangeApply,
        is SemanticSource.ChangeRecover -> {
            val change =
                source.changeCall(operation)
                    ?: return boundaryExit(CliBoundaryExitStatus.RUNTIME, "daemon-operation-identity-rejected")
            executeDaemonOperation(start, capabilities.roots, capabilities.read, DaemonOperationCall.Change(change))
        }
        SemanticSource.Canonical -> boundaryExit(CliBoundaryExitStatus.RUNTIME, "daemon-operation-unsupported")
    }
}

private fun SemanticSource.changeCall(operation: ExistingIdeOperation): DaemonChangeAction? =
    when (this) {
        is SemanticSource.ChangePlan ->
            if (operation is ExistingIdeOperation.Plan) DaemonChangeAction.Plan(request) else null
        is SemanticSource.ChangeApply -> changeCall(operation)
        is SemanticSource.ChangeRecover -> changeCall(operation)
        else -> null
    }

private fun SemanticSource.ChangeApply.changeCall(operation: ExistingIdeOperation): DaemonChangeAction? =
    when (operation) {
        is ExistingIdeOperation.ApprovalPreparation ->
            if (
                operation.kind == HostedMutationOperation.CHANGE_APPLY &&
                    operation.identity.value == request.planIdentity.value
            )
                DaemonChangeAction.Prepare(operation.kind, operation.identity.value)
            else null
        is ExistingIdeOperation.ApprovedMutation ->
            if (
                operation.kind == HostedMutationOperation.CHANGE_APPLY &&
                    operation.identity.value == request.planIdentity.value
            )
                DaemonChangeAction.Apply(request, operation.assertion.value)
            else null
        else -> null
    }

private fun SemanticSource.ChangeRecover.changeCall(operation: ExistingIdeOperation): DaemonChangeAction? =
    when (operation) {
        is ExistingIdeOperation.ApprovalPreparation ->
            if (
                operation.kind == HostedMutationOperation.CHANGE_RECOVER &&
                    operation.identity.value == request.planIdentity.value
            )
                DaemonChangeAction.Prepare(operation.kind, operation.identity.value)
            else null
        is ExistingIdeOperation.ApprovedMutation ->
            if (
                operation.kind == HostedMutationOperation.CHANGE_RECOVER &&
                    operation.identity.value == request.planIdentity.value
            )
                DaemonChangeAction.Recover(request, operation.assertion.value)
            else null
        else -> null
    }

private fun executeDaemonOperation(
    start: Path,
    roots: CanonicalRootDiscoverer,
    read: DaemonOperationClient,
    call: DaemonOperationCall,
): CliExit {
    val root =
        when (val selected = roots.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> selected.root
            is CanonicalRootDiscovery.Rejected ->
                return boundaryExit(CliBoundaryExitStatus.ROOT, selected.failure.name.lowercase())
        }
    return when (val result = read.read(root, call)) {
        is DaemonOperationResult.Complete -> CliExit.Complete(result.document)
        is DaemonOperationResult.Qualified -> CliExit.Qualified(result.document)
        is DaemonOperationResult.OperationRejected -> CliExit.OperationRejected(result.document)
        is DaemonOperationResult.Hosted -> CliExit.Complete(result.document)
        is DaemonOperationResult.Rejected ->
            boundaryExit(CliBoundaryExitStatus.RUNTIME, result.failure.diagnosticCode())
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
