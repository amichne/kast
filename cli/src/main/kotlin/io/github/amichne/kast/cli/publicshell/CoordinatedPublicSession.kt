package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.cli.direct.CoordinatedToolClient
import io.github.amichne.kast.cli.direct.InstalledClientBinding
import io.github.amichne.kast.cli.direct.InvocationWitness
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import java.nio.file.Path

/** The public shell's session for [installation]. A coordinator-hosted shell supplies its own [read] and [witness]. */
internal fun coordinatedPublicSession(
    installation: Path,
    directory: Path,
    catalog: PublicToolCatalog,
    environment: Map<String, String>,
    read: (InstalledClientBinding) -> DaemonOperationClient = ::CoordinatedToolClient,
    witness: InvocationWitness = InvocationWitness.ProcessRecord,
): KastDirectToolSession? =
    when (val selected = publicInstallation(installation, environment)) {
        is Refinement.Rejected -> null
        is Refinement.Refined ->
            KastDirectToolSession.installed(directory, selected.value, catalog, read(selected.value), witness)
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

/** Installation bindings are located from the installation's management payload. */
private fun publicInstallation(installation: Path, environment: Map<String, String>) =
    InstalledClientBinding.locate(InstallationLayout.of(installation).payload.managementExecutable, environment)
