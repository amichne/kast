package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ReleaseRevision
import java.nio.file.Path

internal data class MacosHomebrewInstallReceipt(
    val cliBinary: Path,
    val formulaPrefix: Path,
    val cliVersion: CliImplementationVersion,
    val cliRevision: ReleaseRevision,
)

internal fun defaultMacosHomebrewReceiptPath(
    userHome: Path = Path.of(System.getProperty("user.home")),
): Path = userHome.resolve("Library/Application Support/Kast/homebrew-install.json")
