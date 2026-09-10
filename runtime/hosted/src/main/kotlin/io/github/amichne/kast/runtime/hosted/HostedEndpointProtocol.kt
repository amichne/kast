package io.github.amichne.kast.runtime.hosted

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedClassLookup
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedKotlinSelection
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSupertypeSelection
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQualifiedClassSelection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

enum class HostedEndpointFailure {
    INVALID_REQUEST, REQUEST_TOO_LARGE, REQUEST_INCOMPLETE, IO_UNAVAILABLE, DEADLINE_EXCEEDED,
    WRONG_ROOT, OWNERSHIP_CONFLICT, DIRECTORY_REJECTED, SOCKET_UNAVAILABLE, PLATFORM_UNAVAILABLE,
}

internal sealed interface HostedRequest {
    val root: CanonicalWorkspaceRoot
    data class Describe(override val root: CanonicalWorkspaceRoot) : HostedRequest
    data class Classes(val lookup: HostedClassLookup) : HostedRequest { override val root get() = lookup.root }
    data class Supertype(val selection: HostedSupertypeSelection) : HostedRequest { override val root get() = selection.root }
}

internal object HostedRequests {
    fun decode(raw: String): Refinement<HostedRequest, HostedEndpointFailure> {
        val rejected = Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
        try {
            val reader = JsonReader(java.io.StringReader(raw)).apply { isLenient = false }
            val json = JsonObject()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (json.has(key) || key !in setOf("type", "root", "name", "file", "offset", "qualifiedName")) return rejected
                if (key == "offset") {
                    if (reader.peek() != JsonToken.NUMBER) return rejected
                    val token = reader.nextString()
                    if (!token.matches(Regex("0|[1-9][0-9]{0,9}"))) return rejected
                    json.addProperty(key, token.toInt())
                } else {
                    if (reader.peek() != JsonToken.STRING) return rejected
                    json.addProperty(key, reader.nextString())
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) return rejected
            fun text(key: String): String {
                val value = json.get(key)
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                return value.asString
            }
            val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(text("root")))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected
            }
            return when (text("type")) {
                "DESCRIBE" -> if (json.keySet() == setOf("type", "root")) Refinement.Refined(HostedRequest.Describe(root)) else rejected
                "CLASS_LOOKUP" -> {
                    if (json.keySet() != setOf("type", "root", "name")) return rejected
                    when (val lookup = HostedClassLookup.parse(root, text("name"))) {
                        is Refinement.Refined -> Refinement.Refined(HostedRequest.Classes(lookup.value))
                        is Refinement.Rejected -> rejected
                    }
                }
                "DIRECT_SUPERTYPE" -> {
                    if (json.keySet() == setOf("type", "root", "qualifiedName")) {
                        return when (val selected = HostedQualifiedClassSelection.parse(root, text("qualifiedName"))) {
                            is Refinement.Refined -> Refinement.Refined(HostedRequest.Supertype(selected.value))
                            is Refinement.Rejected -> rejected
                        }
                    }
                    if (json.keySet() != setOf("type", "root", "file", "offset")) return rejected
                    val offset = json.get("offset").asJsonPrimitive
                    if (!offset.isNumber || !offset.asString.matches(Regex("0|[1-9][0-9]{0,9}"))) return rejected
                    when (val selected = HostedKotlinSelection.parse(root, text("file"), offset.asString.toInt())) {
                        is Refinement.Refined -> Refinement.Refined(HostedRequest.Supertype(selected.value))
                        is Refinement.Rejected -> rejected
                    }
                }
                else -> rejected
            }
        } catch (_: RuntimeException) { return rejected }
        catch (_: IOException) { return rejected }
    }

    fun rejected(failure: HostedEndpointFailure): String = Gson().toJson(mapOf("type" to "HOST_REJECTED", "failure" to failure.name))
}

/** Four-byte network-order length followed by bounded strict UTF-8 JSON; no line ambiguity. */
internal object HostedFrames {
    fun read(input: InputStream): Refinement<String, HostedEndpointFailure> = try {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        if (length !in 1..16_384) Refinement.Rejected(HostedEndpointFailure.REQUEST_TOO_LARGE)
        else {
            val bytes = stream.readNBytes(length)
            if (bytes.size != length) Refinement.Rejected(HostedEndpointFailure.REQUEST_INCOMPLETE)
            else Refinement.Refined(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        }
    } catch (_: java.io.EOFException) { Refinement.Rejected(HostedEndpointFailure.REQUEST_INCOMPLETE) }
    catch (_: java.nio.charset.CharacterCodingException) { Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST) }
    catch (_: IOException) { Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE) }

    fun write(output: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65_536)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
}
