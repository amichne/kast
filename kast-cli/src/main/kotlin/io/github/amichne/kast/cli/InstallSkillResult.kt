package io.github.amichne.kast.cli

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class InstallSkillResult(
    val installedAt: String,
    val version: String,
    val skipped: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
)
