package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString

internal fun writeStagedReport(report: Path) =
    Files.writeString(
        report,
        managementJson.encodeToString(
            FixtureInstallerReport(status = "installed", activation = FixtureActivation("not-requested"))
        ),
    )
