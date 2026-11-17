package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.direct.InstalledClientBinding
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import java.nio.file.Path

internal fun coordinatedPublicSession(
    installation: Path,
    directory: Path,
    catalog: PublicToolCatalog,
): KastDirectToolSession? =
    when (
        val selected =
            InstalledClientBinding.locate(
                installation.resolve("share/kast/libexec/kast-management"),
                System.getenv(),
            )
    ) {
        is Refinement.Rejected -> null
        is Refinement.Refined -> KastDirectToolSession.installed(directory, selected.value, catalog)
    }
