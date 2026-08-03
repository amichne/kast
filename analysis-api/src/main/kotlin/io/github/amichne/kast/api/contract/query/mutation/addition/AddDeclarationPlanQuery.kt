@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class AddDeclarationPlanQuery(
    @DocField(description = "Normalized absolute .kt path of the existing target file.")
    val targetPath: AdditionTargetPath,
    @DocField(description = "Required SHA-256 of the exact current target bytes.")
    val expectedCurrentSha256: AdditionTargetPreimageSha256,
    @DocField(description = "One complete inline top-level Kotlin declaration in normalized LF form.")
    val proposedDeclaration: String,
)
