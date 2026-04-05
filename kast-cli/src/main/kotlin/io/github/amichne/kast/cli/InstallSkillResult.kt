package io.github.amichne.kast.cli

import io.github.amichne.kast.api.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class InstallSkillResult(
    val linkedFrom: String,
    val linkedTo: String,
    val skipped: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
)
