package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A literal RFC 6901 location and presentation budget, never a semantic query grant. */
internal class PublicSchemaSelection
private constructor(
    val pointer: String,
    val tokens: List<String>,
    val cursor: Int,
    val maxEntries: Int,
    val maxBytes: Int,
) {
    companion object {
        fun parse(
            pointer: String,
            cursor: String,
            maxEntries: String,
            maxBytes: String,
        ): Refinement<PublicSchemaSelection, PublicCliArgumentFailure> {
            val offset = cursor.toIntOrNull()
            val count = maxEntries.toIntOrNull()
            val bytes = maxBytes.toIntOrNull()
            when {
                !validSchemaPointer(pointer) ->
                    return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION)
                offset == null || offset < 0 ->
                    return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION)
                count == null || count !in 1..MAX_SCHEMA_ENTRIES ->
                    return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION)
                bytes == null || bytes !in MIN_SCHEMA_BYTES..MAX_SCHEMA_BYTES ->
                    return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION)
            }
            val tokens =
                if (pointer.isEmpty()) emptyList()
                else pointer.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
            return Refinement.Refined(PublicSchemaSelection(pointer, tokens, offset, count, bytes))
        }
    }
}

@Serializable
internal enum class PublicSchemaKind {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
}

@Serializable
internal data class PublicSchemaNode(
    val pointer: String,
    val kind: PublicSchemaKind,
    @SerialName("child_count") val childCount: Int,
    /** Only scalar schema data is opaque JSON. Containers are addressed, never recursively copied. */
    val value: JsonElement? = null,
)

@Serializable
internal sealed interface PublicSchemaPageReply {
    @Serializable
    @SerialName("page")
    data class Page(
        val node: PublicSchemaNode,
        val cursor: Int,
        val entries: List<PublicSchemaNode>,
        val complete: Boolean,
        @SerialName("next_cursor") val nextCursor: Int? = null,
        @Required val schema: String = "kast.cli.schema-page.v1",
    ) : PublicSchemaPageReply

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val failure: PublicSchemaPageFailure,
        @Required val schema: String = "kast.cli.schema-page.v1",
    ) : PublicSchemaPageReply
}

@Serializable
internal enum class PublicSchemaPageFailure {
    POINTER_NOT_FOUND,
    CURSOR_OUT_OF_RANGE,
    ENTRY_TOO_LARGE,
    OUTPUT_ENCODING,
}

private const val MAX_SCHEMA_POINTER_LENGTH = 2048
private const val MAX_SCHEMA_ENTRIES = 128
private const val MIN_SCHEMA_BYTES = 1024
private const val MAX_SCHEMA_BYTES = 65536

private fun validSchemaPointer(pointer: String): Boolean =
    when {
        pointer.length > MAX_SCHEMA_POINTER_LENGTH -> false
        pointer.isNotEmpty() && !pointer.startsWith('/') -> false
        else -> !Regex("~(?![01])").containsMatchIn(pointer)
    }

/** Does not resolve references. Returned pointers remain rooted in the full selected section. */
internal fun browsePublicSchema(
    schema: JsonObject,
    command: PublicCliCommand.Describe,
): DescribeDocument<PublicSchemaPageReply> {
    val selection = requireNotNull(command.selection)
    val reply =
        when (val selected = resolveSchemaPointer(schema, selection.tokens)) {
            is Refinement.Rejected -> PublicSchemaPageReply.Rejected(selected.failure)
            is Refinement.Refined -> collectSchemaPage(selected.value, command)
        }
    return schemaPageDocument(command, reply)
}

private fun resolveSchemaPointer(
    schema: JsonObject,
    tokens: List<String>,
): Refinement<JsonElement, PublicSchemaPageFailure> {
    var selected: JsonElement = schema
    for (token in tokens) {
        selected =
            when (val current = selected) {
                is JsonObject -> current[token]
                is JsonArray ->
                    token
                        .takeIf { it == "0" || it.matches(Regex("[1-9][0-9]*")) }
                        ?.toIntOrNull()
                        ?.let(current::getOrNull)
                else -> null
            } ?: return Refinement.Rejected(PublicSchemaPageFailure.POINTER_NOT_FOUND)
    }
    return Refinement.Refined(selected)
}

private fun schemaChildren(value: JsonElement): Sequence<Pair<String, JsonElement>> =
    when (value) {
        is JsonObject -> value.entries.asSequence().map { it.key to it.value }
        is JsonArray -> value.asSequence().mapIndexed { index, child -> index.toString() to child }
        else -> emptySequence()
    }

private fun collectSchemaPage(selected: JsonElement, command: PublicCliCommand.Describe): PublicSchemaPageReply {
    val selection = requireNotNull(command.selection)
    val node = schemaNode(selection.pointer, selected)
    if (selection.cursor > node.childCount || selection.cursor == node.childCount && node.childCount > 0)
        return PublicSchemaPageReply.Rejected(PublicSchemaPageFailure.CURSOR_OUT_OF_RANGE)
    val entries = mutableListOf<PublicSchemaNode>()
    for ((key, value) in schemaChildren(selected).drop(selection.cursor).take(selection.maxEntries)) {
        val pointer = selection.pointer + "/" + key.replace("~", "~0").replace("/", "~1")
        val next = schemaNode(pointer, value)
        val candidate = schemaPage(node, selection.cursor, entries + next)
        when (val fitting = schemaPageFits(schemaPageDocument(command, candidate), selection.maxBytes)) {
            is Refinement.Refined -> entries.add(next)
            is Refinement.Rejected -> {
                if (fitting.failure != PublicSchemaPageFailure.ENTRY_TOO_LARGE)
                    return PublicSchemaPageReply.Rejected(fitting.failure)
                break
            }
        }
    }
    if (entries.isEmpty() && selection.cursor < node.childCount)
        return PublicSchemaPageReply.Rejected(PublicSchemaPageFailure.ENTRY_TOO_LARGE)
    val result = schemaPage(node, selection.cursor, entries)
    return when (val fitting = schemaPageFits(schemaPageDocument(command, result), selection.maxBytes)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> PublicSchemaPageReply.Rejected(fitting.failure)
    }
}

private fun schemaPage(
    node: PublicSchemaNode,
    cursor: Int,
    entries: List<PublicSchemaNode>,
): PublicSchemaPageReply.Page {
    val end = cursor + entries.size
    return PublicSchemaPageReply.Page(
        node,
        cursor,
        entries,
        end == node.childCount,
        end.takeIf { it < node.childCount },
    )
}

private fun schemaPageDocument(
    command: PublicCliCommand.Describe,
    content: PublicSchemaPageReply,
): DescribeDocument<PublicSchemaPageReply> =
    DescribeDocument(command.catalog, command.section, content, command.command)

private fun schemaPageFits(
    value: DescribeDocument<PublicSchemaPageReply>,
    maxBytes: Int,
): Refinement<Unit, PublicSchemaPageFailure> {
    val json = discoveryJson.encodeToString(value)
    for (format in CliOutputFormat.entries) {
        when (val rendered = renderCliOutput(json, format)) {
            is Refinement.Rejected -> return Refinement.Rejected(PublicSchemaPageFailure.OUTPUT_ENCODING)
            is Refinement.Refined ->
                if (rendered.value.toByteArray(Charsets.UTF_8).size + 1 > maxBytes)
                    return Refinement.Rejected(PublicSchemaPageFailure.ENTRY_TOO_LARGE)
        }
    }
    return Refinement.Refined(Unit)
}

private fun schemaNode(pointer: String, value: JsonElement): PublicSchemaNode =
    when (value) {
        is JsonObject -> PublicSchemaNode(pointer, PublicSchemaKind.OBJECT, value.size)
        is JsonArray -> PublicSchemaNode(pointer, PublicSchemaKind.ARRAY, value.size)
        is JsonNull -> PublicSchemaNode(pointer, PublicSchemaKind.NULL, 0, value)
        is JsonPrimitive ->
            PublicSchemaNode(
                pointer,
                when {
                    value.isString -> PublicSchemaKind.STRING
                    value.content == "true" || value.content == "false" -> PublicSchemaKind.BOOLEAN
                    else -> PublicSchemaKind.NUMBER
                },
                0,
                value,
            )
    }
