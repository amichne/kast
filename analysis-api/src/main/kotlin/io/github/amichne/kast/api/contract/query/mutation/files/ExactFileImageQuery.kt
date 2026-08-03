@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.ExactFileImageBase64
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.contract.isNormalizedAbsoluteExactFileImagePath
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.Serializable

@Serializable
data class ExactFileImageQuery(
    @DocField(description = "Normalized absolute path of the existing file to replace.")
    val filePath: ExactFileImagePath,
    @DocField(description = "Required SHA-256 of the exact current file bytes.")
    val expectedCurrentSha256: ExactFileImageSha256,
    @DocField(description = "Canonical Base64 encoding of the exact replacement bytes.")
    val contentBase64: ExactFileImageBase64,
    @DocField(description = "Required SHA-256 of the decoded replacement bytes.")
    val expectedResultSha256: ExactFileImageSha256,
    @DocField(description = "Optional canonical UUID-v4 verified mutation attempt.")
    val mutationAttemptId: String? = null,
    @DocField(description = "Required predeclared scratch authority when mutationAttemptId is present.")
    val mutationScratch: MutationScratchSet? = null,
) {
    init {
        require(isNormalizedAbsoluteExactFileImagePath(filePath.value)) {
            "Exact file image query path must be normalized and absolute"
        }
        require(FileHashing.sha256(contentBase64.copyBytes()) == expectedResultSha256.value) {
            "Exact file image query content must match expectedResultSha256"
        }
    }
}
