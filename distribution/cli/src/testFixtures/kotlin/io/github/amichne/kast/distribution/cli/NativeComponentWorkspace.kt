package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable

/** Producer-supplied component identities, not an installed-product selection or qualification receipt. */
@Serializable
data class NativeComponentInput(
    val store: String,
    val workspace: String,
    val runtimeArchive: String,
    val runtimeSha256: String,
    val control: String,
    val controlSha256: String,
    val version: String,
    val sourceRevision: String,
    val preparationWaitMillis: Long = 900_000,
)

@Serializable
data class NativeComponentIdentity(
    val sourceRevision: String,
    val version: String,
    val platform: String,
    val runtimeSha256: String,
    val runtimeManifestSha256: String,
    val controlSha256: String,
)

enum class NativeComponentAccess {
    PREPARE,
    EXISTING_AUTHORITY,
}

enum class NativeComponentStop {
    SELECTED,
    ALL,
}

/** Exact profile selected by production archive, workspace and generation admission. */
class NativeComponentDeployment internal constructor(container: AdmittedContainer) {
    val workspace: Path = container.workspace
    val workspaceId: String = container.id.text
    val profile: Path = container.profile
    val home: Path = container.home
    val state: Path = container.state
    val runtimeHome: Path = container.runtime.home
    val runtimeSha256: String = container.runtime.archiveSha256
    val environment: Map<String, String> = containerEnvironment(container, emptyMap())
    val javaHome: Path = Path.of(environment.getValue("JAVA_HOME"))
}

/** This bridge is present only in test fixtures. It cannot produce InstalledProductRuntime. */
class NativeComponentWorkspace
private constructor(
    private val store: ContainerStore,
    private val runtime: AdmittedContainerRuntime,
    val root: CanonicalRoot,
    val identity: NativeComponentIdentity,
    private val allowance: ElapsedTimeAllowance,
) {
    fun open(access: NativeComponentAccess): Refinement<NativeComponentLease, ContainerFailure> =
        ContainerQueryLease.duringStoreAdmission(store.root) {
            when (val workspace = store.workspace(root.path)) {
                is Refinement.Rejected -> workspace
                is Refinement.Refined ->
                    when (val lease = ContainerQueryLease.acquire(workspace.value, store.platform)) {
                        is Refinement.Rejected -> lease
                        is Refinement.Refined -> bind(lease.value, access)
                    }
            }
        }

    private fun bind(
        lease: ContainerQueryLease,
        access: NativeComponentAccess,
    ): Refinement<NativeComponentLease, ContainerFailure> {
        val waiting = NativeComponentWait()
        var transferred = false
        try {
            val generation =
                when (access) {
                    NativeComponentAccess.PREPARE -> prepareContainerGeneration(lease, runtime, allowance, waiting)
                    NativeComponentAccess.EXISTING_AUTHORITY -> lease.admitGeneration()
                }
            return when (generation) {
                is Refinement.Rejected -> generation
                is Refinement.Refined ->
                    if (generation.value.runtime.archiveSha256 != runtime.archiveSha256) {
                        Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                    } else {
                        transferred = true
                        Refinement.Refined(
                            NativeComponentLease(
                                lease = lease,
                                container = generation.value,
                                allowance = allowance,
                                waiting = waiting,
                            )
                        )
                    }
            }
        } finally {
            if (!transferred) lease.close()
        }
    }

    /** The CLI test adapter supplies real settled Host retirement; no default successful callback exists. */
    fun stop(
        scope: NativeComponentStop,
        retire: (NativeComponentDeployment) -> Refinement<Unit, ContainerFailure>,
    ): Refinement<List<String>, ContainerFailure> =
        stopManagedWorkspaces(
            store,
            when (scope) {
                NativeComponentStop.SELECTED -> WorkspaceScope.Current
                NativeComponentStop.ALL -> WorkspaceScope.All
            },
            root.path,
        ) {
            retire(NativeComponentDeployment(it))
        }

    private fun create(): Refinement<NativeComponentWorkspace, ContainerFailure> =
        ContainerQueryLease.duringStoreAdmission(store.root) {
            when (val existing = store.workspace(root.path)) {
                is Refinement.Refined -> Refinement.Refined(this)
                is Refinement.Rejected ->
                    if (existing.failure != ContainerFailure.NOT_FOUND) existing else createMissing()
            }
        }

    private fun createMissing(): Refinement<NativeComponentWorkspace, ContainerFailure> =
        when (
            val creation =
                store.admitCreation(
                    ContainerCommand.Create(name = null, root = root.path.toString(), gradleHome = null, json = false),
                    root.path,
                )
        ) {
            is Refinement.Rejected -> creation
            is Refinement.Refined ->
                store.withStagedCreation(creation.value, runtime) { staged ->
                    withContainerPublicationLock(store.root, allowance, NativeComponentWait()) {
                        publish(staged)
                    }
                }
        }

    private fun publish(staged: StagedContainerCreation): Refinement<NativeComponentWorkspace, ContainerFailure> =
        when (val existing = store.workspace(root.path)) {
            is Refinement.Refined -> Refinement.Refined(this)
            is Refinement.Rejected ->
                if (existing.failure != ContainerFailure.NOT_FOUND) existing
                else {
                    when (val published = store.publishCreation(staged)) {
                        is Refinement.Rejected -> published
                        is Refinement.Refined -> Refinement.Refined(this)
                    }
                }
        }

    companion object {
        /** Imports the digest-verified ZIP and creates exact-root trust, but starts no Host. */
        fun admit(input: NativeComponentInput): Refinement<NativeComponentWorkspace, ContainerFailure> =
            when (val selected = select(input, import = true)) {
                is Refinement.Rejected -> selected
                is Refinement.Refined -> selected.value.create()
            }

        /** Subsequent commands cannot import missing runtime bytes or create workspace trust. */
        fun select(input: NativeComponentInput): Refinement<NativeComponentWorkspace, ContainerFailure> =
            select(input, import = false)

        private fun select(
            input: NativeComponentInput,
            import: Boolean,
        ): Refinement<NativeComponentWorkspace, ContainerFailure> = containerFiles {
            val root =
                when (val admitted = admitComponentInputs(input)) {
                    is Refinement.Rejected -> return@containerFiles admitted
                    is Refinement.Refined -> admitted.value
                }
            val platform =
                when (val current = ContainerPlatform.current()) {
                    is Refinement.Rejected -> return@containerFiles current
                    is Refinement.Refined -> current.value
                }
            val allowance =
                when (val parsed = ElapsedTimeAllowance.admitMillis(input.preparationWaitMillis)) {
                    is Refinement.Rejected ->
                        return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_CONFIGURATION)
                    is Refinement.Refined -> parsed.value
                }
            val store = ContainerStore(Path.of(input.store), platform)
            val runtime =
                when (val admitted = selectComponentRuntime(input, store, import)) {
                    is Refinement.Rejected -> return@containerFiles admitted
                    is Refinement.Refined -> admitted.value
                }
            Refinement.Refined(
                NativeComponentWorkspace(
                    store = store,
                    runtime = runtime,
                    root = root,
                    identity =
                        NativeComponentIdentity(
                            sourceRevision = input.sourceRevision,
                            version = input.version,
                            platform = platform.publicName,
                            runtimeSha256 = runtime.archiveSha256,
                            runtimeManifestSha256 = sha256(runtime.root.resolve("manifest.json")),
                            controlSha256 = input.controlSha256,
                        ),
                    allowance = allowance,
                )
            )
        }
    }
}

private fun selectComponentRuntime(
    input: NativeComponentInput,
    store: ContainerStore,
    import: Boolean,
): Refinement<AdmittedContainerRuntime, ContainerFailure> {
    val selected =
        if (import) {
            importContainerRuntime(
                archive = Path.of(input.runtimeArchive),
                expected = input.runtimeSha256,
                runtimes = store.runtimes,
                version = input.version,
                platform = store.platform,
            )
        } else AdmittedContainerRuntime.read(store.runtimes.resolve(input.runtimeSha256), store.platform)
    return when (selected) {
        is Refinement.Rejected -> selected
        is Refinement.Refined ->
            if (selected.value.archiveSha256 != input.runtimeSha256 || selected.value.manifest.version != input.version)
                Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
            else selected
    }
}

private fun admitComponentInputs(input: NativeComponentInput): Refinement<CanonicalRoot, ContainerFailure> {
    if (!Regex("[a-f0-9]{40}").matches(input.sourceRevision) || input.sourceRevision.all { it == '0' })
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    if (!componentDigestMatches(Path.of(input.control), input.controlSha256))
        return Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH)
    if (!componentDigestMatches(Path.of(input.runtimeArchive), input.runtimeSha256))
        return Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH)
    val root =
        when (val discovered = FilesystemCanonicalRootDiscovery.discover(Path.of(input.workspace))) {
            is CanonicalRootDiscovery.Rejected -> return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
            is CanonicalRootDiscovery.Discovered -> discovered.root
        }
    if (root.path.toString() != input.workspace) return Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
    return if (validComponentStore(Path.of(input.store), root.path)) Refinement.Refined(root)
    else Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
}

private fun validComponentStore(location: Path, workspace: Path): Boolean {
    if (!location.isAbsolute || location.normalize() != location || location.parent == null) return false
    if (location.parent.toRealPath() != location.parent) return false
    val userHome = Path.of(System.getProperty("user.home")).toRealPath()
    return !userHome.startsWith(location) && !workspace.startsWith(location) && !location.startsWith(workspace)
}

private fun componentDigestMatches(path: Path, expected: String): Boolean =
    regularCanonical(path) && runtimeDigest.matches(expected) && sha256(path) == expected

/** Holds the shared production lease for the complete request, including retained result reads. */
class NativeComponentLease
internal constructor(
    private val lease: ContainerQueryLease,
    container: AdmittedContainer,
    private val allowance: ElapsedTimeAllowance,
    private val waiting: ContainerPreparationWait,
) : AutoCloseable {
    val deployment = NativeComponentDeployment(container)

    fun <T> prepare(action: (ElapsedTimeAllowance) -> T): Refinement<T, ContainerFailure> =
        withContainerPreparationLock(lease, allowance, waiting) { remaining ->
            when (val generation = lease.admitGeneration()) {
                is Refinement.Rejected -> generation
                is Refinement.Refined ->
                    if (generation.value.runtime.archiveSha256 != deployment.runtimeSha256) {
                        Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                    } else
                        when (val resumed = resumeContainerState(generation.value)) {
                            is Refinement.Rejected -> resumed
                            is Refinement.Refined -> Refinement.Refined(action(remaining))
                        }
            }
        }

    override fun close() = lease.close()
}

private fun regularCanonical(path: Path): Boolean =
    path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path

private class NativeComponentWait : ContainerPreparationWait {
    private val start = TimeSource.Monotonic.markNow()

    override fun elapsed(): Duration = start.elapsedNow()

    override fun pause(duration: Duration) = Thread.sleep(duration.inWholeMilliseconds.coerceAtLeast(1))

    override fun admitActive(): Refinement<Unit, ContainerFailure> =
        if (Thread.currentThread().isInterrupted) Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        else Refinement.Refined(Unit)
}
