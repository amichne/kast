package io.github.amichne.kast.shared.proofloss.model

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
