package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.RawExactFileObservationPath
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RawExactFileObservationResult {
    val filePath: RawExactFileObservationPath

    @Serializable
    @SerialName("ABSENT")
    data class Absent(
        @DocField(description = "Canonical workspace-relative path proven absent by secure observation.")
        override val filePath: RawExactFileObservationPath,
    ) : RawExactFileObservationResult

    @Serializable
    @SerialName("PRESENT")
    data class Present(
        @DocField(description = "Canonical workspace-relative path observed under the exact workspace root.")
        override val filePath: RawExactFileObservationPath,
        @DocField(description = "Immutable exact byte image observed at the requested path.")
        val image: ExactByteImage,
    ) : RawExactFileObservationResult
}
