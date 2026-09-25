package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.protocol.registry.OperationEffect
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One-shot, harness-neutral process boundary for semantic tool calls. */
object KastToolRpcMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of("").toAbsolutePath()
        val home = Path.of(System.getProperty("user.home"))
        val session = KastDirectToolSession.installed(directory, home)
        val bridge = session?.let(::KastToolRpcBridge)
        val response =
            when {
                bridge == null -> ToolRpcReply.Rejected(ToolRpcFailure.CATALOG_UNAVAILABLE)
                args.contentEquals(arrayOf("catalog")) -> bridge.catalog()
                args.size == 2 && args[0] == "call" -> {
                    val bytes = System.`in`.readNBytes(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)
                    if (bytes.size > MAXIMUM_PROTOCOL_TEXT_LENGTH)
                        ToolRpcReply.Rejected(ToolRpcFailure.REQUEST_TOO_LARGE)
                    else bridge.call(args[1], bytes.decodeToString())
                }
                else -> ToolRpcReply.Rejected(ToolRpcFailure.INVALID_COMMAND)
            }
        println(toolRpcJson.encodeToString<ToolRpcReply>(response))
    }
}

internal class KastToolRpcBridge(private val session: KastDirectToolSession) {
    private val tools =
        session.catalog.map { tool ->
            val effect =
                OperationEffect.entries.singleOrNull { it.name.lowercase() == tool.effect }
                    ?: error("Unknown canonical operation effect")
            check(effect == OperationEffect.NONE || effect == OperationEffect.INTELLIJ_READ)
            ToolRpcTool(tool.name, tool.description, ToolRpcToolEffect.READ, tool.inputSchema)
        } +
            session.supplemental.map { tool ->
                ToolRpcTool(
                    tool.name,
                    tool.description,
                    if (tool.readOnly) ToolRpcToolEffect.READ else ToolRpcToolEffect.WRITE,
                    tool.inputSchema,
                )
            }

    fun catalog(): ToolRpcReply = ToolRpcReply.Catalog(ToolRpcCatalog(tools.sortedBy(ToolRpcTool::name)))

    fun call(name: String, input: String): ToolRpcReply {
        if (tools.none { it.name == name }) return ToolRpcReply.Rejected(ToolRpcFailure.UNKNOWN_TOOL)
        val arguments =
            try {
                toolRpcJson.decodeFromString<JsonObject>(input)
            } catch (_: SerializationException) {
                return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_ARGUMENTS)
            }
        val root =
            session.root() as? CanonicalRootDiscovery.Discovered
                ?: return ToolRpcReply.Rejected(ToolRpcFailure.OUT_OF_SCOPE)
        // A rejected eager preparation does not erase the precise failure from the operation demand.
        session.start(root.root)
        val exit =
            try {
                session.invoke(name, arguments)
            } catch (_: RuntimeException) {
                return ToolRpcReply.Rejected(ToolRpcFailure.INVOCATION_FAILED)
            }
        val document =
            try {
                toolRpcJson.parseToJsonElement(exit.document.value) as? JsonObject
                    ?: return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
            } catch (_: SerializationException) {
                return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
            }
        return when (exit) {
            is CliExit.Complete -> ToolRpcReply.Complete(document)
            is CliExit.Qualified -> ToolRpcReply.Qualified(document)
            is CliExit.OperationRejected,
            is CliExit.BoundaryRejected -> ToolRpcReply.RejectedDocument(document)
            is CliExit.Delegated -> ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
        }
    }
}

@Serializable
internal sealed interface ToolRpcReply {
    @Serializable @SerialName("catalog") data class Catalog(val catalog: ToolRpcCatalog) : ToolRpcReply

    @Serializable @SerialName("complete") data class Complete(val document: JsonElement) : ToolRpcReply

    @Serializable @SerialName("qualified") data class Qualified(val document: JsonElement) : ToolRpcReply

    @Serializable @SerialName("rejected_document") data class RejectedDocument(val document: JsonElement) : ToolRpcReply

    @Serializable @SerialName("rejected") data class Rejected(val failure: ToolRpcFailure) : ToolRpcReply
}

@Serializable internal data class ToolRpcCatalog(val tools: List<ToolRpcTool>, val schemaVersion: Int = 1)

@Serializable
internal data class ToolRpcTool(
    val name: String,
    val description: String,
    val effect: ToolRpcToolEffect,
    /** Generated canonical request schema, retained unchanged until the ToolRpc projection. */
    val inputSchema: JsonElement,
)

@Serializable
internal enum class ToolRpcToolEffect {
    READ,
    WRITE,
}

@Serializable
internal enum class ToolRpcFailure {
    CATALOG_UNAVAILABLE,
    INVALID_COMMAND,
    REQUEST_TOO_LARGE,
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    OUT_OF_SCOPE,
    INVOCATION_FAILED,
    INVALID_RESULT,
}

private val toolRpcJson = Json { encodeDefaults = true }
