package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.rpc.InvocationRecordKind
import io.github.amichne.kast.cli.rpc.QueryCostPlanFailure
import io.github.amichne.kast.cli.rpc.QueryCostPlanReply
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.costAdvice
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.output.cliOutputRejection
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/** Public shell adapter. Metadata and admission precede installation composition. */
object KastPublicCliMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(
            run(args) { input, directory ->
                preparePublicCli(args.toList(), input, directory, diagnostic = System.err::println)
            }
        )
    }

    internal fun coordinated(
        args: Array<String>,
        installation: Path,
        environment: Map<String, String>,
    ): Nothing {
        val started = System.nanoTime()
        val exit =
            run(args) { input, directory ->
                if (args.firstOrNull() == "workspace")
                    preparePublicHook(args.toList(), input, System.err::println) { root, wait ->
                        coordinatedHookPreparation(installation, environment, root, wait, System.err::println)
                    }
                else
                    preparePublicCli(
                        args.toList(),
                        input,
                        directory,
                        session = { workspace, catalog ->
                            coordinatedPublicSession(installation, workspace, catalog, environment)
                        },
                        diagnostic = System.err::println,
                    )
            }
        io.github.amichne.kast.distribution.cli.ControlStageTrace.record(
            io.github.amichne.kast.distribution.cli.ControlStage.END_TO_END,
            if (exit == 0) io.github.amichne.kast.distribution.cli.ControlStageOutcome.COMPLETED
            else io.github.amichne.kast.distribution.cli.ControlStageOutcome.REJECTED,
            started,
        )
        exitProcess(exit)
    }

    private fun run(args: Array<String>, prepare: (InputStream, Path) -> PublicPreparedOutput): Int {
        val output = Channels.newOutputStream(FileOutputStream(FileDescriptor.out).channel)
        val format =
            when (val selected = selectPublicOutputFormat(args.toList(), System.getenv(PUBLIC_OUTPUT_SETTING))) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return runPublicCliInvocation({ selected.failure }, output).code
            }
        return runPublicCliInvocation(
                {
                    prepare(
                        Channels.newInputStream(FileInputStream(FileDescriptor.`in`).channel),
                        Path.of("").toAbsolutePath(),
                    )
                },
                output,
                format,
            )
            .code
    }
}

internal fun nativeControlEnvironment(): Map<String, String> = System.getenv()

/** The caller's output setting; [selectPublicOutputFormat] admits it before any command, help included, is prepared. */
internal const val PUBLIC_OUTPUT_SETTING = "KAST_OUTPUT_TOON"

/** Hook replies and explicit `--json` use JSON; otherwise the caller's TOON setting selects the format. */
internal fun selectPublicOutputFormat(
    args: List<String>,
    toon: String?,
): Refinement<CliOutputFormat, PublicPreparedOutput> =
    if (args.firstOrNull() in setOf("hook", "workspace") || "--json" in args) Refinement.Refined(CliOutputFormat.JSON)
    else
        when (val selected = selectCliOutputFormat(toon)) {
            is Refinement.Refined -> selected
            is Refinement.Rejected ->
                Refinement.Rejected(
                    PublicPreparedOutput(
                        cliOutputRejection(selected.failure, CliOutputFormat.JSON),
                        PublicShellExit.INPUT,
                    )
                )
        }

internal sealed interface PublicOutputContent {
    data object DOCUMENT : PublicOutputContent

    data class Projection(
        val limits: PublicJqLimits,
        val recovery: io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery? = null,
    ) : PublicOutputContent

    data object HELP : PublicOutputContent
}

internal data class PublicPreparedOutput(
    val text: String,
    val exit: PublicShellExit,
    val content: PublicOutputContent = PublicOutputContent.DOCUMENT,
    val recovery: io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery? = null,
)

internal fun preparePublicCli(
    argv: List<String>,
    input: InputStream,
    directory: Path,
    session: (Path, PublicToolCatalog) -> KastDirectToolSession? = { root, catalog ->
        val environment = System.getenv()
        KastDirectToolSession.installed(root, environment, catalog)
    },
    invoke: (KastDirectToolSession, AdmittedPublicTool) -> PublicPreparedOutput = ::invokeInstalledPublic,
    diagnostic: (String) -> Unit = {},
    jqExecutor: PublicJqExecutor = PublicJqProcess(directory, emptyMap()),
): PublicPreparedOutput =
    if (argv.firstOrNull() in setOf("hook", "workspace")) preparePublicHook(argv, input, diagnostic)
    else
        when (val parsed = parsePublicCliArguments(argv)) {
            is Refinement.Rejected -> {
                diagnostic(publicCliArgumentDiagnostic(parsed.failure))
                publicFailure(ToolRpcFailure.INVALID_COMMAND)
            }
            is Refinement.Refined ->
                prepareProjectedPublicCli(parsed.value, directory, jqExecutor, diagnostic) {
                    when (val command = parsed.value) {
                        is PublicCliCommand.Help ->
                            PublicPreparedOutput(
                                publicCliHelp(command.command).trimEnd(),
                                PublicShellExit.COMPLETE,
                                PublicOutputContent.HELP,
                            )
                        is PublicCliCommand.NamespaceHelp ->
                            PublicPreparedOutput(
                                publicCliHelp(command.namespace.commands).trimEnd(),
                                PublicShellExit.COMPLETE,
                                PublicOutputContent.HELP,
                            )
                        is PublicCliCommand.Describe -> preparePublicDescriptor(command)
                        is PublicCliCommand.Query ->
                            prepareQuery(
                                command.input,
                                command.catalog,
                                input,
                                directory,
                                diagnostic,
                                command.identity,
                            ) { admitted ->
                                prepareInstalledQuery(command, directory, admitted, session, invoke)
                            }
                        is PublicCliCommand.Plan ->
                            prepareQuery(
                                command.input,
                                command.catalog,
                                input,
                                directory,
                                diagnostic,
                                use = ::preparePlan,
                            )
                    }
                }
        }

private fun prepareProjectedPublicCli(
    command: PublicCliCommand,
    directory: Path,
    executor: PublicJqExecutor,
    diagnostic: (String) -> Unit,
    prepare: () -> PublicPreparedOutput,
): PublicPreparedOutput =
    when (val projection = command.projection) {
        PublicOutputProjection.Unfiltered -> prepare()
        is PublicOutputProjection.Jq ->
            when (val engine = PublicJqEngine.prepare(projection.options, directory, executor)) {
                is Refinement.Rejected -> {
                    diagnostic("kast: ${engine.failure.failure}. ${engine.failure.guidance}")
                    engine.failure.prepared()
                }
                is Refinement.Refined ->
                    when (val projected = engine.value.project(prepare())) {
                        is Refinement.Refined -> projected.value
                        is Refinement.Rejected -> {
                            diagnostic("kast: ${projected.failure.failure}. ${projected.failure.guidance}")
                            projected.failure.prepared()
                        }
                    }
            }
    }

private fun preparePublicDescriptor(command: PublicCliCommand.Describe): PublicPreparedOutput {
    val descriptor = describePublicCli(command)
    val rejected =
        descriptor is PublicCliDescriptor.SchemaPage && descriptor.value.content is PublicSchemaPageReply.Rejected
    return PublicPreparedOutput(
        encodePublicCliDescriptor(descriptor),
        if (rejected) PublicShellExit.INPUT else PublicShellExit.COMPLETE,
    )
}

private fun prepareInstalledQuery(
    command: PublicCliCommand.Query,
    directory: Path,
    admitted: AdmittedPublicTool,
    session: (Path, PublicToolCatalog) -> KastDirectToolSession?,
    invoke: (KastDirectToolSession, AdmittedPublicTool) -> PublicPreparedOutput,
): PublicPreparedOutput {
    val root =
        try {
            when (val selection = command.workspace) {
                PublicCliWorkspace.Current -> directory
                is PublicCliWorkspace.Selected -> directory.resolve(selection.path).normalize()
            }
        } catch (_: InvalidPathException) {
            return publicFailure(ToolRpcFailure.INVALID_ARGUMENTS)
        }
    val installed = session(root, command.catalog) ?: return publicFailure(ToolRpcFailure.CATALOG_UNAVAILABLE)
    return invoke(installed, admitted)
}

private fun preparePlan(admitted: AdmittedPublicTool): PublicPreparedOutput {
    val canonical = admitted.canonical
    val request = (canonical as? PublicToolCanonical.Query)?.request
    val planned =
        if (request is QueryRunRequest.Run) QueryCostPlanReply.Planned(request, request.costAdvice())
        else QueryCostPlanReply.Rejected(QueryCostPlanFailure.RUN_REQUIRED)
    return PublicPreparedOutput(
        publicShellJson.encodeToString(QueryCostPlanReply.serializer(), planned),
        when (planned) {
            is QueryCostPlanReply.Planned -> PublicShellExit.COMPLETE
            is QueryCostPlanReply.Rejected -> PublicShellExit.INPUT
        },
    )
}

private fun prepareQuery(
    input: PublicCliInput,
    catalog: PublicToolCatalog,
    stdin: InputStream,
    directory: Path,
    diagnostic: (String) -> Unit,
    identity: PublicToolIdentity = PublicToolIdentity.QUERY_SYMBOLS,
    use: (AdmittedPublicTool) -> PublicPreparedOutput,
): PublicPreparedOutput {
    val arguments =
        when (val loaded = readPublicCliInput(input, stdin, identity, directory)) {
            is Refinement.Refined -> loaded.value
            is Refinement.Rejected -> {
                diagnostic(publicCliInputDiagnostic(loaded.failure))
                return when (loaded.failure) {
                    PublicInputReadFailure.IO ->
                        preparePublicReply(
                            presentToolRpcExit(
                                PublicToolIdentity.QUERY_SYMBOLS.toolName,
                                boundaryExit(CliBoundaryExitStatus.TRANSPORT, "request-input-io"),
                            )
                        )
                    PublicInputReadFailure.INVALID -> publicFailure(ToolRpcFailure.INVALID_ARGUMENTS)
                    PublicInputReadFailure.TOO_LARGE -> publicFailure(ToolRpcFailure.REQUEST_TOO_LARGE)
                }
            }
        }
    return when (val admitted = PublicToolContract.admit(identity, arguments, catalog)) {
        is Refinement.Refined -> use(admitted.value)
        is Refinement.Rejected -> preparePublicReply(ToolRpcReply.Rejected.publicInput(admitted.failure))
    }
}

private fun invokeInstalledPublic(session: KastDirectToolSession, admitted: AdmittedPublicTool): PublicPreparedOutput =
    session.beginInvocation(InvocationRecordKind.RPC)?.use { invokePublicSession(session, admitted) }
        ?: publicFailure(ToolRpcFailure.OBSERVATION_UNAVAILABLE)

/** Public admission finishes before this function can receive a request. No automatic replay or drain exists. */
internal fun invokePublicSession(session: KastDirectToolSession, admitted: AdmittedPublicTool): PublicPreparedOutput =
    when {
        session.admission() != InstalledToolAdmission.AVAILABLE -> publicFailure(ToolRpcFailure.INSTALLATION_STOPPED)
        admitted.catalog != session.queryCatalog -> publicFailure(ToolRpcFailure.INVALID_ARGUMENTS)
        !session.supports(admitted.identity.toolName) -> publicFailure(ToolRpcFailure.UNKNOWN_TOOL)
        else ->
            when (val root = session.root()) {
                is CanonicalRootDiscovery.Rejected -> publicFailure(ToolRpcFailure.OUT_OF_SCOPE)
                is CanonicalRootDiscovery.Discovered -> {
                    prepareSessionExit(session.invokeAdmitted(admitted), admitted)
                }
            }
    }

private fun prepareSessionExit(
    exit: io.github.amichne.kast.cli.CliExit,
    admitted: AdmittedPublicTool,
): PublicPreparedOutput {
    val prepared = preparePublicReply(presentToolRpcExit(admitted.identity.toolName, exit), elapsedGrant(admitted))
    return when (exit) {
        is io.github.amichne.kast.cli.CliExit.BoundaryRejected ->
            when (val cause = exit.cause) {
                io.github.amichne.kast.cli.CliFailureCause.Unspecified -> prepared
                is io.github.amichne.kast.cli.CliFailureCause.Runtime ->
                    prepared.copy(exit = classifyPublicRuntimeFailure(cause.rejection))
            }
        is io.github.amichne.kast.cli.CliExit.Complete,
        is io.github.amichne.kast.cli.CliExit.Qualified,
        is io.github.amichne.kast.cli.CliExit.OperationRejected,
        is io.github.amichne.kast.cli.CliExit.PublicInputRejected,
        is io.github.amichne.kast.cli.CliExit.Delegated -> prepared
    }
}

private fun elapsedGrant(admitted: AdmittedPublicTool): PublicElapsedGrant =
    when (val canonical = admitted.canonical) {
        is PublicToolCanonical.Query ->
            if (canonical.request.executionBudget?.maxElapsedMillis != null) PublicElapsedGrant.EXPLICIT
            else PublicElapsedGrant.ABSENT
        is PublicToolCanonical.Source ->
            if (canonical.request.executionBudget?.maxElapsedMillis != null) PublicElapsedGrant.EXPLICIT
            else PublicElapsedGrant.ABSENT
        is PublicToolCanonical.Change,
        is PublicToolCanonical.Diagnostics -> PublicElapsedGrant.ABSENT
    }

internal fun preparePublicReply(
    reply: ToolRpcReply,
    grant: PublicElapsedGrant = PublicElapsedGrant.ABSENT,
): PublicPreparedOutput {
    val classified = classifyPublicReply(reply, grant)
    val text = publicShellJson.encodeToString(ToolRpcReply.serializer(), reply)
    return if (text.toByteArray(Charsets.UTF_8).size > BrokerOperationalLimits.maximumToolRpcResponseBytes) {
        val failure =
            presentToolRpcExit(
                PublicToolIdentity.QUERY_SYMBOLS.toolName,
                boundaryExit(CliBoundaryExitStatus.TRANSPORT, "response-output-limit"),
            )
        PublicPreparedOutput(publicShellJson.encodeToString(ToolRpcReply.serializer(), failure), PublicShellExit.IO)
    } else {
        val recovery =
            if (classified == PublicShellExit.COMPLETE && reply is ToolRpcReply.Complete)
                retainedJqRecovery(reply.document)
            else null
        PublicPreparedOutput(text, classified, recovery = recovery)
    }
}

internal fun publicFailure(failure: ToolRpcFailure): PublicPreparedOutput =
    preparePublicReply(ToolRpcReply.Rejected(failure))

private val publicShellJson = Json { encodeDefaults = true }
