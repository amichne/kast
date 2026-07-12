package io.github.amichne.kast.shared.proofloss.model

enum class CallableKind { FUNCTION }
enum class CallableRole { PREDICATE, MATERIALIZER, BOUNDARY }

@JvmInline
value class CallableIdKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<CallableIdKey> = parseText(raw, ::CallableIdKey)
    }
}

@JvmInline
value class KotlinTypeKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<KotlinTypeKey> = parseText(raw, ::KotlinTypeKey)
    }
}

data class CallableKey(
    val callableId: CallableIdKey,
    val kind: CallableKind,
    val receiverType: KotlinTypeKey?,
    val contextParameterTypes: List<KotlinTypeKey>,
    val valueParameterTypes: List<KotlinTypeKey>,
    val genericArity: Int,
) : Comparable<CallableKey> {
    init {
        require(genericArity >= 0)
    }

    override fun compareTo(other: CallableKey): Int = stableText().compareTo(other.stableText())

    fun stableText(): String = buildString {
        append(callableId.value).append('|').append(kind).append('|')
        append(receiverType?.value ?: "-").append('|')
        append(contextParameterTypes.joinToString(",") { it.value }).append('|')
        append(valueParameterTypes.joinToString(",") { it.value }).append('|').append(genericArity)
    }
}
