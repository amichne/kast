@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.isNormalizedAbsoluteExactFileImagePath
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
enum class ExactFileImageStatus {
    COMMITTED,
}

@Serializable
class ExactFileImageResult private constructor(
    @DocField(description = "Normalized absolute path whose exact byte image committed.")
    val filePath: ExactFileImagePath,
    @DocField(description = "Closed successful terminal status. Conflicts and unsafe states are typed protocol errors.")
    val status: ExactFileImageStatus,
    @DocField(description = "SHA-256 of the exact preimage bytes accepted by compare-and-swap.")
    val previousSha256: ExactFileImageSha256,
    @DocField(description = "SHA-256 of the exact postimage bytes verified after commit.")
    val resultSha256: ExactFileImageSha256,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(isNormalizedAbsoluteExactFileImagePath(filePath.value)) {
            "Exact file image result path must be normalized and absolute"
        }
    }

    companion object {
        fun committed(
            filePath: String,
            previousSha256: ExactFileImageSha256,
            resultSha256: ExactFileImageSha256,
        ): ExactFileImageResult = ExactFileImageResult(
            filePath = ExactFileImagePath(filePath),
            status = ExactFileImageStatus.COMMITTED,
            previousSha256 = previousSha256,
            resultSha256 = resultSha256,
        )
    }
}
