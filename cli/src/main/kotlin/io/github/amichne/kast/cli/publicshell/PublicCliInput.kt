package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.rpc.decodeToolRpcInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal sealed interface PublicCliInput {
    data class Lowered(val arguments: JsonObject) : PublicCliInput

    data class File(val path: String) : PublicCliInput

    data object Stdin : PublicCliInput
}

internal enum class PublicInputReadFailure {
    IO,
    INVALID,
    TOO_LARGE,
}

/** A relative request file resolves against the caller's [directory], never the reading process's own cwd. */
internal fun readPublicCliInput(
    input: PublicCliInput,
    stdin: InputStream,
    identity: PublicToolIdentity,
    directory: Path,
): Refinement<JsonObject, PublicInputReadFailure> =
    try {
        when (input) {
            is PublicCliInput.Lowered -> Refinement.Refined(input.arguments)
            is PublicCliInput.File ->
                Files.newInputStream(directory.resolve(input.path)).use { readPublicArguments(it, identity) }
            PublicCliInput.Stdin -> readPublicArguments(stdin, identity)
        }
    } catch (_: IOException) {
        Refinement.Rejected(PublicInputReadFailure.IO)
    } catch (_: InvalidPathException) {
        Refinement.Rejected(PublicInputReadFailure.INVALID)
    }

// Keep the entire action opaque until the selected public schema admits it, including unknown fields.
@Serializable private data class ShellRunArguments(val request: JsonObject)

private fun readPublicArguments(
    input: InputStream,
    identity: PublicToolIdentity,
): Refinement<JsonObject, PublicInputReadFailure> {
    val bytes = input.readNBytes(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)
    if (bytes.size > MAXIMUM_PROTOCOL_TEXT_LENGTH) return Refinement.Rejected(PublicInputReadFailure.TOO_LARGE)
    val text =
        when (val decoded = decodeToolRpcInput(bytes)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(PublicInputReadFailure.INVALID)
        }
    return try {
        val raw = Json.decodeFromString<JsonObject>(text)
        val arguments =
            if (identity == PublicToolIdentity.QUERY_SYMBOLS && raw["type"] == JsonPrimitive("RUN"))
                Json.encodeToJsonElement(ShellRunArguments.serializer(), ShellRunArguments(raw)).jsonObject
            else raw
        Refinement.Refined(arguments)
    } catch (_: SerializationException) {
        Refinement.Rejected(PublicInputReadFailure.INVALID)
    }
}
