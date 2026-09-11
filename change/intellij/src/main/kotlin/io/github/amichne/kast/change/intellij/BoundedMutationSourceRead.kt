package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import java.io.InputStream

internal sealed interface BoundedMutationSourceRead {
    class Complete(val bytes: ByteArray) : BoundedMutationSourceRead

    data object LimitExceeded : BoundedMutationSourceRead
}

/** Bounds the actual read, including a file growing after its metadata was observed. */
internal fun readBoundedMutationSource(input: InputStream, limits: ReadLimits): BoundedMutationSourceRead {
    val maximum = limits[ReadLimitParameter.SOURCE_RETURNED_BYTES].value
    val bytes = input.readNBytes(maximum + 1)
    return if (bytes.size > maximum) BoundedMutationSourceRead.LimitExceeded
    else BoundedMutationSourceRead.Complete(bytes)
}
