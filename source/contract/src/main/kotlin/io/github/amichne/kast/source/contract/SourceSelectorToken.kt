package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeSnapshot
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

private const val SOURCE_SELECTOR_TOKEN_PREFIX = "source-selector-v1"
private const val SOURCE_SELECTOR_PAYLOAD_VERSION = "source-selector-payload-v1"
private const val LIVE_SOURCE_SELECTOR_TOKEN_PREFIX = "source-selector-v2"
private const val LIVE_SOURCE_SELECTOR_PAYLOAD_VERSION = "source-selector-payload-v2"
private const val SOURCE_SELECTOR_TOKEN_PARTS = 3
private const val SOURCE_SELECTOR_TOKEN_DIGEST_LENGTH = 64
private const val MAX_SOURCE_SELECTOR_TOKEN_LENGTH = 1_048_576
private const val MAX_SOURCE_SELECTOR_DEPTH = 64

enum class SourceSelectorTokenFailure {
    TOKEN_TOO_LONG,
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
    MALFORMED_PAYLOAD,
    SNAPSHOT_REJECTED,
    SELECTOR_REJECTED,
    SELECTOR_TOO_DEEP,
}

/** Syntactically admitted outer source-selector token. */
@JvmInline
value class SourceSelectorToken private constructor(
    val value: String,
) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<SourceSelectorToken, SourceSelectorTokenFailure> {
            if (raw.length > MAX_SOURCE_SELECTOR_TOKEN_LENGTH) {
                return Refinement.Rejected(SourceSelectorTokenFailure.TOKEN_TOO_LONG)
            }
            val parts = raw.split(':')
            if (
                parts.size != SOURCE_SELECTOR_TOKEN_PARTS ||
                parts[0] !in setOf(SOURCE_SELECTOR_TOKEN_PREFIX, LIVE_SOURCE_SELECTOR_TOKEN_PREFIX) ||
                parts[1].isEmpty() ||
                parts[2].length != SOURCE_SELECTOR_TOKEN_DIGEST_LENGTH ||
                parts[2].any { it !in '0'..'9' && it !in 'a'..'f' }
            ) {
                return Refinement.Rejected(SourceSelectorTokenFailure.INVALID_TOKEN_STRUCTURE)
            }
            return Refinement.Refined(SourceSelectorToken(raw))
        }
    }
}

/** Strict versioned codec for the complete hierarchical source-selector proof. */
object SourceSelectorTokenCodec {
    fun encode(selector: SourceSelector): SourceSelectorToken {
        val hierarchy = selector.hierarchy()
        check(hierarchy.size <= MAX_SOURCE_SELECTOR_DEPTH) {
            "Source selector hierarchy exceeds contract depth"
        }
        val snapshot = selector.snapshot
        val extended = snapshot.context is SourceReadContext.Live || snapshot.readScope is SourceReadScope.Constrained
        val fields = buildList {
            add(if (extended) LIVE_SOURCE_SELECTOR_PAYLOAD_VERSION else SOURCE_SELECTOR_PAYLOAD_VERSION)
            if (extended) add(if (snapshot.context is SourceReadContext.Live) "live" else "published")
            add(snapshot.lease.workspaceRoot.value)
            add(snapshot.lease.identity.revisionKey.value)
            add(when (val context = snapshot.context) {
                is SourceReadContext.Published -> context.sourceState.value
                is SourceReadContext.Live -> context.lease.reference.contentView.name
            })
            add(snapshot.file.path.value)
            add(snapshot.textIdentity.value)
            add(snapshot.length.value.toString())
            if (extended) add(encodeReadScope(snapshot.readScope))
            add(hierarchy.size.toString())
            hierarchy.forEach { current ->
                when (current) {
                    is SourceSelector.RootRegion -> {
                        add("root")
                        add(current.kind.name)
                        add(current.range.startInclusive.value.toString())
                        add(current.range.endExclusive.value.toString())
                        add("unavailable")
                        add("")
                        add(current.fingerprint.value)
                    }
                    is SourceSelector.NestedRegion -> {
                        add("nested")
                        add(current.kind.name)
                        add(current.range.startInclusive.value.toString())
                        add(current.range.endExclusive.value.toString())
                        add("unavailable")
                        add("")
                        add(current.fingerprint.value)
                    }
                    is SourceSelector.Entity -> {
                        add("entity")
                        add(current.kind.name)
                        add(current.range.startInclusive.value.toString())
                        add(current.range.endExclusive.value.toString())
                        when (val name = current.name) {
                            SourceEntityName.Unavailable -> {
                                add("unavailable")
                                add("")
                            }
                            is SourceEntityName.Present -> {
                                add("present")
                                add(name.value)
                            }
                        }
                        add(current.fingerprint.value)
                    }
                }
            }
        }
        val payload = fields.joinToString(separator = "") { field ->
            "${field.length}:$field"
        }.toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val prefix = if (extended) LIVE_SOURCE_SELECTOR_TOKEN_PREFIX else SOURCE_SELECTOR_TOKEN_PREFIX
        val raw = "$prefix:$encoded:${sha256(payload)}"
        return when (val parsed = SourceSelectorToken.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("Issued source selector token violated its contract")
        }
    }

    fun decode(token: SourceSelectorToken): Refinement<SourceSelector, SourceSelectorTokenFailure> =
        decodeWithAuthority(token, LiveSourceRestoration.Unavailable)

    /** The owning caller supplies current imported model evidence for modeled scope restoration. */
    fun decode(
        token: SourceSelectorToken,
        model: WorkspaceSearchScopeModel,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> =
        decodeWithAuthority(token, LiveSourceRestoration.Unavailable, SourceScopeRestoration.Current(model))

    /** The original owner must freshly admit this authority before reference restoration. */
    fun decode(
        token: SourceSelectorToken,
        admitted: LiveSemanticReadAuthority,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> =
        decodeWithAuthority(token, LiveSourceRestoration.Admitted(admitted))

    /** Live references retain both original-owner freshness and current imported scope ownership. */
    fun decode(
        token: SourceSelectorToken,
        admitted: LiveSemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> =
        decodeWithAuthority(token, LiveSourceRestoration.Admitted(admitted), SourceScopeRestoration.Current(model))

    private fun decodeWithAuthority(
        token: SourceSelectorToken,
        live: LiveSourceRestoration,
        scopes: SourceScopeRestoration = SourceScopeRestoration.Unavailable,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> {
        val parts = token.value.split(':')
        val payloadBytes = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(SourceSelectorTokenFailure.INVALID_PAYLOAD_ENCODING)
        }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) != parts[1]) {
            return Refinement.Rejected(SourceSelectorTokenFailure.INVALID_PAYLOAD_ENCODING)
        }
        if (sha256(payloadBytes) != parts[2]) {
            return Refinement.Rejected(SourceSelectorTokenFailure.PAYLOAD_DIGEST_MISMATCH)
        }
        val payload = try {
            payloadBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return Refinement.Rejected(SourceSelectorTokenFailure.INVALID_PAYLOAD_ENCODING)
        }
        return decodePayload(payload, parts[0], live, scopes)
    }

    private fun decodePayload(
        payload: String,
        prefix: String,
        live: LiveSourceRestoration,
        scopes: SourceScopeRestoration,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> {
        val fields = SourceSelectorFieldReader(payload)
        val version = fields.read()
        if ((prefix == SOURCE_SELECTOR_TOKEN_PREFIX && version != SOURCE_SELECTOR_PAYLOAD_VERSION) ||
            (prefix == LIVE_SOURCE_SELECTOR_TOKEN_PREFIX && version != LIVE_SOURCE_SELECTOR_PAYLOAD_VERSION)) {
            return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        }
        val extended = version == LIVE_SOURCE_SELECTOR_PAYLOAD_VERSION
        val authorityKind = if (extended) fields.read() else "published"
        if (authorityKind !in setOf("published", "live")) return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val rootText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        if (scopes is SourceScopeRestoration.Current && scopes.model.workspaceRoot.value != rootText) {
            return Refinement.Rejected(SourceSelectorTokenFailure.SNAPSHOT_REJECTED)
        }
        val generationText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val sourceStateText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val fileText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val textIdentityText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val lengthText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        val readScope = if (extended) {
            decodeReadScope(rootText, fields.read() ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD), scopes)
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.SNAPSHOT_REJECTED)
        } else SourceReadScope.ExactFile
        val depthText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)

        val snapshot = admitSnapshot(
            rootText,
            generationText,
            sourceStateText,
            fileText,
            textIdentityText,
            lengthText,
            if (authorityKind == "live") live else LiveSourceRestoration.Unavailable,
            authorityKind == "live",
            readScope,
        ) ?: return Refinement.Rejected(SourceSelectorTokenFailure.SNAPSHOT_REJECTED)
        val depth = depthText.toCanonicalIntOrNull()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        if (depth < 1) {
            return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        }
        if (depth > MAX_SOURCE_SELECTOR_DEPTH) {
            return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_TOO_DEEP)
        }

        var current: SourceSelector? = null
        repeat(depth) { index ->
            val variant = fields.read()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val kind = fields.read()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val start = fields.read()?.toCanonicalIntOrNull()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val end = fields.read()?.toCanonicalIntOrNull()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val nameState = fields.read()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val nameValue = fields.read()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val fingerprintText = fields.read()
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
            val range = admitRange(snapshot, start, end)
                ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
            val fingerprint = when (
                val parsed = SourceSelectorFingerprint.parse(fingerprintText)
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
            }
            current = when (variant) {
                "root" -> {
                    if (index != 0 || current != null || nameState != "unavailable" || nameValue.isNotEmpty()) {
                        return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                    val regionKind = enumValueOrNull<SourceRegionKind>(kind)
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    when (val restored = SourceSelector.restoreRoot(range, regionKind, fingerprint)) {
                        is Refinement.Refined -> restored.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                }
                "nested" -> {
                    val parent = current
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    if (nameState != "unavailable" || nameValue.isNotEmpty()) {
                        return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                    val regionKind = enumValueOrNull<SourceRegionKind>(kind)
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    when (
                        val restored = SourceSelector.restoreNested(
                            parent,
                            range,
                            regionKind,
                            fingerprint,
                        )
                    ) {
                        is Refinement.Refined -> restored.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                }
                "entity" -> {
                    val parent = current
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    val entityKind = enumValueOrNull<SourceEntityKind>(kind)
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    val name = admitName(nameState, nameValue)
                        ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    val nonEmpty = when (val admitted = NonEmptySourceRange.create(range)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                    when (
                        val restored = SourceSelector.restoreEntity(
                            parent,
                            nonEmpty,
                            entityKind,
                            name,
                            fingerprint,
                        )
                    ) {
                        is Refinement.Refined -> restored.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
                    }
                }
                else -> return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
            }
        }
        if (!fields.exhausted) {
            return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        }
        return Refinement.Refined(
            current ?: return Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED),
        )
    }
}

private fun SourceSelector.hierarchy(): List<SourceSelector> {
    val reversed = ArrayList<SourceSelector>()
    var current: SourceSelector? = this
    while (current != null) {
        reversed += current
        current = when (current) {
            is SourceSelector.RootRegion -> null
            is SourceSelector.NestedRegion -> current.parent
            is SourceSelector.Entity -> current.parent
        }
    }
    reversed.reverse()
    return reversed
}

private fun admitSnapshot(
    rawRoot: String,
    rawGeneration: String,
    rawSourceState: String,
    rawFile: String,
    rawTextIdentity: String,
    rawLength: String,
    live: LiveSourceRestoration,
    isLive: Boolean,
    readScope: SourceReadScope,
): SourceSnapshot? {
    val rootPath = try {
        Path.of(rawRoot)
    } catch (_: InvalidPathException) {
        return null
    }
    val root = when (val parsed = CanonicalWorkspaceRoot.fromCanonicalPath(rootPath)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val context = if (isLive) {
        val admitted = when (live) {
            LiveSourceRestoration.Unavailable -> return null
            is LiveSourceRestoration.Admitted -> live.authority
        }
        if (admitted.workspaceRoot != root || admitted.identity.revisionKey.value != rawGeneration ||
            admitted.reference.contentView.name != rawSourceState) return null
        SourceReadContext.Live(admitted)
    } else {
        val generation = when (val parsed = rawGeneration.toCanonicalLongOrNull()?.let(EvidenceGeneration::parse)) {
            is Refinement.Refined -> parsed.value
            else -> return null
        }
        val sourceState = when (val parsed = WorkspaceStateIdentity.parse(rawSourceState)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
        SourceReadContext.Published(SemanticReadLease(root, generation), sourceState)
    }
    val filePath = try {
        Path.of(rawFile)
    } catch (_: InvalidPathException) {
        return null
    }
    val file = when (val parsed = CanonicalWorkspaceFilePath.fromCanonicalPath(root, filePath)) {
        is Refinement.Refined -> SymbolDiscoveryFileIdentity.Workspace(parsed.value)
        is Refinement.Rejected -> return null
    }
    val textIdentity = when (val parsed = SourceTextIdentity.parse(rawTextIdentity)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val length = when (
        val parsed = rawLength.toCanonicalIntOrNull()?.let(Utf16CodeUnitCount::parse)
    ) {
        is Refinement.Refined -> parsed.value
        else -> return null
    }
    return SourceSnapshot.create(
        context,
        file,
        textIdentity,
        length,
        readScope,
    )
}

private fun admitRange(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange? {
    val startOffset = when (val parsed = Utf16CodeUnitOffset.parse(start)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val endOffset = when (val parsed = Utf16CodeUnitOffset.parse(end)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    return when (val parsed = SourceRange.create(snapshot, startOffset, endOffset)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> null
    }
}

private fun admitName(state: String, value: String): SourceEntityName? = when (state) {
    "unavailable" -> if (value.isEmpty()) SourceEntityName.Unavailable else null
    "present" -> when (val parsed = SourceEntityName.present(value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> null
    }
    else -> null
}

private class SourceSelectorFieldReader(private val payload: String) {
    private var cursor: Int = 0

    val exhausted: Boolean
        get() = cursor == payload.length

    fun read(): String? {
        if (cursor >= payload.length) return null
        val lengthStart = cursor
        while (cursor < payload.length && payload[cursor].isDigit()) cursor += 1
        if (cursor == lengthStart || cursor >= payload.length || payload[cursor] != ':') return null
        val lengthText = payload.substring(lengthStart, cursor)
        val length = lengthText.toCanonicalIntOrNull() ?: return null
        cursor += 1
        val end = cursor.toLong() + length.toLong()
        if (end > payload.length.toLong()) return null
        val value = payload.substring(cursor, end.toInt())
        cursor = end.toInt()
        return value
    }
}

private fun String.toCanonicalIntOrNull(): Int? =
    if (isEmpty() || (length > 1 && first() == '0') || any { !it.isDigit() }) null else toIntOrNull()

private fun String.toCanonicalLongOrNull(): Long? =
    if (isEmpty() || (length > 1 && first() == '0') || any { !it.isDigit() }) null else toLongOrNull()

private inline fun <reified Value : Enum<Value>> enumValueOrNull(raw: String): Value? =
    enumValues<Value>().singleOrNull { it.name == raw }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private sealed interface LiveSourceRestoration {
    data object Unavailable : LiveSourceRestoration
    data class Admitted(val authority: LiveSemanticReadAuthority) : LiveSourceRestoration
}

private sealed interface SourceScopeRestoration {
    data object Unavailable : SourceScopeRestoration
    data class Current(val model: WorkspaceSearchScopeModel) : SourceScopeRestoration
}

private fun encodeReadScope(scope: SourceReadScope): String = when (scope) {
    SourceReadScope.ExactFile -> ""
    is SourceReadScope.Constrained -> {
        val captured = SymbolSearchScope.snapshot(scope.scope)
        val fields = listOf(captured.kind.name, captured.primary ?: "", captured.secondary ?: "",
            captured.sourceKinds.name, captured.generatedSources.name, captured.libraries?.name ?: "") +
            scope.constraints.fingerprintFields()
        fields.joinToString("") { "${it.length}:$it" }
    }
}

private fun decodeReadScope(rawRoot: String, raw: String, scopes: SourceScopeRestoration): SourceReadScope? {
    if (raw.isEmpty()) return SourceReadScope.ExactFile
    val fields = SourceSelectorFieldReader(raw)
    val kind = fields.read()?.let { enumValueOrNull<SymbolSearchScopeKind>(it) } ?: return null
    val primary = fields.read() ?: return null
    val secondary = fields.read() ?: return null
    val sourceKinds = fields.read()?.let { enumValueOrNull<SymbolSourceKindPolicy>(it) } ?: return null
    val generated = fields.read()?.let { enumValueOrNull<SymbolGeneratedSourcePolicy>(it) } ?: return null
    val libraries = fields.read() ?: return null
    val libraryPolicy = if (libraries.isEmpty()) null else enumValueOrNull<SymbolLibraryPolicy>(libraries) ?: return null
    val captured = SymbolSearchScopeSnapshot(
        kind, primary.ifEmpty { null }, secondary.ifEmpty { null }, sourceKinds, generated, libraryPolicy,
    )
    val scope = when (scopes) {
        is SourceScopeRestoration.Current -> when (val restored = SymbolSearchScope.restore(scopes.model, captured)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return null
        }
        SourceScopeRestoration.Unavailable -> when (kind) {
        SymbolSearchScopeKind.WORKSPACE -> SymbolSearchScope.Workspace(sourceKinds, generated, libraryPolicy ?: return null)
        SymbolSearchScopeKind.EXACT_FILE -> {
            val rootPath = try { Path.of(rawRoot) } catch (_: InvalidPathException) { return null }
            val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(rootPath)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return null
            }
            val filePath = try { Path.of(primary) } catch (_: InvalidPathException) { return null }
            val file = when (val admitted = CanonicalWorkspaceFilePath.fromCanonicalPath(root, filePath)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return null
            }
            SymbolSearchScope.ExactFile(file, sourceKinds, generated)
        }
        // These scopes require a current imported owner, not reconstructed model evidence.
        SymbolSearchScopeKind.MODULE, SymbolSearchScopeKind.SOURCE_SET, SymbolSearchScopeKind.GRADLE_PROJECT -> return null
        }
    }
    if (SymbolSearchScope.snapshot(scope) != captured) return null
    val constraints = if (fields.exhausted) SymbolDiscoveryConstraints.None else decodeConstraints(fields) ?: return null
    if (!fields.exhausted) return null
    val result = SourceReadScope.Constrained(scope, constraints)
    return result.takeIf { encodeReadScope(it) == raw }
}

private fun decodeConstraints(fields: SourceSelectorFieldReader): SymbolDiscoveryConstraints? {
    if (fields.read() != "discovery-constraints-v1") return null
    val directoryText = fields.read() ?: return null
    val directoryContainment = fields.read() ?: return null
    val packageText = fields.read() ?: return null
    val packageContainment = fields.read() ?: return null
    val directory = if (directoryText.isEmpty()) {
        if (directoryContainment.isNotEmpty()) return null
        null
    } else {
        val name = when (val parsed = SymbolDiscoveryDirectory.parse(directoryText)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
        SymbolDiscoveryDirectoryConstraint(name, enumValueOrNull(directoryContainment) ?: return null)
    }
    val packageName = if (packageText.isEmpty()) {
        if (packageContainment.isNotEmpty()) return null
        null
    } else {
        val name = when (val parsed = SymbolDiscoveryPackage.parse(packageText)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
        SymbolDiscoveryPackageConstraint(name, enumValueOrNull(packageContainment) ?: return null)
    }
    val kindCount = fields.read()?.toCanonicalIntOrNull() ?: return null
    if (kindCount > CompilerSymbolKind.entries.size) return null
    val kinds = buildSet {
        repeat(kindCount) { add(fields.read()?.let { enumValueOrNull<CompilerSymbolKind>(it) } ?: return null) }
    }
    val declarationKinds = if (kindCount == 0) null else when (val parsed = SymbolDiscoveryDeclarationKinds.from(kinds)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val sourceSets = when (fields.read()) {
        "all-source-sets" -> SymbolDiscoverySourceSets.All
        "exact-source-sets" -> {
            val count = fields.read()?.toCanonicalIntOrNull() ?: return null
            if (count !in 1..MAX_SOURCE_SELECTOR_TOKEN_LENGTH) return null
            val names = buildSet {
                repeat(count) {
                    when (val name = io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName.parse(fields.read() ?: return null)) {
                        is Refinement.Refined -> add(name.value)
                        is Refinement.Rejected -> return null
                    }
                }
            }
            when (val parsed = SymbolDiscoverySourceSets.Exact.from(names)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return null
            }
        }
        else -> return null
    }
    return SymbolDiscoveryConstraints(directory, packageName, declarationKinds, sourceSets)
}
