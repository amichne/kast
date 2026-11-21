package io.github.amichne.kast.cli.publicshell

import com.lukelast.ktoon.Ktoon
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Exact public admission for one recorded invocation, without a Host or semantic executor. */
@Serializable
internal data class ReplayAdmissionReceipt(
    val type: ReplayAdmissionType,
    val argv: List<String>,
    val stdin: String,
    /** Contract-defined dynamic field: PublicToolContract.encode owns this admitted public document. */
    val arguments: JsonElement? = null,
    val cause: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val exitStatus: Int = 0,
    val nativeCalls: Int = 0,
    val invocation: ReplayInvocation? = null,
)

@Serializable
internal enum class ReplayAdmissionType {
    ADMITTED,
    ARGUMENT_REJECTED,
    INPUT_REJECTED,
    PUBLIC_REJECTED,
    METADATA,
    FIXTURE_FILE_REJECTED,
}

internal fun replayAdmission(
    argv: List<String>,
    stdin: String = "",
    directory: Path = Path.of("").toAbsolutePath(),
    format: CliOutputFormat = CliOutputFormat.JSON,
): ReplayAdmissionReceipt =
    presentReplayReceipt(
        when (val parsed = parsePublicCliArguments(argv)) {
            is Refinement.Rejected ->
                replayRejection(
                    argv,
                    stdin,
                    ReplayAdmissionType.ARGUMENT_REJECTED,
                    parsed.failure.name,
                    publicFailure(ToolRpcFailure.INVALID_COMMAND),
                    publicCliArgumentDiagnostic(parsed.failure),
                )
            is Refinement.Refined ->
                when (val command = parsed.value) {
                    is PublicCliCommand.Query ->
                        replayQueryAdmission(
                            argv,
                            stdin,
                            directory,
                            command.input,
                            command.catalog,
                            ReplayInvocation(ReplayOperation.EXECUTE, replayWorkspace(command.workspace, directory)),
                        )
                    is PublicCliCommand.Plan ->
                        replayQueryAdmission(
                            argv,
                            stdin,
                            directory,
                            command.input,
                            command.catalog,
                            ReplayInvocation(ReplayOperation.PLAN, replayWorkspace(command.workspace, directory)),
                        )
                    is PublicCliCommand.Help,
                    is PublicCliCommand.NamespaceHelp,
                    is PublicCliCommand.Describe -> replayMetadata(argv, stdin, directory, format)
                }
        },
        format,
    )

private fun selectedReplayFormat(): CliOutputFormat =
    when (val selected = selectCliOutputFormat(System.getenv("KAST_OUTPUT_TOON"))) {
        is Refinement.Refined -> selected.value
        is Refinement.Rejected -> error(selected.failure.name)
    }

private fun presentReplayReceipt(receipt: ReplayAdmissionReceipt, format: CliOutputFormat): ReplayAdmissionReceipt =
    if (receipt.stdout.isEmpty() || receipt.type == ReplayAdmissionType.METADATA) receipt
    else receipt.copy(stdout = replayRender(receipt.stdout.removeSuffix("\n"), format) + "\n")

private fun replayQueryAdmission(
    argv: List<String>,
    stdin: String,
    directory: Path,
    input: PublicCliInput,
    catalog: io.github.amichne.kast.protocol.registry.PublicToolCatalog,
    invocation: ReplayInvocation,
): ReplayAdmissionReceipt {
    // The production reader accepts paths. The replay capability restricts those paths to fixture-owned files.
    val ownedInput =
        when (input) {
            is PublicCliInput.File -> {
                val root = directory.toRealPath()
                val selected = root.resolve(input.path).normalize()
                if (!fixtureOwnedFile(root, selected)) {
                    return ReplayAdmissionReceipt(
                        ReplayAdmissionType.FIXTURE_FILE_REJECTED,
                        argv,
                        stdin,
                        cause = "FIXTURE_FILE_REQUIRED",
                        exitStatus = 2,
                    )
                }
                PublicCliInput.File(selected.toString())
            }
            is PublicCliInput.Lowered,
            PublicCliInput.Stdin -> input
        }
    return when (
        val loaded =
            readPublicCliInput(ownedInput, stdin.byteInputStream(), PublicToolIdentity.QUERY_SYMBOLS, directory)
    ) {
        is Refinement.Rejected ->
            replayRejection(
                argv,
                stdin,
                ReplayAdmissionType.INPUT_REJECTED,
                loaded.failure.name,
                replayInputReadFailure(loaded.failure),
                publicCliInputDiagnostic(loaded.failure),
            )
        is Refinement.Refined -> replayPublicAdmission(argv, stdin, loaded.value, catalog, invocation)
    }
}

private fun replayPublicAdmission(
    argv: List<String>,
    stdin: String,
    request: JsonObject,
    catalog: io.github.amichne.kast.protocol.registry.PublicToolCatalog,
    invocation: ReplayInvocation,
): ReplayAdmissionReceipt =
    when (val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, request, catalog)) {
        is Refinement.Refined ->
            ReplayAdmissionReceipt(
                ReplayAdmissionType.ADMITTED,
                argv,
                stdin,
                arguments = PublicToolContract.encode(admitted.value),
                invocation = invocation,
            )
        is Refinement.Rejected ->
            replayRejection(
                argv,
                stdin,
                ReplayAdmissionType.PUBLIC_REJECTED,
                publicCause(admitted.failure),
                preparePublicReply(ToolRpcReply.Rejected.publicInput(admitted.failure)),
            )
    }

private fun replayInputReadFailure(failure: PublicInputReadFailure): PublicPreparedOutput =
    when (failure) {
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

private fun fixtureOwnedFile(root: Path, selected: Path): Boolean {
    if (!selected.startsWith(root)) return false
    var component = root
    for (part in root.relativize(selected)) {
        component = component.resolve(part)
        if (Files.isSymbolicLink(component)) return false
    }
    return Files.isRegularFile(selected) && selected.toRealPath().startsWith(root)
}

private fun publicCause(failure: PublicToolInputFailure): String =
    when (failure) {
        PublicToolInputFailure.SchemaMismatch -> "SCHEMA_MISMATCH"
        PublicToolInputFailure.SchemaRejected -> "SCHEMA_REJECTED"
        PublicToolInputFailure.SyntaxRejected -> "SYNTAX_REJECTED"
        is PublicToolInputFailure.SourceAnchor -> "SOURCE_ANCHOR"
        is PublicToolInputFailure.Parameter -> failure.rule.name
        is PublicToolInputFailure.Constraint -> failure.rule.name
    }

private fun replayRejection(
    argv: List<String>,
    stdin: String,
    type: ReplayAdmissionType,
    cause: String,
    prepared: PublicPreparedOutput,
    diagnostic: String = "",
): ReplayAdmissionReceipt =
    ReplayAdmissionReceipt(
        type,
        argv,
        stdin,
        cause = cause,
        stdout = prepared.text + "\n",
        stderr = if (diagnostic.isEmpty()) "" else diagnostic + "\n",
        exitStatus = prepared.exit.code,
    )

/** Renders through production output and proves equality with an independent decoder. */
private fun rawReplayRender(document: String, format: CliOutputFormat): String =
    when (val outcome = renderCliOutput(document, format)) {
        is Refinement.Refined -> outcome.value
        is Refinement.Rejected -> error(outcome.failure.name)
    }

internal fun replayRender(document: String, format: CliOutputFormat): String {
    val rendered = rawReplayRender(document, format)
    val decoded =
        when (format) {
            CliOutputFormat.JSON -> Json.parseToJsonElement(rendered)
            CliOutputFormat.TOON -> Ktoon.Default.decodeToonToJson(rendered)
        }
    require(Json.parseToJsonElement(document) == decoded) { "REPLAY_ENCODING_MISMATCH" }
    return rendered
}

private val replayJson = Json { encodeDefaults = true }
