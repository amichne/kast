package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSelectorFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

internal object CanonicalSelectorCodec {
    fun encodeCandidate(selection: SymbolDiscoverySelection): ProtocolText = token(
        CANDIDATE_PREFIX,
        buildJsonObject {
            putLeaseAndScope(selection.lease, selection.scope)
            put("kind", selection.candidate.kind.name)
            put("name", selection.candidate.name.value)
            putFile(selection.candidate.location.file)
            val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
            put("offset", location.offset.value)
        },
    )

    fun decodeCandidate(document: ProtocolText): SymbolDiscoverySelection? {
        val value = parseToken(document, CANDIDATE_PREFIX) ?: return null
        val lease = value.lease() ?: return null
        val scope = value.scope(lease.workspaceRoot) ?: return null
        val kind = value.enum<SymbolDiscoveryKind>("kind") ?: return null
        if (kind == SymbolDiscoveryKind.FILE || kind == SymbolDiscoveryKind.TEXT) return null
        val name = value.string("name") ?: return null
        val file = value.file(lease.workspaceRoot) ?: return null
        val offset = value.int("offset") ?: return null
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            kind,
            name,
            lease,
            file.nativePath(),
            file.urlValue(),
            offset,
        ).refinedOrNull() ?: return null
        return SymbolDiscoverySelection.restore(lease, scope, candidate).refinedOrNull()
    }

    fun encodeExact(selector: SymbolSelector): ProtocolText = token(
        EXACT_PREFIX,
        buildJsonObject {
            putLeaseAndScope(selector.lease, selector.scope)
            putFile(selector.file)
            put("start", selector.range.startInclusive)
            put("end", selector.range.endExclusive)
            put("name", selector.name.value)
            when (val identity = selector.qualifiedIdentity) {
                is ExactDeclarationQualifiedIdentity.Available ->
                    put("qualifiedIdentity", identity.value)
                ExactDeclarationQualifiedIdentity.Unavailable ->
                    put("qualifiedIdentity", JsonNull)
            }
            put("kind", selector.kind.name)
            put("compilerIdentity", selector.compilerIdentity.value)
            put("fingerprint", selector.fingerprint.value)
        },
    )

    fun decodeExact(document: ProtocolText): SymbolSelector? {
        val value = parseToken(document, EXACT_PREFIX) ?: return null
        val lease = value.lease() ?: return null
        val scope = value.scope(lease.workspaceRoot) ?: return null
        val file = value.file(lease.workspaceRoot) ?: return null
        val start = value.int("start") ?: return null
        val end = value.int("end") ?: return null
        val name = value.string("name") ?: return null
        val qualified = value.nullableString("qualifiedIdentity") ?: return null
        val kind = value.enum<CompilerSymbolKind>("kind") ?: return null
        val compilerIdentity = CompilerSymbolIdentity.parse(
            value.string("compilerIdentity") ?: return null,
        ).refinedOrNull() ?: return null
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file,
            start,
            end,
            name,
            qualified.value,
            kind,
            compilerIdentity,
        ).refinedOrNull() ?: return null
        val fingerprint = SymbolSelectorFingerprint.parse(
            value.string("fingerprint") ?: return null,
        ).refinedOrNull() ?: return null
        return SymbolSelector.restore(lease, scope, evidence, fingerprint).refinedOrNull()
    }
}

private fun token(
    prefix: String,
    value: JsonObject,
): ProtocolText {
    val payload = value.toString().toByteArray(StandardCharsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    val digest = sha256(payload)
    return ProtocolText.parse("$prefix:$VERSION:$encoded:$digest").refinedOrError()
}

private fun parseToken(
    document: ProtocolText,
    prefix: String,
): JsonObject? {
    val parts = document.value.split(':')
    if (parts.size != 4 || parts[0] != prefix || parts[1] != VERSION) return null
    val payload = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return null
    if (sha256(payload) != parts[3]) return null
    return runCatching {
        Json.parseToJsonElement(payload.toString(StandardCharsets.UTF_8)).jsonObject
    }.getOrNull()
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putLeaseAndScope(
    lease: SemanticReadLease,
    scope: SymbolSearchScope,
) {
    put("root", lease.workspaceRoot.value)
    put("generation", lease.generation.value)
    put("sourceKinds", scope.sourceKinds.name)
    put("generatedSources", scope.generatedSources.name)
    when (scope) {
        is SymbolSearchScope.ExactFile -> {
            put("scope", "exact-file")
            put("scopeFile", scope.file.value)
        }
        is SymbolSearchScope.Workspace -> {
            put("scope", "workspace")
            put("libraries", scope.libraries.name)
        }
        else -> error("Public selector tokens support exact-file and workspace scopes only")
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFile(
    file: SymbolDiscoveryFileIdentity,
) {
    when (file) {
        is SymbolDiscoveryFileIdentity.Workspace -> {
            put("fileType", "workspace")
            put("file", file.path.value)
        }
        is SymbolDiscoveryFileIdentity.External -> {
            put("fileType", "external")
            put("file", file.url.value)
        }
    }
}

private fun JsonObject.lease(): SemanticReadLease? {
    val rootPath = path("root") ?: return null
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(rootPath).refinedOrNull() ?: return null
    val generation = EvidenceGeneration.parse(long("generation") ?: return null).refinedOrNull()
                     ?: return null
    return SemanticReadLease(root, generation)
}

private fun JsonObject.scope(root: CanonicalWorkspaceRoot): SymbolSearchScope? {
    val sourceKinds = enum<SymbolSourceKindPolicy>("sourceKinds") ?: return null
    val generated = enum<SymbolGeneratedSourcePolicy>("generatedSources") ?: return null
    return when (string("scope")) {
        "workspace" -> SymbolSearchScope.Workspace(
            sourceKinds,
            generated,
            enum<SymbolLibraryPolicy>("libraries") ?: return null,
        )
        "exact-file" -> {
            val filePath = path("scopeFile") ?: return null
            val file = CanonicalWorkspaceFilePath.fromCanonicalPath(
                root,
                filePath,
            ).refinedOrNull() ?: return null
            SymbolSearchScope.ExactFile(file, sourceKinds, generated)
        }
        else -> null
    }
}

private fun JsonObject.file(root: CanonicalWorkspaceRoot): SymbolDiscoveryFileIdentity? =
    when (string("fileType")) {
        "workspace" -> {
            val filePath = path("file") ?: return null
            val workspacePath = CanonicalWorkspaceFilePath.fromCanonicalPath(
                root,
                filePath,
            ).refinedOrNull() ?: return null
            SymbolDiscoveryFileIdentity.Workspace(workspacePath)
        }
        "external" -> {
            val url = DetachedVirtualFileUrl.parse(string("file") ?: return null).refinedOrNull()
                      ?: return null
            SymbolDiscoveryFileIdentity.External(url)
        }
        else -> null
    }

private data class NullableString(val value: String?)

private fun JsonObject.nullableString(name: String): NullableString? {
    val element = this[name] ?: return null
    return if (element === JsonNull) NullableString(null) else {
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        if (!primitive.isString) return null
        NullableString(primitive.content)
    }
}

private fun JsonObject.string(name: String): String? {
    val primitive = runCatching { getValue(name).jsonPrimitive }.getOrNull() ?: return null
    return primitive.takeIf { it.isString }?.content
}

private fun JsonObject.path(name: String): Path? =
    runCatching { Path.of(string(name) ?: return null) }.getOrNull()

private fun JsonObject.int(name: String): Int? =
    runCatching { getValue(name).jsonPrimitive.content.toInt() }.getOrNull()

private fun JsonObject.long(name: String): Long? =
    runCatching { getValue(name).jsonPrimitive.content.toLong() }.getOrNull()

private inline fun <reified Value : Enum<Value>> JsonObject.enum(name: String): Value? =
    runCatching { enumValueOf<Value>(string(name) ?: return null) }.getOrNull()

private fun SymbolDiscoveryFileIdentity.nativePath(): Path? = when (this) {
    is SymbolDiscoveryFileIdentity.Workspace -> Path.of(path.value)
    is SymbolDiscoveryFileIdentity.External -> null
}

private fun SymbolDiscoveryFileIdentity.urlValue(): String = when (this) {
    is SymbolDiscoveryFileIdentity.Workspace -> Path.of(path.value).toUri().toString()
    is SymbolDiscoveryFileIdentity.External -> url.value
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrError(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Canonical selector token is bounded and non-blank")
}

private const val CANDIDATE_PREFIX = "candidate"
private const val EXACT_PREFIX = "exact"
private const val VERSION = "v1"
