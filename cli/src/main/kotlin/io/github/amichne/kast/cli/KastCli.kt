package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.appserver.UnavailableBrokerServerRunner
import io.github.amichne.kast.appserver.host.CodexClientLaunch
import io.github.amichne.kast.appserver.host.CodexClientLauncher
import io.github.amichne.kast.appserver.host.UnavailableCodexClientLauncher
import io.github.amichne.kast.appserver.outputReason
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.ProductInspectionDocuments
import java.nio.file.Path

/** Pure orchestration of the closed CLI boundaries and their explicit outer effects. */
class KastCli(
    private val commandGraphFactory: CliCommandGraphFactory,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val localMetadata: CliLocalMetadata,
    private val productVersion: io.github.amichne.kast.protocol.contract.KastPluginVersion,
    private val appServerManager: io.github.amichne.kast.appserver.AppServerManager =
        io.github.amichne.kast.appserver.UnavailableAppServerManager,
    private val brokerServerRunner: BrokerServerRunner = UnavailableBrokerServerRunner,
    private val codexClientLauncher: CodexClientLauncher = UnavailableCodexClientLauncher,
    private val existingIdeClient: io.github.amichne.kast.cli.ide.ExistingIdeClient =
        io.github.amichne.kast.cli.ide.ExistingIdeClient { _, _ ->
            io.github.amichne.kast.cli.ide.ExistingIdeExchange.Rejected(
                io.github.amichne.kast.cli.ide.ExistingIdeFailure.HOST_UNAVAILABLE
            )
        },
) {
    /**
     * Proof transition: `List<String> + Path -> CliExit`.
     *
     * Establishes a canonical command, exact root, admitted runtime endpoint, typed wire outcome, canonical JSON
     * document, and exhaustive process status. [CliBoundaryExitStatus] is the finite boundary-failure classification.
     * Raw argv and start path are permitted only here.
     */
    fun execute(
        argv: List<String>,
        start: Path,
    ): CliExit = execute(argv, start, CliRequestDocumentInput.Absent)

    internal fun execute(
        argv: List<String>,
        start: Path,
        requestInput: CliRequestDocumentInput,
    ): CliExit {
        if (argv == listOf("start") || argv == listOf("stop")) {
            return boundaryExit(CliBoundaryExitStatus.RUNTIME, "isolated-runtime-retired-use-ide-status")
        }
        if (
            io.github.amichne.kast.cli.ide.selectCliRuntimePath(argv) ==
                io.github.amichne.kast.cli.ide.CliRuntimePath.EXISTING_IDE
        ) {
            return io.github.amichne.kast.cli.ide.executeExistingIdeCli(
                argv,
                start,
                rootDiscovery,
                existingIdeClient,
                requestInput,
            )
        }
        return when (val parsed = commandGraphFactory.parse(argv, requestInput)) {
            is CliCommandParsing.Parsed -> executeAction(parsed.action, start)
            is CliCommandParsing.Help -> CliExit.Complete(parsed.document)
            is CliCommandParsing.Rejected -> usageExit(parsed.failure, parsed.diagnostic)
            is CliCommandParsing.ProjectionRejected -> projectionFailure(parsed.failure)
        }
    }

    private fun executeAction(action: CliAction, start: Path): CliExit =
        when (action) {
            is CliAction.Local.Metadata -> CliExit.Complete(localMetadata.output(action.command))
            CliAction.Local.Inspect,
            CliAction.Local.ProductInspect ->
                CliExit.Complete(ProductInspectionDocuments.complete(productVersion, rootDiscovery.discover(start)))
            is CliAction.Local.AppServer ->
                when (val result = appServerManager.execute(action.action, start)) {
                    is io.github.amichne.kast.appserver.AppServerManagementResult.Completed ->
                        when (val document = CliTextDocument.admit(result.document.toString())) {
                            is CliTextDocumentAdmission.Admitted -> CliExit.Complete(document.document)
                            is CliTextDocumentAdmission.Rejected ->
                                boundaryExit(CliBoundaryExitStatus.RUNTIME, "app-server-output-rejected")
                        }
                    is io.github.amichne.kast.appserver.AppServerManagementResult.Rejected ->
                        boundaryExit(
                            CliBoundaryExitStatus.RUNTIME,
                            (result.serviceFailure?.name ?: result.failure.name).lowercase().replace('_', '-'),
                        )
                }
            CliAction.Local.BrokerServe ->
                when (val run = brokerServerRunner.serve()) {
                    BrokerServerRun.Stopped -> CliExit.Complete(CliBoundaryDocuments.brokerStopped())
                    is BrokerServerRun.Rejected ->
                        boundaryExit(
                            CliBoundaryExitStatus.RUNTIME,
                            run.failure.outputReason(),
                        )
                }
            CliAction.Local.CodexCli -> launchCodex(codexClientLauncher, CodexClientLaunch.Cli)
            CliAction.Local.CodexDesktop -> launchCodex(codexClientLauncher, CodexClientLaunch.Desktop)
            CliAction.Local.TrustBroker -> boundaryExit(CliBoundaryExitStatus.RUNTIME, "ide-trust-unavailable")
            is CliAction.Local.ExistingIde ->
                io.github.amichne.kast.cli.ide.executeExistingIdeAction(action, start, rootDiscovery, existingIdeClient)
            is CliAction.Semantic -> boundaryExit(CliBoundaryExitStatus.USAGE, "existing-ide-command-required")
            is CliAction.Lifecycle ->
                when (action) {
                    CliAction.Lifecycle.Start,
                    CliAction.Lifecycle.Stop ->
                        boundaryExit(CliBoundaryExitStatus.RUNTIME, "isolated-runtime-retired-use-ide-status")
                    CliAction.Lifecycle.Status ->
                        io.github.amichne.kast.cli.ide.executeExistingIdeCli(
                            listOf("ide", "status"),
                            start,
                            rootDiscovery,
                            existingIdeClient,
                        )
                }
        }

    private fun projectionFailure(failure: CliProjectionFailure): CliExit =
        when (failure) {
            is CliProjectionFailure.RequestEncodingFailed ->
                boundaryExit(
                    CliBoundaryExitStatus.PROTOCOL,
                    "request-encoding-rejected",
                )
            is CliProjectionFailure.ResponseDecodingFailed ->
                boundaryExit(
                    CliBoundaryExitStatus.PROTOCOL,
                    "response-decoding-rejected",
                )
        }
}

enum class CliBoundaryExitStatus(val code: Int) {
    USAGE(2),
    ROOT(3),
    RUNTIME(4),
    TRANSPORT(5),
    PROTOCOL(6),
    BOOTSTRAP(9),
}

/** Complete and exhaustive process result; every variant carries its explicit output policy. */
sealed interface CliExit {
    val code: Int
    val document: CliProcessOutput

    data class Delegated(override val code: Int) : CliExit {
        override val document: CliProcessOutput = CliDelegatedProcessOutput
    }

    data class Complete(override val document: CliProcessOutput) : CliExit {
        override val code: Int = 0
    }

    data class Qualified(override val document: CliJsonDocument) : CliExit {
        override val code: Int = 0
    }

    data class OperationRejected(override val document: CliJsonDocument) : CliExit {
        override val code: Int = 0
    }

    data class BoundaryRejected(
        val status: CliBoundaryExitStatus,
        override val document: CliJsonDocument,
    ) : CliExit {
        override val code: Int = status.code
    }
}

internal fun boundaryExit(
    status: CliBoundaryExitStatus,
    reason: String,
): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        status,
        CliBoundaryDocuments.boundaryRejected(status, reason),
    )

private fun usageExit(
    failure: CliCommandFailure,
    diagnostic: CliTextDocument,
): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.USAGE,
        CliBoundaryDocuments.usageRejected(failure, diagnostic),
    )
