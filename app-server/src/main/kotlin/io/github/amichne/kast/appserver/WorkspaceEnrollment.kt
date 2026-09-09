package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

@JvmInline
internal value class BrokerWorkspaceId private constructor(val value: String) {
    companion object {
        fun derive(root: CanonicalBrokerDirectory): BrokerWorkspaceId = BrokerWorkspaceId(
            MessageDigest.getInstance("SHA-256").digest(root.path.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }
}

internal data class WorkspaceRegistration(val root: CanonicalBrokerDirectory) {
    val id: BrokerWorkspaceId = BrokerWorkspaceId.derive(root)
}

internal enum class WorkspaceSelectionFailure {
    PATH_REJECTED, REGISTRY_REJECTED, UNREGISTERED, AMBIGUOUS, WORKING_DIRECTORY_OUTSIDE_ROOT,
}

internal sealed interface WorkspaceSelection {
    class Selected private constructor(
        val workspace: WorkspaceRegistration,
        val workingDirectory: CanonicalBrokerDirectory,
    ) : WorkspaceSelection {
        companion object {
            internal fun admit(workspace: WorkspaceRegistration, workingDirectory: CanonicalBrokerDirectory): WorkspaceSelection =
                if (workingDirectory.path.startsWith(workspace.root.path)) Selected(workspace, workingDirectory)
                else Rejected(WorkspaceSelectionFailure.WORKING_DIRECTORY_OUTSIDE_ROOT)
        }
    }
    data class Rejected(val failure: WorkspaceSelectionFailure) : WorkspaceSelection
}

internal sealed interface WorkspaceEnrollment {
    data object Unenrolled : WorkspaceEnrollment
    /** Explicit fixed enrollment for callers that already own a canonical root. */
    data class Enrolled(val root: CanonicalBrokerDirectory) : WorkspaceEnrollment
    /** Each selection reads the current bounded registry; registration does not restart sessions. */
    class Registered internal constructor(private val store: WorkspaceEnrollmentStore) : WorkspaceEnrollment {
        internal fun snapshot(): WorkspaceRegistryRead = store.snapshot()
    }
    /** Unit fixtures can exercise the protocol without enrolling a real installation. */
    data object ProtocolFixture : WorkspaceEnrollment

    fun select(raw: String?, explicitRoot: String? = null): WorkspaceSelection {
        val cwd = canonical(raw) ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
        val roots = when (this) {
            Unenrolled -> emptyList()
            ProtocolFixture -> listOf(WorkspaceRegistration(cwd))
            is Enrolled -> listOf(WorkspaceRegistration(root))
            is Registered -> when (val read = snapshot()) {
                is WorkspaceRegistryRead.Read -> read.snapshot.workspaces
                is WorkspaceRegistryRead.Rejected -> return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.REGISTRY_REJECTED)
            }
        }
        if (explicitRoot != null) {
            val selectedRoot = canonical(explicitRoot)
                ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.PATH_REJECTED)
            val selected = roots.singleOrNull { it.root == selectedRoot }
                ?: return WorkspaceSelection.Rejected(WorkspaceSelectionFailure.UNREGISTERED)
            return WorkspaceSelection.Selected.admit(selected, cwd)
        }
        val matches = roots.filter { cwd.path.startsWith(it.root.path) }
        return when (matches.size) {
            0 -> WorkspaceSelection.Rejected(WorkspaceSelectionFailure.UNREGISTERED)
            1 -> WorkspaceSelection.Selected.admit(matches.single(), cwd)
            else -> WorkspaceSelection.Rejected(WorkspaceSelectionFailure.AMBIGUOUS)
        }
    }

    fun contains(raw: String?): Boolean = select(raw) is WorkspaceSelection.Selected

    private fun canonical(raw: String?): CanonicalBrokerDirectory? = try {
        raw?.let { Path.of(it) }?.takeIf(Path::isAbsolute)?.toRealPath()?.let(CanonicalBrokerDirectory::admit)
    } catch (_: Exception) { null }
}

internal enum class EnrollmentFailure { PATH_REJECTED, DOCUMENT_REJECTED, WRITE_REJECTED, WORKSPACE_CONFLICT, CAPACITY_EXCEEDED }
internal sealed interface EnrollmentRead {
    data class Read(val enrollment: WorkspaceEnrollment) : EnrollmentRead
    data class Rejected(val failure: EnrollmentFailure) : EnrollmentRead
}

@JvmInline
internal value class WorkspaceRegistryRevision private constructor(val value: Long) {
    internal fun next(): Refinement<WorkspaceRegistryRevision, EnrollmentFailure> =
        if (value == Long.MAX_VALUE) Refinement.Rejected(EnrollmentFailure.CAPACITY_EXCEEDED)
        else Refinement.Refined(WorkspaceRegistryRevision(value + 1))

    companion object {
        val Empty = WorkspaceRegistryRevision(0)
        internal fun admit(raw: Long): WorkspaceRegistryRevision? = raw.takeIf { it >= 0 }?.let(::WorkspaceRegistryRevision)
    }
}

internal data class WorkspaceRegistrationAcknowledgement(val workspace: WorkspaceRegistration, val revision: WorkspaceRegistryRevision)
internal data class WorkspaceRegistrySnapshot(val revision: WorkspaceRegistryRevision, val workspaces: List<WorkspaceRegistration>)
internal sealed interface WorkspaceRegistryRead {
    data class Read(val snapshot: WorkspaceRegistrySnapshot) : WorkspaceRegistryRead
    data class Rejected(val failure: EnrollmentFailure) : WorkspaceRegistryRead
}

/** Bounded desired workspace registrations, independent of runtime readiness or frontend lifetime. */
internal class WorkspaceEnrollmentStore(private val file: Path) {
    fun read(): EnrollmentRead = when (val current = snapshot()) {
        is WorkspaceRegistryRead.Read -> EnrollmentRead.Read(WorkspaceEnrollment.Registered(this))
        is WorkspaceRegistryRead.Rejected -> EnrollmentRead.Rejected(current.failure)
    }

    internal fun snapshot(): WorkspaceRegistryRead = try {
        var parent = file.parent ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
        while (true) {
            try {
                val attributes = Files.readAttributes(parent, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!attributes.isDirectory || parent.toRealPath() != parent)
                    return WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
                break
            } catch (_: java.nio.file.NoSuchFileException) {
                parent = parent.parent ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
            }
        }
        when {
            !file.isAbsolute || file.normalize() != file || Files.isSymbolicLink(file) -> WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
            !Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> WorkspaceRegistryRead.Read(WorkspaceRegistrySnapshot(WorkspaceRegistryRevision.Empty, emptyList()))
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAXIMUM_BYTES -> WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
            else -> {
                val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_BYTES.toInt() + 1) }
                if (file.parent.toRealPath() != file.parent) WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
                else if (bytes.size > MAXIMUM_BYTES) WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
                else decode(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
            }
        }
    } catch (_: Exception) { WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED) }

    private fun decode(doc: JsonObject): WorkspaceRegistryRead {
        if (doc.keys != setOf("schemaVersion", "revision", "roots") || doc["schemaVersion"] != JsonPrimitive(2)) {
            return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
        }
        val revision = (doc["revision"] as? JsonPrimitive)?.longOrNull
            ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
        val roots = doc["roots"] as? JsonArray
            ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
        if (revision < 1 || roots.isEmpty() || roots.size > MAXIMUM_WORKSPACES) return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
        val workspaces = roots.map { raw ->
            val text = (raw as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
            val root = CanonicalBrokerDirectory.admit(Path.of(text))
                ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.PATH_REJECTED)
            WorkspaceRegistration(root)
        }
        if (workspaces.map { it.id }.toSet().size != workspaces.size) return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
        return WorkspaceRegistryRead.Read(WorkspaceRegistrySnapshot(WorkspaceRegistryRevision.admit(revision) ?: return WorkspaceRegistryRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED), workspaces))
    }

    fun enroll(root: Path): Refinement<WorkspaceRegistrationAcknowledgement, EnrollmentFailure> {
        val canonical = try { root.takeIf(Path::isAbsolute)?.toRealPath()?.let(CanonicalBrokerDirectory::admit) } catch (_: Exception) { null }
            ?: return Refinement.Rejected(EnrollmentFailure.PATH_REJECTED)
        return synchronized(locks.computeIfAbsent(file) { Any() }) {
            try {
                if (!file.isAbsolute || file.normalize() != file) return@synchronized Refinement.Rejected(EnrollmentFailure.PATH_REJECTED)
                Files.createDirectories(file.parent)
                if (file.parent.toRealPath() != file.parent || Files.isSymbolicLink(file)) return@synchronized Refinement.Rejected(EnrollmentFailure.PATH_REJECTED)
                Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
                val lockPath = file.resolveSibling("${file.fileName}.lock")
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"))
                    val lock = channel.tryLock() ?: return@synchronized Refinement.Rejected(EnrollmentFailure.WRITE_REJECTED)
                    lock.use {
                        val previous = when (val read = snapshot()) {
                            is WorkspaceRegistryRead.Read -> read.snapshot
                            is WorkspaceRegistryRead.Rejected -> return@synchronized Refinement.Rejected(read.failure)
                        }
                        if (previous.workspaces.any { it.root == canonical }) return@synchronized Refinement.Refined(WorkspaceRegistrationAcknowledgement(WorkspaceRegistration(canonical), previous.revision))
                        if (previous.workspaces.size >= MAXIMUM_WORKSPACES) return@synchronized Refinement.Rejected(EnrollmentFailure.CAPACITY_EXCEEDED)
                        val revision = when (val next = previous.revision.next()) {
                            is Refinement.Refined -> next.value
                            is Refinement.Rejected -> return@synchronized Refinement.Rejected(next.failure)
                        }
                        val doc = buildJsonObject {
                            put("schemaVersion", 2)
                            put("revision", revision.value)
                            put("roots", JsonArray((previous.workspaces.map { it.root.path.toString() } + canonical.path.toString()).sorted().map(::JsonPrimitive)))
                        }.toString()
                        if (doc.toByteArray().size > MAXIMUM_BYTES) return@synchronized Refinement.Rejected(EnrollmentFailure.CAPACITY_EXCEEDED)
                        val temporary = Files.createTempFile(file.parent, ".enrollment-", ".json")
                        try {
                            Files.writeString(temporary, doc)
                            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
                            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(temporary) }
                        Refinement.Refined(WorkspaceRegistrationAcknowledgement(WorkspaceRegistration(canonical), revision))
                    }
                }
            } catch (_: Exception) { Refinement.Rejected(EnrollmentFailure.WRITE_REJECTED) }
        }
    }

    private companion object {
        const val MAXIMUM_WORKSPACES = BrokerOperationalLimits.maximumWorkspaces
        val MAXIMUM_BYTES = BrokerOperationalLimits.maximumRegistryBytes.toLong()
        val locks = ConcurrentHashMap<Path, Any>()
    }
}
