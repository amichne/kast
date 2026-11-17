package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.direct.InstalledClientBinding
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import java.nio.file.Path

internal fun coordinatedPublicSession(
    installation: Path,
    directory: Path,
    catalog: PublicToolCatalog,
    environment: Map<String, String>,
): KastDirectToolSession? =
    when (val selected = publicInstallation(installation, environment)) {
        is Refinement.Rejected -> null
        is Refinement.Refined -> KastDirectToolSession.installed(directory, selected.value, catalog)
    }

internal fun coordinatedHookPreparation(
    installation: Path,
    environment: Map<String, String>,
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (val selected = publicInstallation(installation, environment)) {
        is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Installation(selected.failure))
        is Refinement.Refined -> prepareInstalledHook(root, wait, diagnostic, selected.value)
    }

private fun publicInstallation(installation: Path, environment: Map<String, String>) =
    InstalledClientBinding.locate(installation.resolve("share/kast/libexec/kast-management"), environment)
