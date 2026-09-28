package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

internal data class InstallDestination(val path: Path, val onPath: Boolean, val previous: ManagementReceipt?)
