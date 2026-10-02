package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Serializable

/** Versioned wire observation. Readers must admit schema, PID and start time before trusting it. */
@Serializable data class InstalledToolInvocation(val schemaVersion: Int, val pid: Long, val startEpochMillis: Long)
