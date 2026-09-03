package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

private const val SOURCE_SELECTOR_TOKEN_PREFIX = "source-selector-v1"
private const val SOURCE_SELECTOR_PAYLOAD_VERSION = "source-selector-payload-v1"
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
                parts[0] != SOURCE_SELECTOR_TOKEN_PREFIX ||
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
        val fields = buildList {
            add(SOURCE_SELECTOR_PAYLOAD_VERSION)
            add(snapshot.lease.workspaceRoot.value)
            add(snapshot.lease.generation.value.toString())
            add(snapshot.sourceState.value)
            add(snapshot.file.path.value)
            add(snapshot.textIdentity.value)
            add(snapshot.length.value.toString())
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
        val raw = "$SOURCE_SELECTOR_TOKEN_PREFIX:$encoded:${sha256(payload)}"
        return when (val parsed = SourceSelectorToken.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("Issued source selector token violated its contract")
        }
    }

    fun decode(
        token: SourceSelectorToken,
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
        return decodePayload(payload)
    }

    private fun decodePayload(
        payload: String,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> {
        val fields = SourceSelectorFieldReader(payload)
        if (fields.read() != SOURCE_SELECTOR_PAYLOAD_VERSION) {
            return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
        }
        val rootText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)
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
        val depthText = fields.read()
            ?: return Refinement.Rejected(SourceSelectorTokenFailure.MALFORMED_PAYLOAD)

        val snapshot = admitSnapshot(
            rootText,
            generationText,
            sourceStateText,
            fileText,
            textIdentityText,
            lengthText,
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
    val generation = when (
        val parsed = rawGeneration.toCanonicalLongOrNull()?.let(EvidenceGeneration::parse)
    ) {
        is Refinement.Refined -> parsed.value
        else -> return null
    }
    val sourceState = when (val parsed = WorkspaceStateIdentity.parse(rawSourceState)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
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
        SemanticReadLease(root, generation),
        sourceState,
        file,
        textIdentity,
        length,
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
