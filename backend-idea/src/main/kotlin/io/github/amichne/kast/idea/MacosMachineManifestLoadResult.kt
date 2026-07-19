package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import java.nio.file.Path

internal sealed interface MacosMachineManifestLoadResult {
    data class Loaded(
        val binary: Path,
        val version: CliImplementationVersion,
    ) : MacosMachineManifestLoadResult

    data class Rejected(val message: String) : MacosMachineManifestLoadResult
}
