package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.wire.*
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
    RESPONSE_REJECTED, RESULT_TOO_LARGE,
}

internal sealed interface HostedRequest {
    val root: CanonicalWorkspaceRoot
    data class Describe(override val root: CanonicalWorkspaceRoot) : HostedRequest
    data class Classes(val lookup: HostedClassLookup) : HostedRequest { override val root get() = lookup.root }
    data class Supertype(val selection: HostedSupertypeSelection) : HostedRequest { override val root get() = selection.root }
    sealed interface Read : HostedRequest
    data class Query(override val root: CanonicalWorkspaceRoot, val request: QueryRunRequest) : Read
    data class Discover(override val root: CanonicalWorkspaceRoot, val request: SymbolDiscoverRequest) : Read
    data class Inspect(override val root: CanonicalWorkspaceRoot, val request: SymbolInspectRequest) : Read
    data class Source(override val root: CanonicalWorkspaceRoot, val request: SourceReadRequest) : Read
    data class Relation(override val root: CanonicalWorkspaceRoot, val request: RelationReadRequest) : Read
    data class Traversal(override val root: CanonicalWorkspaceRoot, val request: TraversalRunRequest) : Read
    data class Diagnostic(override val root: CanonicalWorkspaceRoot, val request: DiagnosticCheckRequest) : Read
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
                if (json.has(key) || key !in setOf("type", "root", "name", "file", "offset", "qualifiedName", "document")) return rejected
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
                "QUERY_RUN", "SYMBOL_DISCOVER", "SYMBOL_INSPECT", "SOURCE_READ", "RELATION_READ", "TRAVERSAL_RUN", "DIAGNOSTIC_CHECK" -> {
                    if (json.keySet() != setOf("type", "root", "document")) return rejected
                    val envelope = when (val admitted = WireRequestEnvelope.admit(text("document"))) {
                        is WireRequestAdmission.Admitted -> admitted.request
                        is WireRequestAdmission.Rejected -> return rejected
                    }
                    when (text("type")) {
                        "QUERY_RUN" -> decode(CanonicalOperationWireBindings.queryRun.decodeRequest(envelope)) { HostedRequest.Query(root, it) }
                        "SYMBOL_DISCOVER" -> decode(CanonicalOperationWireBindings.symbolDiscover.decodeRequest(envelope)) { HostedRequest.Discover(root, it) }
                        "SYMBOL_INSPECT" -> decode(CanonicalOperationWireBindings.symbolInspect.decodeRequest(envelope)) { HostedRequest.Inspect(root, it) }
                        "SOURCE_READ" -> decode(CanonicalOperationWireBindings.sourceRead.decodeRequest(envelope)) { HostedRequest.Source(root, it) }
                        "RELATION_READ" -> decode(CanonicalOperationWireBindings.relationRead.decodeRequest(envelope)) { HostedRequest.Relation(root, it) }
                        "TRAVERSAL_RUN" -> decode(CanonicalOperationWireBindings.traversalRun.decodeRequest(envelope)) { HostedRequest.Traversal(root, it) }
                        "DIAGNOSTIC_CHECK" -> decode(CanonicalOperationWireBindings.diagnosticCheck.decodeRequest(envelope)) { HostedRequest.Diagnostic(root, it) }
                        else -> rejected
                    }
                }
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

    private fun <Value> decode(value: WireDecoding<Value>, request: (Value) -> HostedRequest.Read): Refinement<HostedRequest, HostedEndpointFailure> = when (value) {
        is WireDecoding.Decoded -> Refinement.Refined(request(value.value))
        is WireDecoding.Rejected -> Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
    }

    fun rejected(failure: HostedEndpointFailure): String = Gson().toJson(mapOf("type" to "HOST_REJECTED", "failure" to failure.name))
}

/** Four-byte network-order length followed by bounded strict UTF-8 JSON; no line ambiguity. */
internal object HostedFrames {
    fun read(input: InputStream, limits: ReadLimits = ReadLimits.Default): Refinement<String, HostedEndpointFailure> = try {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        if (length !in 1..limits[ReadLimitParameter.HOST_REQUEST_BYTES].value) Refinement.Rejected(HostedEndpointFailure.REQUEST_TOO_LARGE)
        else {
            val bytes = stream.readNBytes(length)
            if (bytes.size != length) Refinement.Rejected(HostedEndpointFailure.REQUEST_INCOMPLETE)
            else Refinement.Refined(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        }
    } catch (_: java.io.EOFException) { Refinement.Rejected(HostedEndpointFailure.REQUEST_INCOMPLETE) }
    catch (_: java.nio.charset.CharacterCodingException) { Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST) }
    catch (_: IOException) { Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE) }

    fun write(output: OutputStream, value: String, limits: ReadLimits = ReadLimits.Default): Refinement<Unit, HostedEndpointFailure> {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value) return Refinement.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
        return Refinement.Refined(Unit)
    }
}
