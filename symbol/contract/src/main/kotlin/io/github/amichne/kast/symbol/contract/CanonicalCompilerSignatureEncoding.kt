package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets

internal fun CanonicalCompilerSignature.encodeCanonicalSignature(): String = buildString {
    appendCanonicalField(CANONICAL_SIGNATURE_VERSION)
    when (val signature = this@encodeCanonicalSignature) {
        is CanonicalCompilerSignature.AnonymousObject -> {
            appendCanonicalField("anonymous-object-v1")
            appendLocalAddress(signature.address)
            appendCanonicalFields(signature.supertypes.map(CanonicalCompilerType::value))
        }
        is CanonicalCompilerSignature.LocalFunction -> {
            appendCanonicalField("local-function-v1")
            appendLocalAddress(signature.address)
            appendCallableFacts(signature)
            appendCanonicalField(signature.returnType.value)
        }
        is CanonicalCompilerSignature.LocalProperty -> {
            appendCanonicalField("local-property-v1")
            appendLocalAddress(signature.address)
            appendCanonicalField(signature.returnType.value)
            appendCanonicalField(signature.mutability.name)
        }
        is CanonicalCompilerSignature.Function -> {
            appendCanonicalField(FUNCTION_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
            appendCallableFacts(signature)
        }
        is CanonicalCompilerSignature.Property -> {
            appendCanonicalField(PROPERTY_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
            appendReceiver(signature.receiver)
            appendCanonicalFields(signature.contextReceivers.map(CanonicalCompilerType::value))
            appendCanonicalField(signature.returnType.value)
        }
        is CanonicalCompilerSignature.TypeAlias -> {
            appendCanonicalField(TYPE_ALIAS_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
        }
        is CanonicalCompilerSignature.ClassLike -> {
            appendCanonicalField(CLASS_LIKE_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
        }
    }
}

private fun StringBuilder.appendCallableFacts(signature: CanonicalCompilerCallableSignature) {
    appendReceiver(signature.receiver)
    appendCanonicalFields(signature.contextReceivers.map(CanonicalCompilerType::value))
    appendCanonicalFields(signature.valueParameters.map(CanonicalCompilerType::value))
    appendCanonicalField(signature.typeParameterCount.value.toString())
}

private fun StringBuilder.appendReceiver(receiver: CanonicalCompilerReceiver) {
    when (receiver) {
        CanonicalCompilerReceiver.Absent -> appendCanonicalField(RECEIVER_ABSENT)
        is CanonicalCompilerReceiver.Present -> {
            appendCanonicalField(RECEIVER_PRESENT)
            appendCanonicalField(receiver.type.value)
        }
    }
}

internal fun Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>.requireExactEncoding(
    raw: String,
    cursor: CanonicalFieldCursor,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    when (this) {
        is Refinement.Rejected -> this
        is Refinement.Refined ->
            if (cursor.isExhausted && value.canonicalEncoding().value == raw) {
                this
            } else {
                Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
            }
    }

internal class CanonicalFieldCursor(private val fields: List<String>) {
    private var index: Int = 0

    val isExhausted: Boolean
        get() = index == fields.size

    fun next(): String? = fields.getOrNull(index++)

    fun nextValues(): List<String>? {
        val count = next()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (count > fields.size - index) return null
        return List(count) { next() ?: return null }
    }
}

internal fun decodeCanonicalFields(raw: String): List<String>? {
    val bytes = raw.toByteArray(StandardCharsets.UTF_8)
    val fields = mutableListOf<String>()
    var offset = 0
    while (offset < bytes.size) {
        val field = decodeCanonicalField(bytes, offset) ?: return null
        fields += field.value
        offset = field.endExclusive
    }
    return fields
}

private data class DecodedCanonicalField(val value: String, val endExclusive: Int)

private fun decodeCanonicalField(bytes: ByteArray, offset: Int): DecodedCanonicalField? {
    val separator = canonicalLengthSeparator(bytes, offset) ?: return null
    val length = String(bytes, offset, separator - offset, StandardCharsets.US_ASCII).toIntOrNull() ?: return null
    val fieldStart = separator + 1
    if (length > bytes.size - fieldStart) return null
    val fieldEnd = fieldStart + length
    val fieldBytes = bytes.copyOfRange(fieldStart, fieldEnd)
    val field = String(fieldBytes, StandardCharsets.UTF_8)
    if (!field.toByteArray(StandardCharsets.UTF_8).contentEquals(fieldBytes)) return null
    return DecodedCanonicalField(field, fieldEnd)
}

private fun canonicalLengthSeparator(bytes: ByteArray, start: Int): Int? {
    var offset = start
    while (offset < bytes.size && bytes[offset] != ':'.code.toByte()) {
        if (bytes[offset] !in '0'.code.toByte()..'9'.code.toByte()) return null
        offset += 1
    }
    return if (offset == start || offset >= bytes.size) null else offset
}

internal fun canonicalQualifiedIdentity(raw: String): CanonicalCompilerQualifiedIdentity? =
    raw.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }?.let(::CanonicalCompilerQualifiedIdentity)

internal fun canonicalType(raw: String): CanonicalCompilerType? {
    val canonical = raw.filterNot(Char::isWhitespace)
    return canonical.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }?.let(::CanonicalCompilerType)
}

internal fun List<String>.canonicalTypes(): List<CanonicalCompilerType>? {
    val canonical = map { raw -> canonicalType(raw) ?: return null }
    return java.util.List.copyOf(canonical)
}

internal fun StringBuilder.appendCanonicalFields(values: List<String>) {
    appendCanonicalField(values.size.toString())
    values.forEach(::appendCanonicalField)
}

internal fun StringBuilder.appendCanonicalField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}

internal fun StringBuilder.appendLocalAddress(address: LocalDeclarationAddress) {
    appendCanonicalField(
        when (address.file) {
            is SymbolDiscoveryFileIdentity.Workspace -> "workspace"
            is SymbolDiscoveryFileIdentity.External -> "external"
        }
    )
    appendCanonicalField(address.file.stableValue)
    appendCanonicalField(address.kind.name)
    appendCanonicalField(address.range.startInclusive.toString())
    appendCanonicalField(address.range.endExclusive.toString())
    appendCanonicalField(address.ownerIdentity.value)
    appendCanonicalField(address.ownerRange.startInclusive.toString())
    appendCanonicalField(address.ownerRange.endExclusive.toString())
    appendCanonicalField(address.lexicalOwners.size.toString())
    address.lexicalOwners.forEach {
        appendCanonicalField(it.startInclusive.toString())
        appendCanonicalField(it.endExclusive.toString())
    }
}

internal fun CanonicalFieldCursor.nextLocalAddress(): LocalDeclarationAddress? {
    val file = nextLocalFile() ?: return null
    val rawKind = next() ?: return null
    val kind = LocalDeclarationKind.entries.singleOrNull { it.name == rawKind } ?: return null
    val location = nextRange() ?: return null
    val owner = nextOwnerIdentity() ?: return null
    val enclosing = nextRange() ?: return null
    val count = next()?.toIntOrNull()?.takeIf { it in 0..LocalDeclarationAddress.MAX_OWNER_DEPTH } ?: return null
    val lexical = List(count) { nextRange() ?: return null }
    return LocalDeclarationAddress.create(file, kind, location, owner, enclosing, lexical).refinedLocalOrNull()
}

private fun CanonicalFieldCursor.nextLocalFile(): SymbolDiscoveryFileIdentity? {
    val fileKind = next() ?: return null
    val rawFile = next() ?: return null
    return LocalDeclarationAddress.restoreFile(fileKind, rawFile).refinedLocalOrNull()
}

private fun CanonicalFieldCursor.nextRange(): ExactDeclarationTextRange? {
    val start = next()?.toIntOrNull() ?: return null
    val end = next()?.toIntOrNull() ?: return null
    return ExactDeclarationTextRange.parse(start, end).refinedLocalOrNull()
}

private fun CanonicalFieldCursor.nextOwnerIdentity(): CompilerSymbolIdentity? {
    val raw = next() ?: return null
    return CompilerSymbolIdentity.parse(raw).refinedLocalOrNull()
}

internal fun <V, F> Refinement<V, F>.refinedLocalOrNull(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

internal fun restoreLocalFunction(
    cursor: CanonicalFieldCursor
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
    val address =
        cursor.nextLocalAddress()
            ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val receiver =
        when (cursor.next()) {
            RECEIVER_ABSENT -> null
            RECEIVER_PRESENT ->
                cursor.next()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
            else -> return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
        }
    val contexts =
        cursor.nextValues() ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val parameters =
        cursor.nextValues() ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val count =
        cursor.next()?.toIntOrNull()
            ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val result =
        cursor.next() ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    return CanonicalCompilerSignature.localFunction(address, receiver, contexts, parameters, count, result)
}

internal fun restoreLocalProperty(
    cursor: CanonicalFieldCursor
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
    val address =
        cursor.nextLocalAddress()
            ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val result =
        cursor.next() ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val rawMutability = cursor.next()
    val mutability =
        LocalPropertyMutability.entries.singleOrNull { it.name == rawMutability }
            ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    return CanonicalCompilerSignature.localProperty(address, result, mutability)
}

internal fun restoreAnonymousObject(
    cursor: CanonicalFieldCursor
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
    val address =
        cursor.nextLocalAddress()
            ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    val supertypes =
        cursor.nextValues() ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    return CanonicalCompilerSignature.anonymousObject(address, supertypes)
}
