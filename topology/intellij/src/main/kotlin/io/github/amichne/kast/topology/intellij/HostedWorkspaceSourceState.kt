package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspacePublicationSerialization
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

sealed interface HostedWorkspaceSourceStateAdmissionFailure {
    data class ProjectRejected(
        val failure: HostedProjectAdmissionFailure,
    ) : HostedWorkspaceSourceStateAdmissionFailure

    data object SourceRootUnavailable : HostedWorkspaceSourceStateAdmissionFailure
    data object SourceContentUnavailable : HostedWorkspaceSourceStateAdmissionFailure
    data object AmbiguousSourceRootOwner : HostedWorkspaceSourceStateAdmissionFailure
    data object SourceStateRejected : HostedWorkspaceSourceStateAdmissionFailure
}

data class HostedWorkspaceSourcePublication(
    val sourceState: WorkspaceStateIdentity,
    val generation: EvidenceGeneration,
)

sealed interface HostedWorkspaceSourceResumption {
    data class Resumed(
        val publication: HostedWorkspaceSourcePublication,
    ) : HostedWorkspaceSourceResumption

    data class Rejected(
        val failure: HostedWorkspaceSourceStateAdmissionFailure,
    ) : HostedWorkspaceSourceResumption
}

/** Durable boundary that resumes the latest state descended from one bounded workspace basis. */
fun interface HostedWorkspaceSourceResumptionOperations {
    fun resume(basis: WorkspaceStateIdentity): HostedWorkspaceSourceResumption
}

/** One explicit cold-project epoch; a fresh project service can never reuse an unverified basis. */
@JvmInline
value class HostedWorkspaceColdStartIdentity private constructor(
    val value: String,
) {
    companion object {
        fun issue(): HostedWorkspaceColdStartIdentity =
            HostedWorkspaceColdStartIdentity(UUID.randomUUID().toString())

        internal fun testing(value: String): HostedWorkspaceColdStartIdentity {
            require(value.isNotBlank())
            return HostedWorkspaceColdStartIdentity(value)
        }
    }
}

sealed interface HostedWorkspaceSourceStateAdmission {
    data class Admitted(
        val publication: HostedWorkspaceSourcePublication,
        val observations: HostedWorkspaceSourceStateOperations,
        val serialization: WorkspacePublicationSerialization,
    ) : HostedWorkspaceSourceStateAdmission {
        val sourceState: WorkspaceStateIdentity
            get() = publication.sourceState
    }

    data class Rejected(
        val failure: HostedWorkspaceSourceStateAdmissionFailure,
    ) : HostedWorkspaceSourceStateAdmission
}

sealed interface HostedWorkspaceSourceStateObservation {
    data class Observed(
        val sourceState: WorkspaceStateIdentity,
    ) : HostedWorkspaceSourceStateObservation

    data class Rejected(
        val failure: HostedWorkspaceSourceStateAdmissionFailure,
    ) : HostedWorkspaceSourceStateObservation
}

sealed interface HostedWorkspaceSourceInvalidation {
    data object Invalidated : HostedWorkspaceSourceInvalidation
    data class Rejected(
        val failure: HostedWorkspaceSourceStateAdmissionFailure,
    ) : HostedWorkspaceSourceInvalidation
}

/** Constant-size observation and explicit refinement of source transitions in one project. */
interface HostedWorkspaceSourceStateOperations {
    fun observe(): HostedWorkspaceSourceStateObservation
    fun invalidate(): HostedWorkspaceSourceInvalidation
}

/**
 * One project-service-owned source admission. Deferred endpoint attempts reuse its exact listener,
 * event counter, and publication; a changed admission input cannot silently replace that state.
 */
class HostedWorkspaceSourceStateSession(
    private val coldStart: HostedWorkspaceColdStartIdentity,
    private val lifecycle: Disposable,
) {
    private var cached: CachedHostedWorkspaceSourceState? = null

    @Synchronized
    fun admit(
        project: Project,
        root: CanonicalWorkspaceRoot,
        compatibilityCandidate: IdeHostCompatibilityCandidate,
        compatibilityPolicy: IdeHostCompatibilityPolicy,
        sourceRoots: List<SourceRoot>,
        resumptions: HostedWorkspaceSourceResumptionOperations,
    ): HostedWorkspaceSourceStateAdmission {
        val current = cached
        if (current != null) {
            return if (
                current.project === project &&
                current.root == root &&
                current.compatibilityCandidate == compatibilityCandidate &&
                current.compatibilityPolicy == compatibilityPolicy &&
                current.sourceRoots == sourceRoots
            ) {
                current.admission
            } else {
                rejected(HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected)
            }
        }
        return when (val admission = admitHostedWorkspaceSourceState(
            project,
            root,
            compatibilityCandidate,
            compatibilityPolicy,
            sourceRoots,
            resumptions,
            coldStart,
            lifecycle,
        )) {
            is HostedWorkspaceSourceStateAdmission.Admitted -> {
                cached = CachedHostedWorkspaceSourceState(
                    project,
                    root,
                    compatibilityCandidate,
                    compatibilityPolicy,
                    sourceRoots.toList(),
                    admission,
                )
                admission
            }
            is HostedWorkspaceSourceStateAdmission.Rejected -> admission
        }
    }
}

private data class CachedHostedWorkspaceSourceState(
    val project: Project,
    val root: CanonicalWorkspaceRoot,
    val compatibilityCandidate: IdeHostCompatibilityCandidate,
    val compatibilityPolicy: IdeHostCompatibilityPolicy,
    val sourceRoots: List<SourceRoot>,
    val admission: HostedWorkspaceSourceStateAdmission.Admitted,
)

/**
 * Verifies the already-open exact Project, installs a source-root-filtered VFS transition
 * listener, and resumes the last durable state in the bounded workspace lineage. Startup performs
 * neither repository enumeration nor source-content reads.
 */
fun admitHostedWorkspaceSourceState(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    sourceRoots: List<SourceRoot>,
    resumptions: HostedWorkspaceSourceResumptionOperations,
    coldStart: HostedWorkspaceColdStartIdentity,
    lifecycle: Disposable,
): HostedWorkspaceSourceStateAdmission {
    when (val validation = ExistingProjectValidation.validate(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        ExistingProjectValidation.Validated -> Unit
        is ExistingProjectValidation.Rejected -> return rejected(
            HostedWorkspaceSourceStateAdmissionFailure.ProjectRejected(
                HostedProjectAdmissionFailure.ProjectRejected(validation.failure),
            ),
        )
    }
    val basis = when (val admission = admitHostedWorkspaceSourceBasis(
        root,
        sourceRoots,
        coldStart,
    )) {
        is HostedWorkspaceSourceBasisAdmission.Admitted -> admission.basis
        is HostedWorkspaceSourceBasisAdmission.Rejected -> return rejected(admission.failure)
    }
    val serialization = WorkspacePublicationSerialization()
    val eventCounter = HostedWorkspaceSourceEventCounter(serialization)
    val listener = HostedWorkspaceSourceVfsListener(basis.roots, eventCounter)
    val subscribed = runCatching {
        project.messageBus.connect(lifecycle).subscribe(VirtualFileManager.VFS_CHANGES, listener)
    }.isSuccess
    if (!subscribed) {
        return rejected(HostedWorkspaceSourceStateAdmissionFailure.SourceContentUnavailable)
    }
    val publication = when (val resumed = resumptions.resume(basis.identity)) {
        is HostedWorkspaceSourceResumption.Resumed -> resumed.publication
        is HostedWorkspaceSourceResumption.Rejected -> return rejected(resumed.failure)
    }
    return HostedWorkspaceSourceStateAdmission.Admitted(
        publication,
        liveHostedWorkspaceSourceStateOperations(publication.sourceState, eventCounter),
        serialization,
    )
}

internal sealed interface HostedWorkspaceSourceBasisAdmission {
    data class Admitted(val basis: HostedWorkspaceSourceBasis) :
        HostedWorkspaceSourceBasisAdmission

    data class Rejected(val failure: HostedWorkspaceSourceStateAdmissionFailure) :
        HostedWorkspaceSourceBasisAdmission
}

internal class HostedWorkspaceSourceBasis(
    val identity: WorkspaceStateIdentity,
    val roots: HostedWorkspacePhysicalSourceRoots,
)

/** Pure bounded refinement of detached workspace-model evidence into a durable lineage basis. */
internal fun admitHostedWorkspaceSourceBasis(
    root: CanonicalWorkspaceRoot,
    sourceRoots: List<SourceRoot>,
    coldStart: HostedWorkspaceColdStartIdentity,
): HostedWorkspaceSourceBasisAdmission {
    if (sourceRoots.size > MAX_SOURCE_ROOTS) {
        return basisRejected(HostedWorkspaceSourceStateAdmissionFailure.SourceRootUnavailable)
    }
    val workspaceRoot = Path.of(root.value)
    val physicalRoots = sourceRoots.map { sourceRoot ->
        workspaceRoot.resolve(sourceRoot.location.value).normalize()
    }
    if (physicalRoots.any { physicalRoot ->
            !physicalRoot.isAbsolute || !physicalRoot.startsWith(workspaceRoot)
        }
    ) {
        return basisRejected(HostedWorkspaceSourceStateAdmissionFailure.SourceRootUnavailable)
    }
    val orderedRoots = physicalRoots.sortedBy(Path::toString)
    if (orderedRoots.zipWithNext().any { (left, right) -> right.startsWith(left) }) {
        return basisRejected(
            HostedWorkspaceSourceStateAdmissionFailure.AmbiguousSourceRootOwner,
        )
    }
    val roots = when (val admission = HostedWorkspacePhysicalSourceRoots.admit(physicalRoots)) {
        is Refinement.Refined -> admission.value
        is Refinement.Rejected -> return basisRejected(admission.failure)
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("kast-hosted-source-basis-v3".toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(root.value.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(coldStart.value.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    sourceRoots.sortedBy { it.location.value }.forEach { sourceRoot ->
        digest.update(sourceRoot.owner.module.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(sourceRoot.owner.project.buildRoot.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(sourceRoot.owner.project.projectPath.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(sourceRoot.owner.sourceSet.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(sourceRoot.location.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(sourceRoot.provenance.basisToken().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return when (
        val parsed = WorkspaceStateIdentity.parse(HexFormat.of().formatHex(digest.digest()))
    ) {
        is Refinement.Refined -> HostedWorkspaceSourceBasisAdmission.Admitted(
            HostedWorkspaceSourceBasis(parsed.value, roots),
        )
        is Refinement.Rejected -> basisRejected(
            HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
        )
    }
}

internal class HostedWorkspacePhysicalSourceRoots private constructor(
    private val paths: List<Path>,
) {
    fun contains(candidate: Path): Boolean = paths.any { sourceRoot ->
        candidate.startsWith(sourceRoot) || sourceRoot.startsWith(candidate)
    }

    companion object {
        fun admit(
            paths: List<Path>,
        ): Refinement<HostedWorkspacePhysicalSourceRoots, HostedWorkspaceSourceStateAdmissionFailure> =
            if (paths.size > MAX_SOURCE_ROOTS) {
                Refinement.Rejected(
                    HostedWorkspaceSourceStateAdmissionFailure.SourceRootUnavailable,
                )
            } else {
                Refinement.Refined(HostedWorkspacePhysicalSourceRoots(paths.toList()))
            }
    }
}

internal sealed interface HostedWorkspaceSourceEventRevision {
    data class Current(val value: Long) : HostedWorkspaceSourceEventRevision
    data class Rejected(val failure: HostedWorkspaceSourceStateAdmissionFailure) :
        HostedWorkspaceSourceEventRevision
}

internal class HostedWorkspaceSourceEventCounter(
    private val serialization: WorkspacePublicationSerialization =
        WorkspacePublicationSerialization(),
) {
    private val revision = AtomicReference<HostedWorkspaceSourceEventRevision>(
        HostedWorkspaceSourceEventRevision.Current(0),
    )

    fun advance() = serialization.serialized {
        while (true) when (val current = revision.get()) {
            is HostedWorkspaceSourceEventRevision.Rejected -> return@serialized
            is HostedWorkspaceSourceEventRevision.Current -> {
                val next = if (current.value == Long.MAX_VALUE) {
                    HostedWorkspaceSourceEventRevision.Rejected(
                        HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
                    )
                } else {
                    HostedWorkspaceSourceEventRevision.Current(current.value + 1)
                }
                if (revision.compareAndSet(current, next)) return@serialized
            }
        }
    }

    fun reject(failure: HostedWorkspaceSourceStateAdmissionFailure) = serialization.serialized {
        while (true) {
            val current = revision.get()
            if (current is HostedWorkspaceSourceEventRevision.Rejected) return@serialized
            if (revision.compareAndSet(current, HostedWorkspaceSourceEventRevision.Rejected(failure))) {
                return@serialized
            }
        }
    }

    fun sample(): HostedWorkspaceSourceEventRevision = serialization.serialized { revision.get() }
}

private data class ObservedHostedWorkspaceSourceState(
    val sourceState: WorkspaceStateIdentity,
    val eventRevision: Long,
)

internal fun liveHostedWorkspaceSourceStateOperations(
    initial: WorkspaceStateIdentity,
    events: HostedWorkspaceSourceEventCounter,
): HostedWorkspaceSourceStateOperations {
    val state = AtomicReference(ObservedHostedWorkspaceSourceState(initial, 0))
    return object : HostedWorkspaceSourceStateOperations {
        override fun observe(): HostedWorkspaceSourceStateObservation {
            while (true) {
                val revision = when (val sampled = events.sample()) {
                    is HostedWorkspaceSourceEventRevision.Current -> sampled.value
                    is HostedWorkspaceSourceEventRevision.Rejected ->
                        return HostedWorkspaceSourceStateObservation.Rejected(sampled.failure)
                }
                val current = state.get()
                if (revision == current.eventRevision) {
                    return HostedWorkspaceSourceStateObservation.Observed(current.sourceState)
                }
                if (revision < current.eventRevision) {
                    return HostedWorkspaceSourceStateObservation.Rejected(
                        HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
                    )
                }
                val next = when (
                    val transitioned = transitionHostedWorkspaceSourceState(
                        current.sourceState,
                        revision,
                    )
                ) {
                    is Refinement.Refined -> ObservedHostedWorkspaceSourceState(
                        transitioned.value,
                        revision,
                    )
                    is Refinement.Rejected ->
                        return HostedWorkspaceSourceStateObservation.Rejected(
                            HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
                        )
                }
                if (state.compareAndSet(current, next)) {
                    return HostedWorkspaceSourceStateObservation.Observed(next.sourceState)
                }
            }
        }

        override fun invalidate(): HostedWorkspaceSourceInvalidation {
            val current = events.sample()
            if (current is HostedWorkspaceSourceEventRevision.Rejected) {
                return HostedWorkspaceSourceInvalidation.Rejected(current.failure)
            }
            events.advance()
            return when (val advanced = events.sample()) {
                is HostedWorkspaceSourceEventRevision.Current ->
                    HostedWorkspaceSourceInvalidation.Invalidated
                is HostedWorkspaceSourceEventRevision.Rejected ->
                    HostedWorkspaceSourceInvalidation.Rejected(advanced.failure)
            }
        }
    }
}

private fun transitionHostedWorkspaceSourceState(
    current: WorkspaceStateIdentity,
    eventRevision: Long,
): Refinement<WorkspaceStateIdentity, *> {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("kast-hosted-source-transition-v2".toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(current.value.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    digest.update(eventRevision.toString().toByteArray(StandardCharsets.UTF_8))
    return WorkspaceStateIdentity.parse(HexFormat.of().formatHex(digest.digest()))
}

private fun SourceRootProvenance.basisToken(): String = when (this) {
    SourceRootProvenance.Authored -> "authored"
    SourceRootProvenance.Generated -> "generated"
    is SourceRootProvenance.Unknown -> when (reason) {
        io.github.amichne.kast.workspace.contract.ProvenanceFailure.ExcludedFromSourceModel ->
            "unknown:excluded-from-source-model"
    }
}

private class HostedWorkspaceSourceVfsListener(
    private val roots: HostedWorkspacePhysicalSourceRoots,
    private val counter: HostedWorkspaceSourceEventCounter,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.size > MAX_VFS_EVENTS_PER_BATCH) {
            counter.reject(HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected)
            return
        }
        var touchesSource = false
        for (event in events) {
            val paths = when (event) {
                is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                is VFilePropertyChangeEvent -> if (event.isRename) {
                    listOf(event.oldPath, event.newPath)
                } else {
                    listOf(event.path)
                }
                else -> listOf(event.path)
            }
            for (raw in paths) {
                val path = admitVfsEventPath(raw) ?: run {
                    counter.reject(
                        HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
                    )
                    return
                }
                if (roots.contains(path)) touchesSource = true
            }
        }
        if (touchesSource) counter.advance()
    }
}

private fun admitVfsEventPath(raw: String): Path? {
    if (
        raw.isEmpty() ||
        raw.length > MAX_VFS_PATH_CHARACTERS ||
        raw.toByteArray(StandardCharsets.UTF_8).size > MAX_VFS_PATH_UTF8_BYTES
    ) {
        return null
    }
    val path = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return null
    }
    return path.takeIf { it.isAbsolute && it.normalize() == it }
}

private fun rejected(
    failure: HostedWorkspaceSourceStateAdmissionFailure,
): HostedWorkspaceSourceStateAdmission.Rejected =
    HostedWorkspaceSourceStateAdmission.Rejected(failure)

private fun basisRejected(
    failure: HostedWorkspaceSourceStateAdmissionFailure,
): HostedWorkspaceSourceBasisAdmission.Rejected =
    HostedWorkspaceSourceBasisAdmission.Rejected(failure)

private const val MAX_SOURCE_ROOTS = 4_096
private const val MAX_VFS_EVENTS_PER_BATCH = 4_096
private const val MAX_VFS_PATH_CHARACTERS = 4_096
private const val MAX_VFS_PATH_UTF8_BYTES = 8_192
