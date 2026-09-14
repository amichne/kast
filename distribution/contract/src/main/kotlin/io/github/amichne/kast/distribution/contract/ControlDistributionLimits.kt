package io.github.amichne.kast.distribution.contract

/** Shared safety bounds for a checksum-verified control product across installation and runtime identity admission. */
object ControlDistributionLimits {
    const val maximumEntryCount: Int = 16_384
    const val maximumArchiveMembers: Int = maximumEntryCount
    // Includes bin, lib and share themselves, globally; archive root is not a member.
    const val maximumTraversedEntries: Int = maximumEntryCount
    const val maximumPayloadFiles: Int = maximumEntryCount
    const val maximumPayloadBytes: Long = 1_073_741_824L
    const val maximumManifestBytes: Int = 64 * 1_024 * 1_024
}
