package kast.repro

internal data class WireValue(val id: String?)
internal data class ClientValue(val id: String?)
internal data class ClientEnvelope(val payload: ClientValue)

internal fun WireValue.toClientValue(): ClientValue = ClientValue(id = id)
internal fun WireValue.toClientEnvelope(): ClientEnvelope = ClientEnvelope(payload = toClientValue())
internal fun convertOne(item: WireValue): ClientEnvelope = item.toClientEnvelope()
