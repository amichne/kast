package kast.repro

internal data class WireValue(val id: String?)
internal data class ClientValue(val id: String?)
internal data class ClientEnvelope(val payload: ClientValue)

internal fun WireValue.toClientValue(): ClientValue = ClientValue(id = id)
internal fun WireValue.toClientEnvelope(): ClientEnvelope = ClientEnvelope(payload = toClientValue())
private fun <T, R> List<T>.map(transform: (T) -> R): List<R> = emptyList()
internal fun convert(items: List<WireValue>): List<ClientEnvelope> = items.map { it.toClientEnvelope() }
