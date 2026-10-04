package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import java.io.DataInputStream

/** Existing wire framing, with only its input stream and evidence sink supplied by the effect owner. */
internal fun readExistingIdeResponse(
    input: DataInputStream,
    limits: ReadLimits,
    observer: ExistingIdeResponseObserver,
    decode: (ByteArray) -> ExistingIdeDecodedResponse,
): ExistingIdeExchange {
    val limit = limits[ReadLimitParameter.HOST_RESPONSE_BYTES]
    val size = input.readInt()
    val frame =
        when {
            size < 1 -> ExistingIdeResponseFrameOutcome.NONPOSITIVE
            size > limit.value -> ExistingIdeResponseFrameOutcome.OVER_LIMIT
            else -> ExistingIdeResponseFrameOutcome.ADMITTED
        }
    observer.record(ExistingIdeResponseEvidence.Frame(size, limit.value, frame))
    if (frame != ExistingIdeResponseFrameOutcome.ADMITTED)
        return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    val bytes = input.readNBytes(size)
    val body =
        if (bytes.size == size) ExistingIdeResponseBodyOutcome.DRAINED else ExistingIdeResponseBodyOutcome.TRUNCATED
    observer.record(ExistingIdeResponseEvidence.Body(size, bytes.size, body))
    if (body == ExistingIdeResponseBodyOutcome.TRUNCATED)
        return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    val decoded = decode(bytes)
    observer.record(decoded.evidence)
    return decoded.exchange
}
