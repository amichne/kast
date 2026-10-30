package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.rpc.InvocationRecordKind
import io.github.amichne.kast.cli.rpc.QueryCostPlanFailure
import io.github.amichne.kast.cli.rpc.QueryCostPlanReply
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.costAdvice
import io.github.amichne.kast.cli.rpc.decodeToolRpcInput
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Public shell adapter. Metadata and admission precede installation composition. */
object KastPublicCliMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(
            runPublicCliInvocation(
                    {
                        preparePublicCli(
                            args.toList(),
                            Channels.newInputStream(FileInputStream(FileDescriptor.`in`).channel),
                            Path.of("").toAbsolutePath(),
                        )
                    },
                    Channels.newOutputStream(FileOutputStream(FileDescriptor.out).channel),
                )
                .code
        )
    }
}

internal data class PublicPreparedOutput(val text: String, val exit: PublicShellExit)

internal fun preparePublicCli(
    argv: List<String>,
    input: InputStream,
    directory: Path,
    session: (Path, PublicToolCatalog) -> KastDirectToolSession? = { root, catalog ->
        val environment = System.getenv()
        val home = Path.of(environment["HOME"] ?: System.getProperty("user.home"))
        KastDirectToolSession.installed(root, home, environment, catalog)
    },
    invoke: (KastDirectToolSession, AdmittedPublicTool) -> PublicPreparedOutput = ::invokeInstalledPublic,
): PublicPreparedOutput =
    when (val parsed = parsePublicCliArguments(argv)) {
        is Refinement.Rejected -> publicFailure(ToolRpcFailure.INVALID_COMMAND)
        is Refinement.Refined ->
            when (val command = parsed.value) {
                is PublicCliCommand.Help ->
                    PublicPreparedOutput(publicCliHelp(command.command).trimEnd(), PublicShellExit.COMPLETE)
                is PublicCliCommand.Describe ->
                    PublicPreparedOutput(
                        encodePublicCliDescriptor(describePublicCli(command)),
                        PublicShellExit.COMPLETE,
                    )
                is PublicCliCommand.Query ->
                    prepareQuery(command.input, command.catalog, input) { admitted ->
                        prepareInstalledQuery(command, directory, admitted, session, invoke)
                    }
                is PublicCliCommand.Plan -> prepareQuery(command.input, command.catalog, input, ::preparePlan)
            }
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
    use: (AdmittedPublicTool) -> PublicPreparedOutput,
): PublicPreparedOutput {
    val arguments =
        when (val loaded = readPublicCliInput(input, stdin)) {
            is Refinement.Refined -> loaded.value
            is Refinement.Rejected ->
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
    return when (val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, arguments, catalog)) {
        is Refinement.Refined -> use(admitted.value)
        is Refinement.Rejected -> preparePublicReply(ToolRpcReply.Rejected.publicInput(admitted.failure))
    }
}

internal enum class PublicInputReadFailure {
    IO,
    INVALID,
    TOO_LARGE,
}

internal fun readPublicCliInput(
    input: PublicCliInput,
    stdin: InputStream,
): Refinement<JsonObject, PublicInputReadFailure> =
    try {
        when (input) {
            is PublicCliInput.Lowered -> Refinement.Refined(input.arguments)
            is PublicCliInput.File -> Files.newInputStream(Path.of(input.path)).use(::readPublicArguments)
            PublicCliInput.Stdin -> readPublicArguments(stdin)
        }
    } catch (_: IOException) {
        Refinement.Rejected(PublicInputReadFailure.IO)
    } catch (_: InvalidPathException) {
        Refinement.Rejected(PublicInputReadFailure.INVALID)
    }

private fun readPublicArguments(input: InputStream): Refinement<JsonObject, PublicInputReadFailure> {
    val bytes = input.readNBytes(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)
    return if (bytes.size > MAXIMUM_PROTOCOL_TEXT_LENGTH) Refinement.Rejected(PublicInputReadFailure.TOO_LARGE)
    else
        when (val decoded = decodeToolRpcInput(bytes)) {
            is Refinement.Refined ->
                try {
                    Refinement.Refined(publicShellJson.decodeFromString<JsonObject>(decoded.value))
                } catch (_: SerializationException) {
                    Refinement.Rejected(PublicInputReadFailure.INVALID)
                }
            is Refinement.Rejected -> Refinement.Rejected(PublicInputReadFailure.INVALID)
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
                    session.start(root.root)
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
    } else PublicPreparedOutput(text, classified)
}

internal fun publicFailure(failure: ToolRpcFailure): PublicPreparedOutput =
    preparePublicReply(ToolRpcReply.Rejected(failure))

private val publicShellJson = Json { encodeDefaults = true }
