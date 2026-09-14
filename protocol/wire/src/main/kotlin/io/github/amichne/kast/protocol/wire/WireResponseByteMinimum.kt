package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import kotlinx.serialization.Serializable

/** Necessary envelope capacity; passing it does not promise that any result or rejection body will fit. */
@JvmInline
value class WireResponseByteMinimum private constructor(val bytes: Long) {
    fun admits(limit: ReturnedByteLimit): Boolean = limit.value >= bytes

    internal companion object {
        fun forOperation(schema: SchemaIdentity, operation: CanonicalOperation): WireResponseByteMinimum =
            WireResponseByteMinimum(
                wireJson
                    .encodeToString(
                        MandatoryWireEnvelopeIdentity.serializer(),
                        MandatoryWireEnvelopeIdentity(schema.value, operation.id.value),
                    )
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
            )
    }
}

/** Every wire outcome includes these exact identity fields plus a nonempty body. This subset is never emitted. */
@Serializable private data class MandatoryWireEnvelopeIdentity(val schema: String, val operation: String)
