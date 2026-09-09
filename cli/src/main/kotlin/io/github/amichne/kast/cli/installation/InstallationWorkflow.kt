package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.configuration.InstallationOperationalLimits
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories
import io.github.amichne.kast.appserver.InstalledWorkspaceRegistryRetention
import io.github.amichne.kast.appserver.PublishedBrokerServiceCommand
import io.github.amichne.kast.appserver.WorkspaceRegistryRetention
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
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
import java.time.Duration
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_CONTROL_FILES = 4_096
private const val MAXIMUM_CONTROL_BYTES = 1024L * 1024L * 1024L
private const val MAXIMUM_MANIFEST_BYTES = 1024L * 1024L

/** Reconstruct the only supported transient enabled owner of an opt-out installation. */
internal fun priorServiceRetirementEnvironment(
    prior: Path,
    home: Path,
    codexHome: Path,
    path: String,
): Map<String, String> = mapOf(
    "HOME" to home.toString(),
    "PATH" to path,
    "CODEX_HOME" to codexHome.toString(),
    "KAST_CONFIGURATION_FILE" to prior.resolve("config/environment").toString(),
    "KAST_ENABLE_APP_SERVER" to "1",
)

internal enum class InstallationFailure {
    REQUEST_REJECTED,
    CONTROL_REJECTED,
    RUNTIME_REJECTED,
    IDEA_REJECTED,
    INSTALLATION_ROOT_REJECTED,
    ACTIVATION_LOCK_REJECTED,
    EXISTING_INSTALLATION_REJECTED,
    CONFIGURATION_REJECTED,
    PREVIOUS_INSTALLATION_REJECTED,
    RETIREMENT_REJECTED,
    REGISTRY_RETENTION_REJECTED,
    ACTIVATION_REJECTED,
    APP_SERVER_ENABLE_REJECTED,
    FILESYSTEM_REJECTED,
    INTERRUPTED,
}

internal sealed interface InstallationOutcome {
    data class Complete(val report: InstallationReport) : InstallationOutcome
    data class Rejected(val failure: InstallationFailure) : InstallationOutcome
}

@Serializable
internal data class InstallationReport(
    val operation: String = "installation.install",
    val status: String,
    val semanticVersion: String,
    val installation: String,
    val controlSha256: String,
    val runtimeSha256: String,
    val ideaHome: String,
    val changes: List<String>,
)

@Serializable
private data class InstallationManifest(
    val schemaVersion: Int = 1,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadIdentity: String,
    val controlSha256: String,
    val runtimeSha256: String,
    val codexHome: String,
    val configuration: String,
    val workspaceRegistry: String,
    val stateRoot: String,
    val externalAnchors: List<ExternalAnchor>,
    val payloadFiles: List<PayloadFile>,
    val retention: Retention = Retention(),
)

@Serializable
private data class ExternalAnchor(
    val kind: String,
    val path: String,
    val expectedLinkTarget: String? = null,
    val requiresCurrentTarget: String? = null,
    val expectedExecutable: String? = null,
    val expectedLabel: String? = null,
    val identityReceipt: String? = null,
    val expectedPhysicalDirectory: String? = null,
    val ownership: String = "declared-not-observed",
)

@Serializable
private data class PayloadFile(val path: String, val sha256: String, val mode: Int)

@Serializable
private data class Retention(
    val payload: String = "until-explicit-uninstall",
    val config: String = "until-explicit-uninstall",
    val state: String = "after-exact-process-retirement",
    val externalAnchors: String = "after-live-identity-match",
)

private data class VerifiedInstallationPlan(
    val request: InstallationRequest,
    val runtimeName: String,
    val payloadDigest: Sha256,
    val versionsRoot: Path,
    val targetRoot: Path,
    val currentLink: Path,
    val commandLink: Path,
    val codexCommandLink: Path,
) {
    val configuration: Path get() = targetRoot.resolve("config/environment")

    fun report(status: String): InstallationReport = InstallationReport(
        status = status,
        semanticVersion = request.version.toString(),
        installation = targetRoot.toString(),
        controlSha256 = "sha256:${request.controlDigest.value}",
        runtimeSha256 = "sha256:${request.runtimeDigest.value}",
        ideaHome = request.ideaHome.value.toString(),
        changes = listOf(
            "install-immutable-payload",
            "write-release-local-configuration",
            "retire-previous-app-server",
            "retain-workspace-registry",
            "replace-current-link",
            "replace-command-links",
        ) + if (request.refreshAppServer == InstallationSwitch.ENABLED) {
            listOf("enable-app-server")
        } else {
            emptyList()
        },
    )
}

/** Installs one already-downloaded matched release; network and IDEA discovery remain bootstrap effects. */
internal object InstallationWorkflow {
    fun execute(request: InstallationRequest): InstallationOutcome {
        val plan = when (val verified = verify(request)) {
            is PlanVerification.Verified -> verified.plan
            is PlanVerification.Rejected -> return InstallationOutcome.Rejected(verified.failure)
        }
        if (request.mode == InstallationMode.PLAN) {
            return InstallationOutcome.Complete(plan.report("planned"))
        }
        return try {
            apply(plan)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InstallationOutcome.Rejected(InstallationFailure.INTERRUPTED)
        } catch (_: IOException) {
            InstallationOutcome.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            InstallationOutcome.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun verify(request: InstallationRequest): PlanVerification {
        val controlRoot = request.controlRoot.value
        if (!physicalDirectory(controlRoot)) return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        if (!regularFile(request.controlArchive.value) || digest(request.controlArchive.value) != request.controlDigest) {
            return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        }
        if (!regularFile(request.runtimeArchive.value) || digest(request.runtimeArchive.value) != request.runtimeDigest) {
            return PlanVerification.Rejected(InstallationFailure.RUNTIME_REJECTED)
        }
        if (!verifyControlLayout(controlRoot)) return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)

        val manifestPath = controlRoot.resolve("share/kast/semantic-runtime.json")
        val rawManifest = readBounded(manifestPath, MAXIMUM_MANIFEST_BYTES)
            ?: return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        val manifest = when (val admission = SemanticRuntimeManifest.admit(rawManifest)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admission.manifest
            is SemanticRuntimeManifestAdmission.Rejected ->
                return PlanVerification.Rejected(InstallationFailure.CONTROL_REJECTED)
        }
        if (
            manifest.productVersion.value != request.version.toString() ||
            manifest.archive.digest.value != "sha256:${request.runtimeDigest.value}" ||
            manifest.archive.size.bytes != fileSize(request.runtimeArchive.value) ||
            manifest.archive.fileName.value != request.runtimeArchive.value.fileName.toString()
        ) return PlanVerification.Rejected(InstallationFailure.RUNTIME_REJECTED)
        if (!verifyRuntimeArchive(request.runtimeArchive.value, manifest.layout.requiredEntries.map { it.value })) {
            return PlanVerification.Rejected(InstallationFailure.RUNTIME_REJECTED)
        }

        val idea = request.ideaHome.value
        val java = request.javaHome.value
        if (
            !physicalDirectory(idea) || !physicalDirectory(java) ||
            !regularExecutable(java.resolve("bin/java")) ||
            !regularFile(idea.resolve("Resources/build.txt")) ||
            !physicalDirectory(idea.resolve("plugins/Kotlin")) ||
            idea.resolve("jbr/Contents/Home").normalize() != java
        ) return PlanVerification.Rejected(InstallationFailure.IDEA_REJECTED)

        val payload = sha256("${request.controlDigest.value}\n${request.runtimeDigest.value}\n".toByteArray())
        val versions = request.installRoot.value.resolve("versions")
        val target = versions.resolve("${request.version}-${payload.value}")
        return PlanVerification.Verified(
            VerifiedInstallationPlan(
                request,
                manifest.archive.fileName.value,
                payload,
                versions,
                target,
                request.installRoot.value.resolve("current"),
                request.binDirectory.value.resolve("kast"),
                request.binDirectory.value.resolve("kast-codex"),
            ),
        )
    }

    private fun apply(plan: VerifiedInstallationPlan): InstallationOutcome {
        if (!prepareOwnedDirectory(plan.request.installRoot.value) || !prepareOwnedDirectory(plan.request.binDirectory.value)) {
            return InstallationOutcome.Rejected(InstallationFailure.INSTALLATION_ROOT_REJECTED)
        }
        if (!prepareOwnedDirectory(plan.versionsRoot)) {
            return InstallationOutcome.Rejected(InstallationFailure.INSTALLATION_ROOT_REJECTED)
        }
        val lockPath = plan.request.installRoot.value.resolve("activation.lock")
        if (Files.isSymbolicLink(lockPath) || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !regularFile(lockPath))) {
            return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
        }
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = acquire(channel) ?: return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
            lock.use {
                if (Files.isSymbolicLink(lockPath) || !secureActivationLock(lockPath)) {
                    return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
                }
                val existing = Files.exists(plan.targetRoot, LinkOption.NOFOLLOW_LINKS)
                if (existing && !admitExisting(plan)) {
                    return InstallationOutcome.Rejected(InstallationFailure.EXISTING_INSTALLATION_REJECTED)
                }
                if (!existing) {
                    when (val staged = stage(plan)) {
                        StageResult.Complete -> Unit
                        is StageResult.Rejected -> return InstallationOutcome.Rejected(staged.failure)
                    }
                }
                val prior = when (val selected = selectedInstallation(plan)) {
                    is PriorSelection.Absent -> null
                    is PriorSelection.Selected -> selected.root
                    is PriorSelection.Rejected ->
                        return InstallationOutcome.Rejected(InstallationFailure.PREVIOUS_INSTALLATION_REJECTED)
                }
                if (prior != null && prior != plan.targetRoot) {
                    if (!admitPrior(prior, plan.request) || !retire(prior, plan.request)) {
                        return InstallationOutcome.Rejected(InstallationFailure.RETIREMENT_REJECTED)
                    }
                    when (InstalledWorkspaceRegistryRetention.retain(
                        source = prior.resolve("config/workspaces.json"),
                        destination = plan.targetRoot.resolve("config/workspaces.json"),
                    )) {
                        WorkspaceRegistryRetention.Retained -> Unit
                        is WorkspaceRegistryRetention.Rejected -> return InstallationOutcome.Rejected(
                            InstallationFailure.REGISTRY_RETENTION_REJECTED,
                        )
                    }
                }
                if (!validateConfiguration(plan)) {
                    return InstallationOutcome.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
                }
                val activation = activate(plan)
                if (activation is ActivationResult.Rejected) {
                    return InstallationOutcome.Rejected(InstallationFailure.ACTIVATION_REJECTED)
                }
            }
        }
        if (plan.request.refreshAppServer == InstallationSwitch.ENABLED && !enableAppServer(plan)) {
            return InstallationOutcome.Rejected(InstallationFailure.APP_SERVER_ENABLE_REJECTED)
        }
        return InstallationOutcome.Complete(plan.report("installed"))
    }

    private fun stage(plan: VerifiedInstallationPlan): StageResult {
        val staged = Files.createTempDirectory(plan.versionsRoot, ".install-${plan.request.version}-")
        try {
            copyControl(plan.request.controlRoot.value, staged)
            val runtimeRoot = Files.createDirectories(staged.resolve("share/kast/runtime"))
            Files.copy(
                plan.request.runtimeArchive.value,
                runtimeRoot.resolve(plan.runtimeName),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            Files.writeString(
                runtimeRoot.resolve("${plan.runtimeName}.sha256"),
                "${plan.request.runtimeDigest.value}  ${plan.runtimeName}\n",
                StandardOpenOption.CREATE_NEW,
            )
            writeLauncher(plan, staged, "kast")
            if (regularExecutable(staged.resolve("bin/kast-codex"))) writeLauncher(plan, staged, "kast-codex")
            writeConfiguration(plan, staged.resolve("config/environment"))
            Files.writeString(staged.resolve(".kast-control-sha256"), "${plan.request.controlDigest.value}\n")
            Files.writeString(staged.resolve(".kast-runtime-sha256"), "${plan.request.runtimeDigest.value}\n")
            writeManifest(plan, staged)
            move(staged, plan.targetRoot)
            return StageResult.Complete
        } catch (_: IOException) {
            return StageResult.Rejected(InstallationFailure.FILESYSTEM_REJECTED)
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) deleteTree(staged)
        }
    }

    private fun copyControl(source: Path, destination: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(directory)) throw IOException("control link rejected")
                val relative = source.relativize(directory)
                if (relative.toString().isNotEmpty()) Files.createDirectory(destination.resolve(relative))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!attributes.isRegularFile || Files.isSymbolicLink(file)) throw IOException("control entry rejected")
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun writeConfiguration(plan: VerifiedInstallationPlan, stagedConfiguration: Path) {
        Files.createDirectories(stagedConfiguration.parent)
        val target = plan.targetRoot
        val request = plan.request
        val values = KastConfigurationCatalogue.declarations
            .filter { declaration ->
                ConfigurationSource.SAVED_INSTALLATION in declaration.sources &&
                    declaration.defaultValue != null
            }
            .associate { declaration -> declaration.key to checkNotNull(declaration.defaultValue) }
            .plus(
                mapOf(
                    "KAST_RUNTIME_STORE" to target.resolve("runtime-payloads").toString(),
                    "KAST_RUNTIME_DIRECTORY" to target.resolve("state/run").toString(),
                    "KAST_CACHE_ROOT" to target.resolve("state/cache").toString(),
                    "KAST_ENABLE_LAUNCHD" to request.enableLaunchd.wireValue(),
                    "KAST_ENABLE_APP_SERVER" to request.enableAppServer.wireValue(),
                    "KAST_APP_SERVER_TOOLS" to request.appServerTools.value,
                ),
            )
        val content = buildString {
            appendLine("# Kast runtime configuration. Values are literal; shell syntax is not evaluated.")
            values.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
        }
        Files.writeString(stagedConfiguration, content, StandardOpenOption.CREATE_NEW)
        setMode(stagedConfiguration, "rw-------")
    }

    private fun writeLauncher(plan: VerifiedInstallationPlan, staged: Path, executable: String) {
        val launcher = staged.resolve("bin/$executable-complete")
        val dispatch = if (executable == "kast") {
            """
            |if [ "${'$'}{1-}" = installation ]; then
            |  shift
            |  exec python3 "${'$'}installation_root/share/kast/installation-lifecycle.py" --installation "${'$'}installation_root" "${'$'}@"
            |fi
            """.trimMargin()
        } else {
            ""
        }
        val script = """
            |#!/bin/sh
            |set -eu
            |script_path="${'$'}0"
            |links=0
            |while [ -L "${'$'}script_path" ]; do
            |  links=${'$'}((links + 1))
            |  [ "${'$'}links" -le 16 ] || { printf '%s\n' 'kast: launcher symlink cycle' >&2; exit 1; }
            |  target=${'$'}(readlink "${'$'}script_path")
            |  case "${'$'}target" in /*) script_path="${'$'}target" ;; *) script_path="${'$'}(dirname -- "${'$'}script_path")/${'$'}target" ;; esac
            |done
            |script_dir=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}script_path")" && pwd -P)
            |installation_root=${'$'}(CDPATH= cd -- "${'$'}script_dir/.." && pwd -P)
            |$dispatch
            |config_file=${shellQuote(plan.configuration.toString())}
            |if [ -n "${'$'}{KAST_CONFIGURATION_FILE+x}" ] && [ "${'$'}KAST_CONFIGURATION_FILE" != "${'$'}config_file" ]; then
            |  export KAST_SAVED_CONFIGURATION_FAILURE=configuration-selector-conflict
            |fi
            |export KAST_CONFIGURATION_FILE="${'$'}config_file"
            |runtime_archive="${'$'}installation_root/share/kast/runtime/${plan.runtimeName}"
            |control_executable="${'$'}script_dir/$executable"
            |[ -x "${'$'}control_executable" ] && [ -f "${'$'}runtime_archive" ] || { printf '%s\n' 'kast: installed payload is incomplete' >&2; exit 1; }
            |if [ -z "${'$'}{KAST_GRADLE_JAVA_HOME+x}" ] && [ -n "${'$'}{JAVA_HOME:-}" ]; then
            |  export KAST_GRADLE_JAVA_HOME="${'$'}JAVA_HOME"
            |fi
            |export JAVA=${shellQuote(plan.request.javaHome.value.resolve("bin/java").toString())}
            |export JAVA_HOME=${shellQuote(plan.request.javaHome.value.toString())}
            |export KAST_RUNTIME_ARCHIVE="${'$'}runtime_archive"
            |unset KAST_SESSION_ROOT
            |exec "${'$'}control_executable" "${'$'}@"
            |
        """.trimMargin()
        Files.writeString(launcher, script, StandardOpenOption.CREATE_NEW)
        setMode(launcher, "rwxr-xr-x")
    }

    private fun writeManifest(plan: VerifiedInstallationPlan, staged: Path) {
        val payloadFiles = payloadFiles(staged)
        val currentTarget = "versions/${plan.targetRoot.fileName}"
        val serviceHash = sha256(plan.targetRoot.toString().toByteArray()).value.take(32)
        val serviceLabel = "io.github.amichne.kast.broker.$serviceHash"
        val anchors = buildList {
            add(ExternalAnchor("current", plan.currentLink.toString(), expectedLinkTarget = currentTarget))
            add(ExternalAnchor("command", plan.commandLink.toString(), expectedLinkTarget = plan.currentLink.resolve("bin/kast-complete").toString(), requiresCurrentTarget = currentTarget))
            add(ExternalAnchor("codex-command", plan.codexCommandLink.toString(), expectedLinkTarget = plan.currentLink.resolve("bin/kast-codex-complete").toString(), requiresCurrentTarget = currentTarget))
            add(ExternalAnchor("login", plan.request.home.value.resolve("Library/LaunchAgents/$serviceLabel.login.plist").toString(), expectedExecutable = plan.targetRoot.resolve("bin/kast").toString(), expectedLabel = "$serviceLabel.login"))
            val run = plan.targetRoot.resolve("state/run")
            if (run.resolve("kast-${"0".repeat(43)}.sock").toString().toByteArray().size >= 104) {
                val alias = Path.of("/tmp/kast-uds-${sha256(run.toString().toByteArray()).value.take(32)}")
                add(ExternalAnchor("socket-alias", alias.toString(), expectedLinkTarget = run.toString(), identityReceipt = run.resolve("endpoint-alias.json").toString()))
            }
            val upstream = InstalledUpstreamDirectories.transportPath(run.resolve("u.sock"))
            if (upstream != run.resolve("u.sock")) {
                add(ExternalAnchor(
                    "upstream-directory",
                    upstream.parent.toString(),
                    identityReceipt = run.resolve("upstream-directory.json").toString(),
                    expectedPhysicalDirectory = run.toString(),
                ))
            }
        }
        val manifest = InstallationManifest(
            semanticVersion = plan.request.version.toString(),
            installationRoot = plan.targetRoot.toString(),
            payloadIdentity = "sha256:${plan.payloadDigest.value}",
            controlSha256 = "sha256:${plan.request.controlDigest.value}",
            runtimeSha256 = "sha256:${plan.request.runtimeDigest.value}",
            codexHome = plan.request.codexHome.value.toString(),
            configuration = plan.configuration.toString(),
            workspaceRegistry = plan.targetRoot.resolve("config/workspaces.json").toString(),
            stateRoot = plan.targetRoot.resolve("state").toString(),
            externalAnchors = anchors,
            payloadFiles = payloadFiles,
        )
        Files.writeString(
            staged.resolve("installation.json"),
            manifestJson.encodeToString(InstallationManifest.serializer(), manifest) + "\n",
            StandardOpenOption.CREATE_NEW,
        )
    }

    private fun admitExisting(plan: VerifiedInstallationPlan): Boolean {
        if (!physicalDirectory(plan.targetRoot)) return false
        val raw = readBounded(plan.targetRoot.resolve("installation.json"), MAXIMUM_MANIFEST_BYTES) ?: return false
        val manifest = try {
            manifestJson.decodeFromString(InstallationManifest.serializer(), raw)
        } catch (_: SerializationException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        return manifest.schemaVersion == 1 &&
            manifest.semanticVersion == plan.request.version.toString() &&
            manifest.installationRoot == plan.targetRoot.toString() &&
            manifest.payloadIdentity == "sha256:${plan.payloadDigest.value}" &&
            manifest.controlSha256 == "sha256:${plan.request.controlDigest.value}" &&
            manifest.runtimeSha256 == "sha256:${plan.request.runtimeDigest.value}" &&
            manifest.payloadFiles == payloadFiles(plan.targetRoot)
    }

    private fun selectedInstallation(plan: VerifiedInstallationPlan): PriorSelection {
        if (!Files.exists(plan.currentLink, LinkOption.NOFOLLOW_LINKS)) return PriorSelection.Absent
        if (!Files.isSymbolicLink(plan.currentLink)) return PriorSelection.Rejected
        val target = Files.readSymbolicLink(plan.currentLink)
        if (target.nameCount != 2 || target.getName(0).toString() != "versions") return PriorSelection.Rejected
        val resolved = plan.request.installRoot.value.resolve(target).normalize()
        if (resolved.parent != plan.versionsRoot || !physicalDirectory(resolved)) return PriorSelection.Rejected
        return PriorSelection.Selected(resolved)
    }

    private fun retire(prior: Path, request: InstallationRequest): Boolean {
        val executable = prior.resolve("bin/kast-complete")
        if (!regularExecutable(executable)) return false
        val recorded = PublishedBrokerServiceCommand.retirementEnvironment(
            installationRoot = prior,
            userHome = request.home.value,
            codexHome = request.codexHome.value,
        )
        return run(
            listOf(executable.toString(), "app-server", "disable"),
            recorded?.values ?: priorServiceRetirementEnvironment(
                prior = prior,
                home = request.home.value,
                codexHome = request.codexHome.value,
                path = System.getenv("PATH") ?: "/usr/bin:/bin",
            ),
        ) == 0
    }

    private fun admitPrior(prior: Path, request: InstallationRequest): Boolean {
        val executable = prior.resolve("bin/kast-complete")
        if (!regularExecutable(executable)) return false
        return run(
            listOf(executable.toString(), "installation", "inspect", "--json"),
            mapOf(
                "HOME" to request.home.value.toString(),
                "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "CODEX_HOME" to request.codexHome.value.toString(),
            ),
        ) == 0
    }

    private fun validateConfiguration(plan: VerifiedInstallationPlan): Boolean = run(
        listOf(
            plan.targetRoot.resolve("bin/kast-complete").toString(),
            "config",
            "validate",
            "--file",
            plan.configuration.toString(),
            "--json",
        ),
        mapOf(
            "HOME" to plan.request.home.value.toString(),
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        ),
    ) == 0

    private fun activate(plan: VerifiedInstallationPlan): ActivationResult {
        val priorCurrent = linkTarget(plan.currentLink)
        val priorCommand = managedCommandLink(plan.commandLink, plan.currentLink.resolve("bin/kast-complete"))
        val priorCodex = managedCommandLink(plan.codexCommandLink, plan.currentLink.resolve("bin/kast-codex-complete"))
        if (priorCurrent is LinkObservation.Rejected || priorCommand is LinkObservation.Rejected || priorCodex is LinkObservation.Rejected) {
            return ActivationResult.Rejected
        }
        return try {
            replaceLink(plan.currentLink, Path.of("versions/${plan.targetRoot.fileName}"))
            replaceLink(plan.commandLink, plan.currentLink.resolve("bin/kast-complete"))
            if (regularExecutable(plan.targetRoot.resolve("bin/kast-codex-complete"))) {
                replaceLink(plan.codexCommandLink, plan.currentLink.resolve("bin/kast-codex-complete"))
            } else {
                Files.deleteIfExists(plan.codexCommandLink)
            }
            if (
                run(
                    listOf(plan.commandLink.toString(), "--version"),
                    mapOf(
                        "HOME" to plan.request.home.value.toString(),
                        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                    ),
                ) != 0
            ) throw IOException("installed command qualification rejected")
            ActivationResult.Complete
        } catch (_: IOException) {
            restoreLink(plan.currentLink, priorCurrent)
            restoreLink(plan.commandLink, priorCommand)
            restoreLink(plan.codexCommandLink, priorCodex)
            ActivationResult.Rejected
        }
    }

    private fun enableAppServer(plan: VerifiedInstallationPlan): Boolean = run(
        listOf(plan.commandLink.toString(), "app-server", "enable"),
        mapOf(
            "HOME" to plan.request.home.value.toString(),
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
            "CODEX_HOME" to plan.request.codexHome.value.toString(),
        ),
    ) == 0

    private fun run(command: List<String>, environment: Map<String, String>): Int = try {
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .start()
        if (!process.waitFor(Duration.ofMillis(InstallationOperationalLimits.retirementChildTimeoutMillis))) {
            process.destroyForcibly()
            process.waitFor()
            -1
        } else {
            process.exitValue()
        }
    } catch (_: IOException) {
        -1
    }

    private fun acquire(channel: FileChannel): java.nio.channels.FileLock? {
        val deadline = System.nanoTime() + Duration.ofMillis(InstallationOperationalLimits.activationLockTimeoutMillis).toNanos()
        while (System.nanoTime() < deadline) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            Thread.sleep(InstallationOperationalLimits.activationLockPollMillis)
        }
        return null
    }
}

private sealed interface PlanVerification {
    data class Verified(val plan: VerifiedInstallationPlan) : PlanVerification
    data class Rejected(val failure: InstallationFailure) : PlanVerification
}

private sealed interface StageResult {
    data object Complete : StageResult
    data class Rejected(val failure: InstallationFailure) : StageResult
}

private sealed interface PriorSelection {
    data object Absent : PriorSelection
    data class Selected(val root: Path) : PriorSelection
    data object Rejected : PriorSelection
}

private sealed interface ActivationResult {
    data object Complete : ActivationResult
    data object Rejected : ActivationResult
}

private sealed interface LinkObservation {
    data object Absent : LinkObservation
    data class Present(val target: Path) : LinkObservation
    data object Rejected : LinkObservation
}

private fun InstallationSwitch.wireValue(): Int = when (this) {
    InstallationSwitch.DISABLED -> 0
    InstallationSwitch.ENABLED -> 1
}

private fun physicalDirectory(path: Path): Boolean = try {
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && path.toRealPath() == path
} catch (_: IOException) {
    false
}

private fun regularFile(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

private fun regularExecutable(path: Path): Boolean = regularFile(path) && Files.isExecutable(path)

internal fun secureActivationLock(path: Path): Boolean = try {
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

private fun fileSize(path: Path): Long = try {
    Files.size(path)
} catch (_: IOException) {
    -1
}

private fun readBounded(path: Path, limit: Long): String? = try {
    if (!regularFile(path) || Files.size(path) > limit) null else Files.readString(path)
} catch (_: IOException) {
    null
}

private fun digest(path: Path): Sha256? = try {
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

private fun sha256(bytes: ByteArray): Sha256 = when (val value = Sha256.parse(
    MessageDigest.getInstance("SHA-256").digest(bytes).hex(),
)) {
    is io.github.amichne.kast.kernel.Refinement.Refined -> value.value
    is io.github.amichne.kast.kernel.Refinement.Rejected -> error("SHA-256 provider emitted an invalid digest")
}

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun verifyControlLayout(root: Path): Boolean {
    if (
        !regularExecutable(root.resolve("bin/kast")) ||
        !regularFile(root.resolve("share/kast/semantic-runtime.json")) ||
        !regularFile(root.resolve("share/kast/operation-registry.json")) ||
        !regularFile(root.resolve("share/kast/wire-schema.json")) ||
        !regularFile(root.resolve("share/kast/installation-lifecycle.py"))
    ) return false
    var files = 0
    var bytes = 0L
    return try {
        for (directoryName in listOf("bin", "lib", "share")) {
            val directory = root.resolve(directoryName)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue
            Files.walk(directory).use { entries ->
                entries.forEach { entry ->
                    if (Files.isSymbolicLink(entry)) throw IOException("link")
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        files += 1
                        bytes += Files.size(entry)
                        if (files > MAXIMUM_CONTROL_FILES || bytes > MAXIMUM_CONTROL_BYTES) throw IOException("limit")
                    }
                }
            }
        }
        true
    } catch (_: IOException) {
        false
    }
}

private fun verifyRuntimeArchive(archive: Path, required: List<String>): Boolean = try {
    ZipFile(archive.toFile()).use { zip ->
        val names = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            if (names.size >= MAXIMUM_CONTROL_FILES) return false
            val entry = entries.nextElement()
            val name = entry.name
            val path = Path.of(name).normalize()
            if (
                name.isBlank() || name.startsWith('/') || path.isAbsolute || path.toString() != name.trimEnd('/') ||
                name.split('/').any { it == ".." } ||
                name == "idea-home" || name.startsWith("idea-home/") ||
                name.endsWith("/product-info.json") || name.contains("/plugins/Kotlin/") || name.contains("/plugins/gradle/")
            ) return false
            names += name.trimEnd('/')
        }
        required.map { it.trimEnd('/') }.all(names::contains)
    }
} catch (_: IOException) {
    false
} catch (_: RuntimeException) {
    false
}

private fun prepareOwnedDirectory(path: Path): Boolean = try {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        physicalDirectory(path)
    } else {
        Files.createDirectories(path)
        physicalDirectory(path)
    }
} catch (_: IOException) {
    false
}

private fun payloadFiles(root: Path): List<PayloadFile> {
    val result = mutableListOf<PayloadFile>()
    var bytes = 0L
    for (directoryName in listOf("bin", "lib", "share")) {
        val directory = root.resolve(directoryName)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue
        Files.walk(directory).use { entries ->
            entries.sorted().forEach { file ->
                if (Files.isSymbolicLink(file)) throw IOException("payload rejected")
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (result.size >= MAXIMUM_CONTROL_FILES) throw IOException("payload rejected")
                bytes += Files.size(file)
                if (bytes > MAXIMUM_CONTROL_BYTES) throw IOException("payload rejected")
                result += PayloadFile(
                    root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                    "sha256:${digest(file)?.value ?: throw IOException("payload unreadable")}",
                    mode(file),
                )
            }
        }
    }
    return result
}

private fun mode(path: Path): Int = try {
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
    ).sumOf { (permission, bit) -> if (permission in permissions) bit else 0 }
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

private fun move(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun linkTarget(path: Path): LinkObservation = when {
    !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> LinkObservation.Absent
    !Files.isSymbolicLink(path) -> LinkObservation.Rejected
    else -> LinkObservation.Present(Files.readSymbolicLink(path))
}

private fun managedCommandLink(path: Path, expected: Path): LinkObservation = when (val observed = linkTarget(path)) {
    LinkObservation.Absent -> observed
    is LinkObservation.Present -> if (observed.target == expected) observed else LinkObservation.Rejected
    LinkObservation.Rejected -> observed
}

private fun replaceLink(path: Path, target: Path) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".link")
    try {
        Files.delete(temporary)
        Files.createSymbolicLink(temporary, target)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun restoreLink(path: Path, observation: LinkObservation) {
    try {
        when (observation) {
            LinkObservation.Absent -> Files.deleteIfExists(path)
            is LinkObservation.Present -> replaceLink(path, observation.target)
            LinkObservation.Rejected -> Unit
        }
    } catch (_: IOException) {
        // The caller already returns ACTIVATION_REJECTED; no weaker success is manufactured.
    }
}

private fun deleteTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            if (failure != null) throw failure
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

private val manifestJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
