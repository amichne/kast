package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*

internal sealed interface WorkspaceEnrollment {
    data object Unenrolled : WorkspaceEnrollment
    data class Enrolled(val root: CanonicalBrokerDirectory) : WorkspaceEnrollment
    /** Unit fixtures can exercise the protocol without enrolling a real installation. */
    data object ProtocolFixture : WorkspaceEnrollment

    fun contains(raw: String?): Boolean = when (this) {
        Unenrolled -> false
        ProtocolFixture -> true
        is Enrolled -> try {
            raw != null && Path.of(raw).toRealPath().startsWith(root.path)
        } catch (_: Exception) { false }
    }
}

internal enum class EnrollmentFailure { PATH_REJECTED, DOCUMENT_REJECTED, WRITE_REJECTED, WORKSPACE_CONFLICT }
internal sealed interface EnrollmentRead {
    data class Read(val enrollment: WorkspaceEnrollment) : EnrollmentRead
    data class Rejected(val failure: EnrollmentFailure) : EnrollmentRead
}

/** One canonical workspace per Codex home; a corrupt enrollment never becomes an empty catalog. */
internal class WorkspaceEnrollmentStore(private val file: Path) {
    fun read(): EnrollmentRead = try {
        when {
            Files.isSymbolicLink(file) -> EnrollmentRead.Rejected(EnrollmentFailure.PATH_REJECTED)
            !Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> EnrollmentRead.Read(WorkspaceEnrollment.Unenrolled)
            !Files.isRegularFile(file) || Files.size(file) > 16_384 -> EnrollmentRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
            else -> {
                val doc = Json.parseToJsonElement(Files.readString(file)).jsonObject
                val root = (doc["root"] as? JsonPrimitive)?.contentOrNull?.let { CanonicalBrokerDirectory.admit(Path.of(it)) }
                if (doc.keys != setOf("schemaVersion", "root") || doc["schemaVersion"] != JsonPrimitive(1) || root == null) {
                    EnrollmentRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED)
                } else EnrollmentRead.Read(WorkspaceEnrollment.Enrolled(root))
            }
        }
    } catch (_: Exception) { EnrollmentRead.Rejected(EnrollmentFailure.DOCUMENT_REJECTED) }

    fun enroll(root: Path): Refinement<WorkspaceEnrollment.Enrolled, EnrollmentFailure> {
        val canonical = try { CanonicalBrokerDirectory.admit(root.toRealPath()) } catch (_: Exception) { null }
            ?: return Refinement.Rejected(EnrollmentFailure.PATH_REJECTED)
        when (val previous = read()) {
            is EnrollmentRead.Rejected -> return Refinement.Rejected(previous.failure)
            is EnrollmentRead.Read -> if (previous.enrollment is WorkspaceEnrollment.Enrolled) {
                return if (previous.enrollment.root == canonical) Refinement.Refined(previous.enrollment)
                else Refinement.Rejected(EnrollmentFailure.WORKSPACE_CONFLICT)
            }
        }
        return try {
            Files.createDirectories(file.parent)
            if (file.parent.toRealPath() != file.parent || Files.isSymbolicLink(file)) return Refinement.Rejected(EnrollmentFailure.PATH_REJECTED)
            Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
            val temporary = Files.createTempFile(file.parent, ".enrollment-", ".json")
            try {
                Files.writeString(temporary, buildJsonObject { put("schemaVersion", 1); put("root", canonical.path.toString()) }.toString())
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
                try { Files.createLink(file, temporary) }
                catch (_: java.nio.file.FileAlreadyExistsException) {
                    val winner = (read() as? EnrollmentRead.Read)?.enrollment as? WorkspaceEnrollment.Enrolled
                    return if (winner?.root == canonical) Refinement.Refined(winner)
                    else Refinement.Rejected(EnrollmentFailure.WORKSPACE_CONFLICT)
                }
            } finally { Files.deleteIfExists(temporary) }
            Refinement.Refined(WorkspaceEnrollment.Enrolled(canonical))
        } catch (_: Exception) { Refinement.Rejected(EnrollmentFailure.WRITE_REJECTED) }
    }
}
