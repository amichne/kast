@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class AddFilePlanQuery(
    @DocField(description = "Normalized absolute .kt path for the absent target.")
    val targetPath: AdditionTargetPath,
    @DocField(description = "Complete inline Kotlin source proposed for the new file.")
    val proposedContent: String,
)
