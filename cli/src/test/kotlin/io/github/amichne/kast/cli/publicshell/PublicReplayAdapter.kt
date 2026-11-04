package io.github.amichne.kast.cli.publicshell

import com.lukelast.ktoon.Ktoon
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Test-only adapter: admission terminates before installation or semantic composition. */
object PublicReplayAdapter {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.toList()) {
            listOf("manifest") -> print(replayJson.encodeToString(replayManifest()))
            listOf("admit") -> {
                val bytes = System.`in`.readNBytes(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)
                require(bytes.size <= MAXIMUM_PROTOCOL_TEXT_LENGTH) { "REPLAY_ENVELOPE_TOO_LARGE" }
                val request = replayJson.decodeFromString<ReplayAdmissionInput>(bytes.toString(Charsets.UTF_8))
                val format = if ("--json" in request.argv) CliOutputFormat.JSON else selectedReplayFormat()
                print(
                    replayJson.encodeToString(
                        replayAdmission(request.argv, request.stdin, Path.of(request.directory), format)
                    )
                )
            }
            listOf("contract") -> print(publicCliHelp(null))
            else -> {
                require(
                    (args.size == 2 && args[0] in listOf("render", "render-raw")) ||
                        (args.size == 3 && args[0] == "validate-fixture")
                ) {
                    "REPLAY_ADAPTER_COMMAND_REJECTED"
                }
                val bytes =
                    Files.newInputStream(Path.of(args.last())).use { it.readNBytes(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1) }
                require(bytes.size <= MAXIMUM_PROTOCOL_TEXT_LENGTH) { "REPLAY_DOCUMENT_TOO_LARGE" }
                val document = bytes.toString(Charsets.UTF_8)
                when (args[0]) {
                    "validate-fixture" -> {
                        val codec =
                            when (args[1]) {
                                "complete" -> CanonicalQueryCliDocuments.completeSerializer
                                "qualified" -> CanonicalQueryCliDocuments.qualifiedSerializer
                                "rejected" -> CanonicalQueryCliDocuments.rejectedSerializer
                                "rpc" -> ToolRpcReply.serializer()
                                else -> error("REPLAY_FIXTURE_KIND_REJECTED")
                            }
                        decodeReplayFixture(codec, document)
                        println("PRODUCTION_DOCUMENT_DECODED")
                    }
                    "render-raw" -> print(rawReplayRender(document, selectedReplayFormat()))
                    "render" -> print(replayRender(document, selectedReplayFormat()))
                }
            }
        }
    }
}

@Serializable
private data class ReplayAdmissionInput(val argv: List<String>, val stdin: String = "", val directory: String)

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
                        replayQueryAdmission(argv, stdin, directory, command.input, command.catalog)
                    is PublicCliCommand.Plan ->
                        replayQueryAdmission(argv, stdin, directory, command.input, command.catalog)
                    is PublicCliCommand.Help,
                    is PublicCliCommand.NamespaceHelp,
                    is PublicCliCommand.Describe -> {
                        val prepared =
                            presentPublicOutput(
                                preparePublicCli(
                                    argv,
                                    stdin.byteInputStream(),
                                    directory,
                                    session = { _, _ ->
                                        error("REPLAY_NATIVE_COMPOSITION_FORBIDDEN")
                                    },
                                ),
                                format,
                            )
                        ReplayAdmissionReceipt(
                            ReplayAdmissionType.METADATA,
                            argv,
                            stdin,
                            stdout = prepared.text,
                            exitStatus = prepared.exit.code,
                        )
                    }
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
    return when (val loaded = readPublicCliInput(ownedInput, stdin.byteInputStream())) {
        is Refinement.Rejected ->
            replayRejection(
                argv,
                stdin,
                ReplayAdmissionType.INPUT_REJECTED,
                loaded.failure.name,
                when (loaded.failure) {
                    PublicInputReadFailure.IO ->
                        preparePublicReply(
                            presentToolRpcExit(
                                PublicToolIdentity.QUERY_SYMBOLS.toolName,
                                boundaryExit(CliBoundaryExitStatus.TRANSPORT, "request-input-io"),
                            )
                        )
                    PublicInputReadFailure.INVALID -> publicFailure(ToolRpcFailure.INVALID_ARGUMENTS)
                    PublicInputReadFailure.TOO_LARGE -> publicFailure(ToolRpcFailure.REQUEST_TOO_LARGE)
                },
                publicCliInputDiagnostic(loaded.failure),
            )
        is Refinement.Refined ->
            when (val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, loaded.value, catalog)) {
                is Refinement.Refined ->
                    ReplayAdmissionReceipt(
                        ReplayAdmissionType.ADMITTED,
                        argv,
                        stdin,
                        arguments = PublicToolContract.encode(admitted.value),
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
    }
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

private fun <T> decodeReplayFixture(codec: KSerializer<T>, document: String) {
    replayJson.decodeFromString(codec, document)
}

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

@Serializable private data class ReplayArtifact(val path: String, val sha256: String)

@Serializable
private data class ReplayAdapterManifest(
    val command: List<String>,
    val artifacts: List<ReplayArtifact>,
    val sha256: String,
    val javaVersion: String,
)

private fun replayManifest(): ReplayAdapterManifest {
    val executable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath()
    val classpath = System.getProperty("java.class.path")
    val files =
        classpath
            .split(java.io.File.pathSeparator)
            .flatMap { item ->
                val path = Path.of(item).toAbsolutePath()
                when {
                    Files.isDirectory(path) ->
                        Files.walk(path).use { paths -> paths.filter(Files::isRegularFile).toList() }
                    Files.isRegularFile(path) -> listOf(path)
                    else -> emptyList()
                }
            }
            .plus(listOf(executable))
            .distinct()
            .sortedBy(Path::toString)
    val artifacts = files.map { ReplayArtifact(it.toString(), sha256(Files.readAllBytes(it))) }
    return ReplayAdapterManifest(
        listOf(executable.toString(), "-cp", classpath, PublicReplayAdapter::class.java.name),
        artifacts,
        sha256(replayJson.encodeToString(artifacts).toByteArray()),
        System.getProperty("java.version"),
    )
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val replayJson = Json { encodeDefaults = true }
