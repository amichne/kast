package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class DeclarationInfo(
    val fqName: String,
    val kind: String,
    val visibility: String,
    val path: String?,
    val modulePath: String?,
    val sourceSet: String?,
)
