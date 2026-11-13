package io.github.amichne.kast.distribution.contract

/** Shared safety bounds for a checksum-verified control product across installation and runtime identity admission. */
object ControlDistributionLimits {
    const val maximumEntryCount: Int = 16_384
    const val maximumArchiveMembers: Int = maximumEntryCount
    // Installed bin/lib/share include the separately admitted pinned interpreter.
    // Traversal includes those three directories; archive admission keeps its original bound.
    const val maximumTraversedEntries: Int = 32_768
    const val maximumPayloadFiles: Int = maximumTraversedEntries
    const val maximumPayloadBytes: Long = 1_073_741_824L
    const val maximumManifestBytes: Int = 64 * 1_024 * 1_024
    const val maximumReplacementReceiptBytes: Int = 2 * 1_024 * 1_024
}
