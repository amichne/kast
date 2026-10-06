package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledUpgradePreparation
import io.github.amichne.kast.appserver.InstalledUpgradeRejection
import io.github.amichne.kast.appserver.InstalledUpgradeSettlement
import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import io.github.amichne.kast.distribution.managed.ControlInventoryAdmission
import io.github.amichne.kast.distribution.managed.ControlInventoryBoundary
import io.github.amichne.kast.distribution.managed.ControlInventoryFailure
import io.github.amichne.kast.distribution.managed.ControlLimitExceeded
import io.github.amichne.kast.distribution.managed.ControlPayloadInventory
import io.github.amichne.kast.distribution.managed.InstallationRecoveryAdmission
import io.github.amichne.kast.distribution.managed.InstallationRecoveryBaseline
import io.github.amichne.kast.distribution.managed.InstallationRecoveryPreparation
import io.github.amichne.kast.distribution.managed.InstallationReplacementWrite
import io.github.amichne.kast.distribution.managed.InstallationSnapshot
import io.github.amichne.kast.distribution.managed.InstallationSnapshotKind
import io.github.amichne.kast.distribution.managed.admitInstallationRecovery
import io.github.amichne.kast.distribution.managed.copyInstallationSnapshot
import io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories
import io.github.amichne.kast.distribution.managed.prepareInstallationRecovery
import io.github.amichne.kast.distribution.managed.writeInstallationReplacementReceipt
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_UNIX_SOCKET_PATH_BYTES = 104
private const val SOCKET_NAME_DIGEST_CHARACTERS = 43

/** Installs a control payload; host installation belongs to its independent installer. */
internal object InstallationWorkflow {
    fun execute(
        request: InstallationRequest,
        upgrades: PriorDaemonUpgradeGateway = NativePriorDaemonUpgradeGateway,
        activationPolicy: InstallationActivationPolicy = InstallationActivationPolicy.ACTIVATE,
    ): InstallationOutcome {
        if (
            request.controlOnly == InstallationSwitch.ENABLED &&
                activationPolicy != InstallationActivationPolicy.ACTIVATE
        )
            return InstallationOutcome.Rejected(InstallationFailure.REQUEST_REJECTED)
        val plan =
            when (val verified = verify(request)) {
                is PlanVerification.Verified -> verified.plan
                is PlanVerification.Rejected -> return InstallationOutcome.Rejected(verified.failure, verified.limit)
            }
        if (request.mode == InstallationMode.PLAN) {
            return InstallationOutcome.Complete(plan.report(InstallationActivation.Planned))
        }
        return try {
            apply(plan, upgrades, activationPolicy)
        } catch (_: InterruptedException) {
            Thread.interrupted()
            try {
                ControlInstallationRecovery.afterFailure(plan, InstallationFailure.INTERRUPTED)
            } finally {
                Thread.currentThread().interrupt()
            }
        } catch (_: IOException) {
            ControlInstallationRecovery.afterFailure(plan, InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ControlInstallationRecovery.afterFailure(plan, InstallationFailure.FILESYSTEM_REJECTED)
        }
    }

    fun recoverControl(request: InstallationRequest, failure: InstallationFailure): InstallationOutcome {
        if (request.controlOnly != InstallationSwitch.ENABLED)
            return InstallationOutcome.Rejected(InstallationFailure.REQUEST_REJECTED)
        val plan =
            when (val verified = verify(request)) {
                is PlanVerification.Verified -> verified.plan
                is PlanVerification.Rejected -> return InstallationOutcome.RecoveryRequired(failure, verified.failure)
            }
        return ControlInstallationRecovery.rollbackActivatedControl(plan, failure)
    }

    private fun verify(request: InstallationRequest): PlanVerification {
        val controlRoot = request.controlRoot.value
        if (!physicalDirectory(controlRoot)) return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        if (
            !regularFile(request.controlArchive.value) || digest(request.controlArchive.value) != request.controlDigest
        ) {
            return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        }
        when (val inventory = verifyControlLayout(controlRoot)) {
            is ControlInventoryAdmission.Admitted -> inventory.report(ControlInventoryBoundary.INSTALLER)
            is ControlInventoryAdmission.Rejected -> {
                inventory.report(ControlInventoryBoundary.INSTALLER)
                return PlanVerification.Rejected(
                    if (inventory.failure == ControlInventoryFailure.LIMIT_EXCEEDED)
                        InstallationFailure.CONTROL_LIMIT_EXCEEDED
                    else InstallationFailure.CONTROL_LAYOUT_REJECTED,
                    inventory.limit,
                )
            }
        }

        val idea = request.ideaHome.value
        val java = request.javaHome.value
        if (
            !physicalDirectory(idea) ||
                !physicalDirectory(java) ||
                !regularExecutable(java.resolve("bin/java")) ||
                !regularFile(idea.resolve("Resources/build.txt")) ||
                !physicalDirectory(idea.resolve("plugins/Kotlin")) ||
                idea.resolve("jbr/Contents/Home").normalize() != java
        )
            return PlanVerification.Rejected(InstallationFailure.IDEA_REJECTED)

        val payload = request.controlDigest
        val target = request.installRoot.value.resolve("installation")
        return PlanVerification.Verified(
            VerifiedInstallationPlan(
                request = request,
                payloadDigest = payload,
                targetRoot = target,
            )
        )
    }

    private fun apply(
        plan: VerifiedInstallationPlan,
        upgrades: PriorDaemonUpgradeGateway,
        activationPolicy: InstallationActivationPolicy,
    ): InstallationOutcome {
        if (!prepareOwnedDirectory(plan.request.installRoot.value))
            return InstallationOutcome.Rejected(InstallationFailure.INSTALLATION_ROOT_REJECTED)
        val lockPath = plan.request.installRoot.value.resolve("activation.lock")
        if (
            Files.isSymbolicLink(lockPath) ||
                (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !regularFile(lockPath))
        )
            return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use {
            channel ->
            val lock =
                acquireInstallationLock(channel)
                    ?: return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
            lock.use activation@{
                if (Files.isSymbolicLink(lockPath) || !secureActivationLock(lockPath))
                    return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
                when (val trust = enrollInstallationTrust(plan.request.home.value)) {
                    is io.github.amichne.kast.cli.ide.BrokerTrustResult.Complete -> Unit
                    is io.github.amichne.kast.cli.ide.BrokerTrustResult.Rejected ->
                        return InstallationOutcome.TrustRejected(trust.failure)
                }
                when (observePendingInstallationReplacement(plan.request.installRoot.value)) {
                    PendingInstallationReplacement.Absent -> Unit
                    PendingInstallationReplacement.RecoveryRequired ->
                        return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                    is PendingInstallationReplacement.Committed -> {
                        if (plan.request.force == InstallationSwitch.ENABLED || !admitExisting(plan))
                            return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                        when (admitInstallationRecovery(plan.targetRoot)) {
                            is InstallationRecoveryAdmission.Admitted,
                            is InstallationRecoveryAdmission.Pending -> Unit
                            InstallationRecoveryAdmission.Rejected ->
                                return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                        }
                        when (val selected = observeInstallationConfigurationSelection(plan, plan.targetRoot)) {
                            is Refinement.Refined -> {
                                val configuration = selected.value
                                if (
                                    configuration !is InstallationConfigurationSelection.Prior ||
                                        !configuration.preservesEndpoint()
                                )
                                    return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                            }
                            is Refinement.Rejected ->
                                return InstallationOutcome.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
                        }
                        return@activation
                    }
                }
                val prior =
                    when (val selected = selectedInstallation(plan)) {
                        PriorSelection.Absent -> null
                        is PriorSelection.Selected -> selected.root
                        PriorSelection.Rejected ->
                            return InstallationOutcome.Rejected(InstallationFailure.PRIOR_SELECTION_REJECTED)
                    }
                if (
                    prior == null &&
                        !Files.notExists(
                            plan.request.installRoot.value.resolve("recovery/installation"),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                )
                    return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                val baseline =
                    if (prior == null) null
                    else
                        when (val admission = admitInstallationRecovery(prior)) {
                            is InstallationRecoveryAdmission.Admitted -> admission.baseline
                            is InstallationRecoveryAdmission.Pending,
                            InstallationRecoveryAdmission.Rejected ->
                                return InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                        }
                val configuration =
                    when (val selected = observeInstallationConfigurationSelection(plan, prior)) {
                        is Refinement.Refined -> selected.value
                        is Refinement.Rejected ->
                            return InstallationOutcome.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
                    }
                val existing = canReuseExisting(plan, prior) && admitExisting(plan)
                if (
                    existing &&
                        configuration is InstallationConfigurationSelection.Prior &&
                        configuration.preservesEndpoint()
                ) {
                    return@activation
                }
                if (!existing && untrustedExistingCandidate(plan, prior))
                    return InstallationOutcome.Rejected(InstallationFailure.CANDIDATE_EXISTING_UNTRUSTED)
                val staged =
                    when (val stage = stage(plan, configuration)) {
                        is StageResult.Complete -> stage.root
                        is StageResult.Rejected -> return InstallationOutcome.Rejected(stage.failure)
                    }
                try {
                    if (
                        validateStagedConfiguration(staged.resolve("config/environment")) !=
                            InstallationConfigurationValidationOutcome.ADMITTED
                    )
                        return InstallationOutcome.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
                    if (qualifyCandidate(plan, staged) != InstallationChildOutcome.COMPLETED)
                        return InstallationOutcome.Rejected(InstallationFailure.CANDIDATE_QUALIFICATION_REJECTED)
                    var retiredPrior: PriorRetirement? = null
                    if (plan.request.controlOnly == InstallationSwitch.ENABLED) {
                        if (prior != plan.targetRoot)
                            return InstallationOutcome.Rejected(InstallationFailure.PRIOR_SELECTION_REJECTED)
                        val admitted =
                            executeInstallationChild(
                                InstallationChildStage.HOST_PREFLIGHT,
                                listOf(staged.resolve("share/kast/libexec/kast-service").toString(), "host-admission"),
                                ControlInstallationRecovery.controlChildEnvironment(plan) +
                                    ("KAST_CONFIGURATION_FILE" to staged.resolve("config/environment").toString()),
                            )
                        if (admitted != InstallationChildOutcome.COMPLETED)
                            return InstallationOutcome.Rejected(InstallationFailure.HOST_ADMISSION_REJECTED)
                    }
                    if (prior != null) {
                        if (plan.request.force == InstallationSwitch.DISABLED) {
                            when (val admission = admitPrior(prior, staged, plan.request, PriorInspection.PAYLOAD)) {
                                is Refinement.Refined -> Unit
                                is Refinement.Rejected -> return InstallationOutcome.Rejected(admission.failure)
                            }
                            val retirement =
                                when (val admitted = PriorRetirement.admit(prior, plan.request)) {
                                    is Refinement.Refined -> admitted.value
                                    is Refinement.Rejected -> return InstallationOutcome.Rejected(admitted.failure)
                                }
                            when (val update = upgrades.prepare(retirement, plan.request, plan.payloadDigest)) {
                                InstalledUpgradePreparation.NoDaemon -> Unit
                                is InstalledUpgradePreparation.Pending ->
                                    return InstallationOutcome.UpgradePending(update.blockers)
                                is InstalledUpgradePreparation.Rejected ->
                                    return InstallationOutcome.UpgradeRejected(update.reason)
                                is InstalledUpgradePreparation.Committed -> Unit
                                is InstalledUpgradePreparation.Sealed ->
                                    when (val committed = update.permit.commit()) {
                                        InstalledUpgradeSettlement.Completed -> Unit
                                        is InstalledUpgradeSettlement.Rejected ->
                                            return InstallationOutcome.UpgradeRejected(
                                                InstalledUpgradeRejection.Daemon(committed.reason)
                                            )
                                    }
                            }
                            when (val retired = retire(retirement)) {
                                is Refinement.Refined -> retiredPrior = retirement
                                is Refinement.Rejected ->
                                    return if (plan.request.controlOnly == InstallationSwitch.ENABLED)
                                        ControlInstallationRecovery.restoreRunningPrior(plan, retired.failure)
                                    else retirement.restore(retired.failure, staged, plan.request)
                            }
                            when (val admission = admitPrior(prior, staged, plan.request)) {
                                is Refinement.Refined -> Unit
                                is Refinement.Rejected ->
                                    return if (plan.request.controlOnly == InstallationSwitch.ENABLED)
                                        ControlInstallationRecovery.restoreRunningPrior(plan, admission.failure)
                                    else retirement.restore(admission.failure, staged, plan.request)
                            }
                            when (val retained = PriorInstallationRetention.retain(plan, prior, staged)) {
                                is Refinement.Refined -> Unit
                                is Refinement.Rejected ->
                                    return if (plan.request.controlOnly == InstallationSwitch.ENABLED)
                                        ControlInstallationRecovery.restoreRunningPrior(plan, retained.failure)
                                    else retirement.restore(retained.failure, staged, plan.request)
                            }
                        } else
                            when (val reset = admitReplacement(resetInstallation(prior, plan.request.home.value))) {
                                is Refinement.Refined -> Unit
                                is Refinement.Rejected -> return InstallationOutcome.Rejected(reset.failure)
                            }
                    }
                    when (val activated = activate(plan, staged, prior, baseline)) {
                        ActivationResult.Complete -> Unit
                        ActivationResult.RecoveryRequired ->
                            return if (plan.request.controlOnly == InstallationSwitch.ENABLED)
                                InstallationOutcome.RecoveryRequired(
                                    InstallationFailure.ACTIVATION_REJECTED,
                                    InstallationFailure.RECOVERY_REQUIRED,
                                )
                            else InstallationOutcome.Rejected(InstallationFailure.RECOVERY_REQUIRED)
                        is ActivationResult.Rejected ->
                            return if (plan.request.controlOnly == InstallationSwitch.ENABLED && prior != null)
                                ControlInstallationRecovery.restoreRunningPrior(plan, activated.failure)
                            else if (retiredPrior != null)
                                retiredPrior.restore(activated.failure, plan.request.controlRoot.value, plan.request)
                            else InstallationOutcome.Rejected(activated.failure)
                    }
                } finally {
                    if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) deleteTree(staged)
                }
            }
        }
        val activation =
            if (activationPolicy == InstallationActivationPolicy.ACTIVATE)
                InstallationActivation.fromChild(enableAppServer(plan))
            else InstallationActivation.NotRequested
        if (plan.request.controlOnly == InstallationSwitch.ENABLED) {
            when (activation) {
                InstallationActivation.Ready -> Unit
                InstallationActivation.Planned,
                InstallationActivation.NotRequested,
                is InstallationActivation.Pending ->
                    return ControlInstallationRecovery.rollbackActivatedControl(
                        plan,
                        InstallationFailure.CONTROL_START_REJECTED,
                    )
            }
            when (ControlInstallationRecovery.installedHostAdmission(plan, InstallationChildStage.HOST_RECONNECT)) {
                InstallationChildOutcome.COMPLETED -> Unit
                InstallationChildOutcome.EXIT_REJECTED,
                InstallationChildOutcome.DEADLINE_EXCEEDED,
                InstallationChildOutcome.IO_REJECTED,
                InstallationChildOutcome.INTERRUPTED ->
                    return ControlInstallationRecovery.rollbackActivatedControl(
                        plan,
                        InstallationFailure.HOST_RECONNECT_REJECTED,
                    )
            }
        }
        return InstallationOutcome.Complete(plan.report(activation))
    }

    private fun stage(
        plan: VerifiedInstallationPlan,
        configuration: InstallationConfigurationSelection,
    ): StageResult {
        val staged = Files.createTempDirectory(plan.request.installRoot.value, ".install-")
        var completed = false
        try {
            copyControl(plan.request.controlRoot.value, staged)
            writeLauncher(plan, staged, "kast")
            writeLauncher(plan, staged, "kast-codex")
            writeLauncher(plan, staged, "kast-mcp")
            writeLauncher(plan, staged, "kast-tool-rpc")
            writeInstallationConfiguration(
                plan = plan,
                stagedConfiguration = staged.resolve("config/environment"),
                configuration = configuration,
            )
            Files.writeString(
                staged.resolve("config/selected-ide.json"),
                Json { encodeDefaults = true }
                    .encodeToString(
                        io.github.amichne.kast.distribution.managed.SelectedIdeLaunch.serializer(),
                        io.github.amichne.kast.distribution.managed.SelectedIdeInstallation.resolve(
                            plan.request.ideaHome.value
                        ),
                    ),
                StandardOpenOption.CREATE_NEW,
            )
            Files.writeString(staged.resolve(".kast-control-sha256"), "${plan.request.controlDigest.value}\n")
            if (!writeManifest(plan, staged)) {
                return StageResult.Rejected(InstallationFailure.CONTROL_LAYOUT_REJECTED)
            }
            completed = true
            return StageResult.Complete(staged)
        } catch (_: IOException) {
            return StageResult.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        } finally {
            if (!completed) deleteTree(staged)
        }
    }

    private fun copyControl(source: Path, destination: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(directory)) throw IOException("control link rejected")
                    val relative = source.relativize(directory)
                    if (relative.toString().isNotEmpty()) Files.createDirectory(destination.resolve(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (!attributes.isRegularFile || Files.isSymbolicLink(file))
                        throw IOException("control entry rejected")
                    Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun writeInstallationConfiguration(
        plan: VerifiedInstallationPlan,
        stagedConfiguration: Path,
        configuration: InstallationConfigurationSelection,
    ) {
        Files.createDirectories(stagedConfiguration.parent)
        if (
            configuration is InstallationConfigurationSelection.Prior &&
                plan.request.controlOnly == InstallationSwitch.ENABLED &&
                plan.request.publicEndpoint == InstallationPublicEndpointSelection.Unspecified
        ) {
            Files.copy(configuration.root.resolve("config/environment"), stagedConfiguration)
            return
        }
        Files.writeString(
            stagedConfiguration,
            installationConfigurationContent(plan, configuration),
            StandardOpenOption.CREATE_NEW,
        )
        setMode(stagedConfiguration, "rw-------")
    }

    private fun writeLauncher(plan: VerifiedInstallationPlan, staged: Path, executable: String) {
        val launcher = staged.resolve("bin/$executable-complete")
        val dispatch =
            if (executable == "kast") {
                """
                |if [ "${'$'}{1-}" = installation ]; then
                |  shift
                |  exec python3 "${'$'}installation_root/share/kast/installation-lifecycle.py" --installation "${'$'}installation_root" "${'$'}@"
                |fi
                """
                    .trimMargin()
            } else {
                ""
            }
        val script =
            """
            |#!/bin/sh
            |set -eu
            |script_path="${'$'}0"
            |[ ! -L "${'$'}script_path" ] || { printf '%s\n' 'kast: launcher must be a regular file' >&2; exit 1; }
            |script_dir=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}script_path")" && pwd -P)
            |installation_root=${'$'}(CDPATH= cd -- "${'$'}script_dir/.." && pwd -P)
            |$dispatch
            |config_file="${'$'}installation_root/config/environment"
            |if [ -z "${'$'}{KAST_CONFIGURATION_FILE+x}" ]; then
            |  export KAST_CONFIGURATION_FILE="${'$'}config_file"
            |fi
            |control_executable="${'$'}script_dir/$executable"
            |[ -x "${'$'}control_executable" ] || { printf '%s\n' 'kast: installed payload is incomplete' >&2; exit 1; }
            |${installationJavaRuntimeEnvironment(plan.request.javaHome)}
            |unset KAST_SESSION_ROOT
            |if [ -e "${'$'}installation_root/.recovery-detached" ]; then
            |  case "${'$'}{1-} ${'$'}{2-}" in
            |    'app-server disable'|'stop ') ;;
            |    *) printf '%s\n' 'kast: installation detached; use standalone recovery' >&2; exit 1 ;;
            |  esac
            |fi
            |exec "${'$'}control_executable" "${'$'}@"
            |
        """
                .trimMargin()
        Files.writeString(launcher, script, StandardOpenOption.CREATE_NEW)
        setMode(launcher, "rwxr-xr-x")
    }

    private fun writeManifest(plan: VerifiedInstallationPlan, staged: Path): Boolean {
        val manifest = installationManifest(plan, staged)
        val encoded = installationManifestJson.encodeToString(InstallationManifest.serializer(), manifest) + "\n"
        if (encoded.encodeToByteArray().size > ControlDistributionLimits.maximumManifestBytes) return false
        Files.writeString(staged.resolve("installation.json"), encoded, StandardOpenOption.CREATE_NEW)
        return true
    }

    private fun installationManifest(plan: VerifiedInstallationPlan, staged: Path): InstallationManifest =
        InstallationManifest(
            semanticVersion = plan.request.version.toString(),
            installationRoot = plan.targetRoot.toString(),
            payloadIdentity = "sha256:${plan.payloadDigest.value}",
            controlSha256 = "sha256:${plan.request.controlDigest.value}",
            codexHome = plan.request.codexHome.value.toString(),
            configuration = plan.configuration.toString(),
            workspaceRegistry = plan.targetRoot.resolve("config/workspaces.json").toString(),
            stateRoot = plan.targetRoot.resolve("state").toString(),
            externalAnchors = externalAnchors(plan),
            payloadFiles = payloadFiles(staged),
        )

    private fun externalAnchors(plan: VerifiedInstallationPlan): List<ExternalAnchor> =
        fixedExternalAnchors(plan) + transportExternalAnchors(plan)

    private fun fixedExternalAnchors(plan: VerifiedInstallationPlan): List<ExternalAnchor> {
        val serviceHash = sha256(plan.targetRoot.toString().toByteArray()).value.take(32)
        val serviceLabel = "io.github.amichne.kast.broker.$serviceHash"
        return listOf(
            ExternalAnchor(
                "login",
                plan.request.home.value.resolve("Library/LaunchAgents/$serviceLabel.login.plist").toString(),
                expectedExecutable = plan.targetRoot.resolve("share/kast/libexec/kast-daemon").toString(),
                expectedLabel = serviceLabel,
            )
        )
    }

    private fun transportExternalAnchors(plan: VerifiedInstallationPlan): List<ExternalAnchor> = buildList {
        val run = plan.targetRoot.resolve("state/run")
        if (
            run.resolve("kast-${"0".repeat(SOCKET_NAME_DIGEST_CHARACTERS)}.sock").toString().toByteArray().size >=
                MAXIMUM_UNIX_SOCKET_PATH_BYTES
        ) {
            val alias = Path.of("/tmp/kast-uds-${sha256(run.toString().toByteArray()).value.take(32)}")
            add(
                ExternalAnchor(
                    "socket-alias",
                    alias.toString(),
                    expectedLinkTarget = run.toString(),
                    identityReceipt = run.resolve("endpoint-alias.json").toString(),
                )
            )
        }
        val upstream = InstalledUpstreamDirectories.transportPath(run.resolve("u.sock"))
        if (upstream != run.resolve("u.sock")) {
            add(
                ExternalAnchor(
                    "upstream-directory",
                    upstream.parent.toString(),
                    identityReceipt = run.resolve("upstream-directory.json").toString(),
                    expectedPhysicalDirectory = run.toString(),
                )
            )
        }
    }

    private fun canReuseExisting(plan: VerifiedInstallationPlan, prior: Path?): Boolean =
        prior == plan.targetRoot &&
            plan.request.controlOnly == InstallationSwitch.DISABLED &&
            plan.request.force == InstallationSwitch.DISABLED

    private fun untrustedExistingCandidate(plan: VerifiedInstallationPlan, prior: Path?): Boolean =
        prior == plan.targetRoot && plan.request.force == InstallationSwitch.DISABLED && samePayload(plan)

    private fun admitExisting(plan: VerifiedInstallationPlan): Boolean {
        if (!physicalDirectory(plan.targetRoot)) return false
        val raw =
            readInstallationBounded(
                plan.targetRoot.resolve("installation.json"),
                ControlDistributionLimits.maximumManifestBytes.toLong(),
            ) ?: return false
        val manifest =
            try {
                installationManifestJson.decodeFromString(InstallationManifest.serializer(), raw)
            } catch (_: SerializationException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
        return manifest.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
            manifest.semanticVersion == plan.request.version.toString() &&
            manifest.installationRoot == plan.targetRoot.toString() &&
            manifest.payloadIdentity == "sha256:${plan.payloadDigest.value}" &&
            manifest.controlSha256 == "sha256:${plan.request.controlDigest.value}" &&
            manifest.payloadFiles == payloadFiles(plan.targetRoot)
    }

    private fun samePayload(plan: VerifiedInstallationPlan): Boolean {
        val raw =
            readInstallationBounded(
                plan.targetRoot.resolve("installation.json"),
                ControlDistributionLimits.maximumManifestBytes.toLong(),
            ) ?: return false
        val manifest =
            try {
                installationManifestJson.decodeFromString(InstallationManifest.serializer(), raw)
            } catch (_: SerializationException) {
                return false
            }
        return manifest.semanticVersion == plan.request.version.toString() &&
            manifest.payloadIdentity == "sha256:${plan.payloadDigest.value}"
    }

    private fun selectedInstallation(plan: VerifiedInstallationPlan): PriorSelection =
        if (Files.exists(plan.targetRoot, LinkOption.NOFOLLOW_LINKS)) selectedPhysicalInstallation(plan)
        else selectedLegacyInstallation(plan)

    private fun selectedPhysicalInstallation(plan: VerifiedInstallationPlan): PriorSelection {
        if (!physicalDirectory(plan.targetRoot)) return PriorSelection.Rejected
        if (Files.exists(plan.request.installRoot.value.resolve("current"), LinkOption.NOFOLLOW_LINKS))
            return PriorSelection.Rejected
        if (plan.request.force == InstallationSwitch.ENABLED) return PriorSelection.Selected(plan.targetRoot)
        val manifest =
            readInstallationBounded(
                plan.targetRoot.resolve("installation.json"),
                ControlDistributionLimits.maximumManifestBytes.toLong(),
            ) ?: return PriorSelection.Rejected
        val existing =
            try {
                installationManifestJson.decodeFromString(InstallationManifest.serializer(), manifest)
            } catch (_: SerializationException) {
                return PriorSelection.Rejected
            }
        if (
            existing.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
                existing.installationRoot != plan.targetRoot.toString()
        )
            return PriorSelection.Rejected
        return PriorSelection.Selected(plan.targetRoot)
    }

    private fun selectedLegacyInstallation(plan: VerifiedInstallationPlan): PriorSelection {
        // Legacy selectors are read only as an explicit, verified migration input.
        val selector = plan.request.installRoot.value.resolve("current")
        if (Files.notExists(selector, LinkOption.NOFOLLOW_LINKS)) return PriorSelection.Absent
        if (!Files.isSymbolicLink(selector)) return PriorSelection.Rejected
        val target = Files.readSymbolicLink(selector)
        if (target.nameCount != 2 || target.getName(0).toString() != "versions") return PriorSelection.Rejected
        val resolved = plan.request.installRoot.value.resolve(target).normalize()
        if (resolved.parent != plan.request.installRoot.value.resolve("versions") || !physicalDirectory(resolved))
            return PriorSelection.Rejected
        return PriorSelection.Selected(resolved)
    }

    private fun qualifyCandidate(plan: VerifiedInstallationPlan, root: Path): InstallationChildOutcome =
        executeInstallationChild(
            InstallationChildStage.CANDIDATE_QUALIFICATION,
            listOf(root.resolve("bin/kast-complete").toString(), "--version"),
            ControlInstallationRecovery.controlChildEnvironment(plan) +
                ("KAST_CONFIGURATION_FILE" to root.resolve("config/environment").toString()),
        )

    private fun activate(
        plan: VerifiedInstallationPlan,
        staged: Path,
        prior: Path?,
        baseline: InstallationRecoveryBaseline?,
    ): ActivationResult {
        return try {
            val recovery = plan.request.installRoot.value.resolve("recovery")
            if (!prepareOwnedDirectory(recovery)) return ActivationResult.Rejected()
            val transaction = recovery.resolve("replacement")
            if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) return ActivationResult.RecoveryRequired
            Files.createDirectory(transaction)
            setMode(transaction, "rwx------")
            val receipt =
                InstallationReplacementReceipt(
                    stage = InstallationReplacementStage.PREPARED,
                    installation = plan.targetRoot.toString(),
                    installationIdentity = observeInstallationFilesystemIdentity(staged),
                    previous = previousPayload(plan, prior, transaction),
                )
            commitReplacement(ReplacementActivation(plan, transaction, receipt), staged, baseline)
        } catch (_: IOException) {
            ActivationResult.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ActivationResult.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun previousPayload(
        plan: VerifiedInstallationPlan,
        prior: Path?,
        transaction: Path,
    ): PreviousInstallationPayload =
        when {
            prior == null -> PreviousInstallationPayload.None
            prior == plan.targetRoot ->
                PreviousInstallationPayload.Physical(
                    installation = prior.toString(),
                    identity = observeInstallationFilesystemIdentity(prior),
                    payload = transaction.resolve("payload").toString(),
                    recovery = transaction.resolve("recovery").toString(),
                )
            else ->
                PreviousInstallationPayload.Legacy(
                    installation = prior.toString(),
                    identity = observeInstallationFilesystemIdentity(prior),
                    selector = plan.request.installRoot.value.resolve("current").toString(),
                    target = "versions/${prior.fileName}",
                    recovery = transaction.resolve("recovery").toString(),
                )
        }

    private fun commitReplacement(
        context: ReplacementActivation,
        staged: Path,
        baseline: InstallationRecoveryBaseline?,
    ): ActivationResult {
        var current = context
        var phase = ReplacementCommitPhase.PREPARED
        try {
            when (val written = writeInstallationReplacementReceipt(current.transaction, current.receipt)) {
                InstallationReplacementWrite.Written -> Unit
                is InstallationReplacementWrite.Rejected ->
                    return rollbackReplacement(current, phase, written.failure.installationFailure())
            }
            if (baseline != null)
                when (
                    val copied =
                        copyInstallationSnapshot(
                            baseline.bundle,
                            context.transaction.resolve("recovery"),
                            InstallationSnapshotKind.RECOVERY,
                        )
                ) {
                    InstallationSnapshot.Copied -> Unit
                    is InstallationSnapshot.Rejected ->
                        return rollbackReplacement(current, phase, copied.failure.installationFailure())
                }
            if (context.receipt.previous is PreviousInstallationPayload.Physical) {
                moveInstallationPayload(context.plan.targetRoot, context.transaction.resolve("payload"))
                phase = ReplacementCommitPhase.PRIOR_MOVED
            }
            moveInstallationPayload(staged, context.plan.targetRoot)
            phase = ReplacementCommitPhase.PAYLOAD_COMMITTED
            if (
                prepareInstallationRecovery(context.plan.targetRoot, baseline) !=
                    InstallationRecoveryPreparation.Prepared
            )
                return rollbackReplacement(current, phase, InstallationFailure.ACTIVATION_REJECTED)
            current =
                current.copy(
                    receipt =
                        current.receipt.copy(
                            stage = InstallationReplacementStage.PAYLOAD_COMMITTED,
                            installationIdentity = observeInstallationFilesystemIdentity(context.plan.targetRoot),
                        )
                )
            return when (val written = writeInstallationReplacementReceipt(current.transaction, current.receipt)) {
                InstallationReplacementWrite.Written -> ActivationResult.Complete
                is InstallationReplacementWrite.Rejected ->
                    rollbackReplacement(current, phase, written.failure.installationFailure())
            }
        } catch (_: IOException) {
            return rollbackReplacement(current, phase, InstallationFailure.ACTIVATION_REJECTED)
        }
    }

    private fun rollbackReplacement(
        context: ReplacementActivation,
        phase: ReplacementCommitPhase,
        failure: InstallationFailure,
    ): ActivationResult {
        return try {
            rollbackPayload(context, phase)
            if (phase == ReplacementCommitPhase.PAYLOAD_COMMITTED) rollbackRecovery(context)
            deleteTree(context.transaction)
            ActivationResult.Rejected(failure)
        } catch (_: IOException) {
            writeInstallationReplacementReceipt(
                context.transaction,
                context.receipt.copy(stage = InstallationReplacementStage.RECOVERY_REQUIRED),
            )
            ActivationResult.RecoveryRequired
        }
    }

    private fun rollbackPayload(context: ReplacementActivation, phase: ReplacementCommitPhase) {
        if (
            phase == ReplacementCommitPhase.PAYLOAD_COMMITTED &&
                Files.exists(context.plan.targetRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            if (observeInstallationFilesystemIdentity(context.plan.targetRoot) != context.receipt.installationIdentity)
                throw IOException("replacement payload identity changed")
            deleteTree(context.plan.targetRoot)
        }
        if (
            phase != ReplacementCommitPhase.PREPARED && context.receipt.previous is PreviousInstallationPayload.Physical
        )
            moveInstallationPayload(context.transaction.resolve("payload"), context.plan.targetRoot)
    }

    private fun rollbackRecovery(context: ReplacementActivation) {
        val bundle = context.transaction.parent.resolve("installation")
        if (Files.exists(bundle, LinkOption.NOFOLLOW_LINKS)) deleteTree(bundle)
        val previous = context.transaction.resolve("recovery")
        if (
            context.receipt.previous is PreviousInstallationPayload.Physical &&
                Files.exists(previous, LinkOption.NOFOLLOW_LINKS)
        )
            moveInstallationPayload(previous, bundle)
    }

    private fun enableAppServer(plan: VerifiedInstallationPlan): InstallationChildOutcome =
        executeInstallationChild(
            InstallationChildStage.APP_SERVER_ENABLE,
            listOf(plan.targetRoot.resolve("share/kast/libexec/kast-service").toString(), "enable"),
            ControlInstallationRecovery.controlChildEnvironment(plan),
        )
}

private enum class ReplacementCommitPhase {
    PREPARED,
    PRIOR_MOVED,
    PAYLOAD_COMMITTED,
}

private data class ReplacementActivation(
    val plan: VerifiedInstallationPlan,
    val transaction: Path,
    val receipt: InstallationReplacementReceipt,
)

private sealed interface PlanVerification {
    data class Verified(val plan: VerifiedInstallationPlan) : PlanVerification

    data class Rejected(val failure: InstallationFailure, val limit: ControlLimitExceeded? = null) : PlanVerification
}

private sealed interface StageResult {
    data class Complete(val root: Path) : StageResult

    data class Rejected(val failure: InstallationFailure) : StageResult
}

private sealed interface PriorSelection {
    data object Absent : PriorSelection

    data class Selected(val root: Path) : PriorSelection

    data object Rejected : PriorSelection
}

private sealed interface ActivationResult {
    data object RecoveryRequired : ActivationResult

    data object Complete : ActivationResult

    data class Rejected(val failure: InstallationFailure = InstallationFailure.ACTIVATION_REJECTED) : ActivationResult
}

private fun physicalDirectory(path: Path): Boolean =
    try {
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && path.toRealPath() == path
    } catch (_: IOException) {
        false
    }

private fun regularFile(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

private fun regularExecutable(path: Path): Boolean = regularFile(path) && Files.isExecutable(path)

internal fun secureActivationLock(path: Path): Boolean =
    try {
        if (!regularFile(path)) {
            false
        } else {
            setMode(path, "rw-------")
            mode(path) == 0b110_000_000
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

internal fun readInstallationBounded(path: Path, limit: Long): String? =
    try {
        if (!regularFile(path) || Files.size(path) > limit) null else Files.readString(path)
    } catch (_: IOException) {
        null
    }

private fun digest(path: Path): Sha256? =
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        Sha256.parse(digest.digest().hex()).let { refinement ->
            when (refinement) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> refinement.value
                is io.github.amichne.kast.kernel.Refinement.Rejected -> null
            }
        }
    } catch (_: IOException) {
        null
    }

private fun sha256(bytes: ByteArray): Sha256 =
    when (val value = Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).hex())) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value.value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error("SHA-256 provider emitted an invalid digest")
    }

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun verifyControlLayout(root: Path): ControlInventoryAdmission {
    if (listOf("kast", "kast-codex", "kast-mcp", "kast-tool-rpc").any { !regularExecutable(root.resolve("bin/$it")) })
        return ControlInventoryAdmission.Rejected(ControlInventoryFailure.UNSUPPORTED_ENTRY)
    val required =
        listOf(
            "ide-host.json",
            "operation-registry.json",
            "wire-schema.json",
            "installation-lifecycle.py",
            "codex-mcp-registration.py",
        )
    if (required.any { !regularFile(root.resolve("share/kast/$it")) })
        return ControlInventoryAdmission.Rejected(ControlInventoryFailure.UNSUPPORTED_ENTRY)
    return ControlPayloadInventory.admit(root)
}

private fun prepareOwnedDirectory(path: Path): Boolean {
    return try {
        var ancestor = path
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent ?: return false
        }
        if (!physicalDirectory(ancestor)) false
        else {
            Files.createDirectories(path)
            physicalDirectory(path)
        }
    } catch (_: IOException) {
        false
    }
}

private fun payloadFiles(root: Path): List<PayloadFile> {
    val inventory =
        when (val admitted = ControlPayloadInventory.admit(root)) {
            is ControlInventoryAdmission.Admitted -> admitted
            is ControlInventoryAdmission.Rejected -> {
                admitted.report(ControlInventoryBoundary.INSTALLER)
                throw IOException("control inventory rejected")
            }
        }
    return inventory.files.map { file ->
        PayloadFile(
            root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
            "sha256:${digest(file)?.value ?: throw IOException("payload unreadable")}",
            mode(file),
        )
    }
}

private fun mode(path: Path): Int =
    try {
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        listOf(
                PosixFilePermission.OWNER_READ to 0b100_000_000,
                PosixFilePermission.OWNER_WRITE to 0b010_000_000,
                PosixFilePermission.OWNER_EXECUTE to 0b001_000_000,
                PosixFilePermission.GROUP_READ to 0b000_100_000,
                PosixFilePermission.GROUP_WRITE to 0b000_010_000,
                PosixFilePermission.GROUP_EXECUTE to 0b000_001_000,
                PosixFilePermission.OTHERS_READ to 0b000_000_100,
                PosixFilePermission.OTHERS_WRITE to 0b000_000_010,
                PosixFilePermission.OTHERS_EXECUTE to 0b000_000_001,
            )
            .sumOf { (permission, bit) -> if (permission in permissions) bit else 0 }
    } catch (_: UnsupportedOperationException) {
        if (Files.isExecutable(path)) 493 else 420
    }

private fun setMode(path: Path, value: String) {
    try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(value))
    } catch (_: UnsupportedOperationException) {
        path.toFile().setExecutable(value.contains('x'), false)
    }
}

internal fun moveInstallationPayload(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

internal fun deleteTree(root: Path) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal val installationManifestJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
