package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object ResetLoginEntries {
    fun remove(home: Path, label: ResetServiceLabel): ResetEffect =
        try {
            for (suffix in listOf(".login.plist", ".plist")) {
                Files.deleteIfExists(home.resolve("Library/LaunchAgents/${label.value}$suffix"))
            }
            ResetEffect.Completed
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
}
