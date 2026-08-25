package support.delivery
import java.io.IOException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
@CacheableTask
abstract class GenerateDeliveryProjectionsTask : DefaultTask() {
    @get:Input
    abstract val programProjection: Property<String>
    @get:OutputFile
    abstract val programOutputFile: RegularFileProperty
    @get:Input
    abstract val requirementTraceProjection: Property<String>
    @get:OutputFile
    abstract val requirementTraceOutputFile: RegularFileProperty
    @TaskAction
    fun generate() {
        writeAtomically(programOutputFile, programProjection)
        writeAtomically(requirementTraceOutputFile, requirementTraceProjection)
    }
    private fun writeAtomically(output: RegularFileProperty, content: Property<String>) {
        writeTextAtomically(output.get().asFile.toPath(), content.get())
    }
}
@Serializable private data class AuthorityVerificationDocument(
    val schemaVersion: Int, val gateId: String, val baseRevision: String, val exactHead: String,
    val programFingerprint: String, val requirementFingerprint: String, val sourceDigests: Map<String, String>,
)
@Serializable private enum class AuthorityNegativeCase {
    STALE_EXACT_HEAD, CHANGED_REQUIREMENT_FINGERPRINT, OMITTED_CONTRADICTION,
    OBSOLETE_TWO_PROCESS_ASSUMPTION,
}
@Serializable private data class AuthorityNegativeProofDocument(
    val schemaVersion: Int, val gateId: String, val rejectedCases: List<AuthorityNegativeCase>,
)
@Serializable private data class ProgramAuthorityJsonDocument(
    val schemaVersion: Int, val baseRevision: String, val exactHead: String,
    val programFingerprint: String, val requirementFingerprint: String,
    val sourceArtifacts: List<AuthoritySourceJsonDocument>, val contradictions: List<String>,
    val obsoleteAssumptions: List<String>, val unprovenClaims: List<String>,
)
@Serializable private data class AuthoritySourceJsonDocument(val id: String, val path: String, val sha256: String)
private val authorityEvidenceJson = Json { ignoreUnknownKeys = false; prettyPrint = true }
/**
 * Proof transition: authority JSON bytes -> parsed `ProgramAuthorityDocument` -> `AdmittedProgramAuthority`.
 *
 * Generated serializers establish the closed JSON shape. Enum names and duplicate entries refine
 * before domain admission. Expected failure returns [ProgramAuthorityAdmission.Rejected]. Raw JSON
 * is extracted only at this Gradle boundary.
 */
internal fun admitProgramAuthority(rawDocument: String, expectation: ProgramAuthorityExpectation, observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation): ProgramAuthorityAdmission {
    val raw = try { authorityEvidenceJson.decodeFromString(ProgramAuthorityJsonDocument.serializer(), rawDocument) }
    catch (_: SerializationException) { return malformedAuthority() }
    val enumSets = when (val refined = refineAuthorityEnumSets(raw)) {
        is AuthorityEnumSetsRefinement.Complete -> refined; AuthorityEnumSetsRefinement.Rejected -> return malformedAuthority()
    }
    val document = ProgramAuthorityDocument(
        raw.schemaVersion, raw.baseRevision, raw.exactHead, raw.programFingerprint, raw.requirementFingerprint,
        raw.sourceArtifacts.map { AuthoritySourceDocument(it.id, it.path, it.sha256) }, enumSets.contradictions,
        enumSets.obsolete, enumSets.unproven,
    )
    return admitProgramAuthority(document, expectation, observeSource)
}
internal fun encodeProgramAuthorityDocument(document: ProgramAuthorityDocument): String {
    val raw = ProgramAuthorityJsonDocument(
        document.schemaVersion, document.baseRevision, document.exactHead, document.programFingerprint, document.requirementFingerprint,
        document.sourceArtifacts.map { AuthoritySourceJsonDocument(it.id, it.path, it.sha256) },
        document.contradictions.map { it.name }, document.obsoleteAssumptions.map { it.name }, document.unprovenClaims.map { it.name },
    )
    return authorityEvidenceJson.encodeToString(ProgramAuthorityJsonDocument.serializer(), raw) + "\n"
}
private fun malformedAuthority() = ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.MalformedDocument)
private sealed interface EnumSetRefinement<out E> {
    data class Complete<E>(val values: Set<E>) : EnumSetRefinement<E>; data object Rejected : EnumSetRefinement<Nothing>
}
private sealed interface AuthorityEnumSetsRefinement {
    data class Complete(val contradictions: Set<AuthorityContradiction>, val obsolete: Set<ObsoleteAuthorityAssumption>, val unproven: Set<UnprovenAuthorityClaim>) : AuthorityEnumSetsRefinement
    data object Rejected : AuthorityEnumSetsRefinement
}
/** Proof transition: raw authority enum lists -> closed enum sets; unknown or duplicate names return `Rejected`; raw names stay at the JSON boundary. */
private fun refineAuthorityEnumSets(raw: ProgramAuthorityJsonDocument): AuthorityEnumSetsRefinement {
    val contradictions = when (val value = enumSetOrRejected<AuthorityContradiction>(raw.contradictions)) { is EnumSetRefinement.Complete -> value.values; EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected }
    val obsolete = when (val value = enumSetOrRejected<ObsoleteAuthorityAssumption>(raw.obsoleteAssumptions)) { is EnumSetRefinement.Complete -> value.values; EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected }
    val unproven = when (val value = enumSetOrRejected<UnprovenAuthorityClaim>(raw.unprovenClaims)) { is EnumSetRefinement.Complete -> value.values; EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected }
    return AuthorityEnumSetsRefinement.Complete(contradictions, obsolete, unproven)
}
/** Proof transition: raw enum-name list -> unique `Set<E>`; unknown or duplicate names return `Rejected`; raw names stay at the JSON boundary. */
private inline fun <reified E : Enum<E>> enumSetOrRejected(values: List<String>): EnumSetRefinement<E> {
    if (values.toSet().size != values.size) return EnumSetRefinement.Rejected
    val entriesByName = enumValues<E>().associateBy { it.name }
    val parsed = LinkedHashSet<E>()
    values.forEach { value -> parsed += entriesByName[value] ?: return EnumSetRefinement.Rejected }
    return EnumSetRefinement.Complete(parsed)
}
@UntrackedTask(because = "Observes live Git metadata and declared external authority artifacts")
abstract class VerifyKastVfsPassiveAuthorityTask : DefaultTask() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:Input abstract val authorityFilePath: Property<String>
    @get:Input abstract val contradictionFilePath: Property<String>
    @get:Input abstract val baseRevision: Property<String>
    @get:Input abstract val programFingerprint: Property<String>
    @get:Input abstract val requirementFingerprint: Property<String>
    @get:Input abstract val sourceDigests: MapProperty<String, String>
    @get:Input abstract val allowedReads: ListProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty
    @TaskAction
    fun verifyAuthority() {
        val repositoryRoot = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val exactHead = when (val observation = observeGitHead(repositoryRoot)) {
            is GitHeadObservation.Complete -> observation.revision
            is GitHeadObservation.Rejected -> rejectAuthority("git head observation", observation.failure.name)
        }
        val expectation = admittedExpectation(exactHead)
        val authorityText = readBoundaryFile(Path.of(authorityFilePath.get()), MAX_AUTHORITY_BYTES).asTextOrReject("authority ledger")
        val admission = admitProgramAuthority(authorityText, expectation) { observeAuthoritySource(repositoryRoot, it) }
        val authority = when (admission) {
            is ProgramAuthorityAdmission.Complete -> admission.authority
            is ProgramAuthorityAdmission.Rejected -> rejectAuthority("authority admission", admission.failure.render())
        }
        val contradictionText = readBoundaryFile(Path.of(contradictionFilePath.get()), MAX_AUTHORITY_BYTES).asTextOrReject("contradiction projection")
        if (contradictionText != authority.contradictionProjection) {
            rejectAuthority("contradiction projection", "bytes differ from admitted authority")
        }
        val report = AuthorityVerificationDocument(
            1,
            "KVP-001-GREEN",
            expectation.baseRevision.value,
            authority.exactHead.value,
            authority.programFingerprint.value,
            authority.requirementFingerprint.value,
            authority.sourceDigests.entries.associate { it.key.value to it.value.value }.toSortedMap(),
        )
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            authorityEvidenceJson.encodeToString(AuthorityVerificationDocument.serializer(), report) + "\n",
        )
    }
    private fun admittedExpectation(exactHead: AuthorityGitRevision): ProgramAuthorityExpectation =
        when (
            val parsed = ProgramAuthorityExpectation.parse(
                baseRevision.get(),
                exactHead.value,
                programFingerprint.get(),
                requirementFingerprint.get(),
                sourceDigests.get(),
                allowedReads.get(),
            )
        ) {
            is ProgramAuthorityExpectationResult.Complete -> parsed.expectation
            is ProgramAuthorityExpectationResult.Rejected -> rejectAuthority(
                "configured authority expectation",
                parsed.failure.render(),
            )
        }
}
@UntrackedTask(because = "Executes deterministic in-memory negative authority fixtures")
abstract class VerifyKastVfsPassiveAuthorityNegativeTask : DefaultTask() {
    @get:Input abstract val baseRevision: Property<String>
    @get:Input abstract val programFingerprint: Property<String>
    @get:Input abstract val requirementFingerprint: Property<String>
    @get:Input abstract val sourceDigests: MapProperty<String, String>
    @get:Input abstract val allowedReads: ListProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty
    @TaskAction
    fun verifyNegativeCases() {
        val expectation = when (
            val parsed = ProgramAuthorityExpectation.parse(
                baseRevision.get(),
                baseRevision.get(),
                programFingerprint.get(),
                requirementFingerprint.get(),
                sourceDigests.get(),
                allowedReads.get(),
            )
        ) {
            is ProgramAuthorityExpectationResult.Complete -> parsed.expectation
            is ProgramAuthorityExpectationResult.Rejected -> rejectAuthority(
                "negative fixture expectation",
                parsed.failure.render(),
            )
        }
        val absoluteReads = expectation.allowedReads.filter { Path.of(it.value).isAbsolute }.sortedBy { it.value }
        if (absoluteReads.size != expectation.sourceDigests.size) {
            rejectAuthority("negative fixture", "source IDs cannot be paired with absolute allowed reads")
        }
        val sourceDocuments = expectation.sourceDigests.entries.sortedBy { it.key.value }
            .zip(absoluteReads)
            .map { (source, path) -> AuthoritySourceDocument(source.key.value, path.value, source.value.value) }
        val exactDocument = ProgramAuthorityDocument(
            1,
            expectation.baseRevision.value,
            expectation.exactHead.value,
            expectation.programFingerprint.value,
            expectation.requirementFingerprint.value,
            sourceDocuments,
            AuthorityContradiction.entries.toSet(),
            ObsoleteAuthorityAssumption.entries.toSet(),
            UnprovenAuthorityClaim.entries.toSet(),
        )
        val observations = sourceDocuments.associate {
            AuthoritySourcePath(it.path) to AuthoritySourceObservation.Complete(AuthorityArtifactDigest(it.sha256))
        }
        fun admission(document: ProgramAuthorityDocument): ProgramAuthorityAdmission =
            admitProgramAuthority(encodeProgramAuthorityDocument(document), expectation) { path ->
                observations.getValue(path)
            }
        if (admission(exactDocument) !is ProgramAuthorityAdmission.Complete) {
            rejectAuthority("negative fixture", "exact control document was rejected")
        }
        expectRejection(
            admission(exactDocument.copy(exactHead = "0".repeat(40))),
            AuthorityAdmissionFailure.ExactHeadMismatch,
        )
        expectRejection(
            admission(exactDocument.copy(requirementFingerprint = "0".repeat(64))),
            AuthorityAdmissionFailure.RequirementFingerprintMismatch,
        )
        expectRejection(
            admission(exactDocument.copy(contradictions = exactDocument.contradictions - AuthorityContradiction.PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT)),
            AuthorityAdmissionFailure.ContradictionSetIncomplete,
        )
        expectRejection(
            admission(exactDocument.copy(obsoleteAssumptions = exactDocument.obsoleteAssumptions - ObsoleteAuthorityAssumption.EXACTLY_TWO_RUNTIME_PROCESSES)),
            AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete,
        )
        val report = AuthorityNegativeProofDocument(1, "KVP-001-RED", AuthorityNegativeCase.entries)
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            authorityEvidenceJson.encodeToString(AuthorityNegativeProofDocument.serializer(), report) + "\n",
        )
    }
}
private fun expectRejection(
    result: ProgramAuthorityAdmission,
    expected: AuthorityAdmissionFailure,
) {
    if (result !is ProgramAuthorityAdmission.Rejected || result.failure != expected) {
        rejectAuthority("negative fixture", "expected ${expected.render()}, received $result")
    }
}
internal sealed interface GitHeadObservation {
    data class Complete(val revision: AuthorityGitRevision) : GitHeadObservation
    data class Rejected(val failure: GitHeadFailure) : GitHeadObservation
}
internal enum class GitHeadFailure { METADATA_MISSING, MALFORMED_GITDIR, HEAD_MISSING, HEAD_MALFORMED, REF_ESCAPES_GITDIR, REF_MISSING, READ_FAILED }
/**
 * Proof transition: repository `Path` -> exact `AuthorityGitRevision`.
 *
 * Establishes a full lowercase commit identity from Git's HEAD and loose or packed ref metadata without
 * process start. Expected failures return [GitHeadObservation.Rejected]. Raw text stays at this boundary.
 */
internal fun observeGitHead(repositoryRoot: Path): GitHeadObservation {
    val dotGit = repositoryRoot.resolve(".git")
    val gitDirectory = try {
        when {
            Files.isDirectory(dotGit, NOFOLLOW_LINKS) -> dotGit
            Files.isRegularFile(dotGit, NOFOLLOW_LINKS) -> {
                val marker = Files.readString(dotGit).trim()
                if (!marker.startsWith("gitdir: ")) return GitHeadObservation.Rejected(GitHeadFailure.MALFORMED_GITDIR)
                dotGit.parent.resolve(marker.removePrefix("gitdir: ")).normalize()
            }
            else -> return GitHeadObservation.Rejected(GitHeadFailure.METADATA_MISSING)
        }
    } catch (_: IOException) {
        return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
    }
    val head = try {
        val headPath = gitDirectory.resolve("HEAD")
        if (!Files.isRegularFile(headPath, NOFOLLOW_LINKS)) return GitHeadObservation.Rejected(GitHeadFailure.HEAD_MISSING)
        Files.readString(headPath).trim()
    } catch (_: IOException) {
        return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
    }
    val revision = if (head.startsWith("ref: ")) {
        val ref = head.removePrefix("ref: ")
        val refPath = gitDirectory.resolve(ref).normalize()
        if (!refPath.startsWith(gitDirectory.normalize())) return GitHeadObservation.Rejected(GitHeadFailure.REF_ESCAPES_GITDIR)
        try {
            if (Files.isRegularFile(refPath, NOFOLLOW_LINKS)) {
                Files.readString(refPath).trim()
            } else {
                when (val packed = readPackedRef(gitDirectory.resolve("packed-refs"), ref)) {
                    is PackedRefObservation.Complete -> packed.revision
                    PackedRefObservation.Rejected -> return GitHeadObservation.Rejected(GitHeadFailure.REF_MISSING)
                }
            }
        } catch (_: IOException) {
            return GitHeadObservation.Rejected(GitHeadFailure.READ_FAILED)
        }
    } else {
        head
    }
    return if (revision.matches(Regex("[0-9a-f]{40}"))) {
        GitHeadObservation.Complete(AuthorityGitRevision(revision))
    } else {
        GitHeadObservation.Rejected(GitHeadFailure.HEAD_MALFORMED)
    }
}
private sealed interface PackedRefObservation { data class Complete(val revision: String) : PackedRefObservation; data object Rejected : PackedRefObservation }
/** Proof transition: packed-ref bytes plus ref name -> one revision; absent or ambiguous matches return `Rejected`; raw text stays at the Git boundary. */
private fun readPackedRef(packedRefs: Path, ref: String): PackedRefObservation {
    if (!Files.isRegularFile(packedRefs, NOFOLLOW_LINKS)) return PackedRefObservation.Rejected
    val matches = Files.readAllLines(packedRefs).asSequence()
        .filterNot { it.startsWith("#") || it.startsWith("^") }
        .map { it.split(' ', limit = 2) }
        .filter { it.size == 2 && it[1] == ref }.map { it.first() }.toList()
    return if (matches.size == 1) PackedRefObservation.Complete(matches.single()) else PackedRefObservation.Rejected
}
private sealed interface BoundaryFileRead {
    class Complete(val bytes: ByteArray) : BoundaryFileRead
    data class Rejected(val failure: AuthoritySourceFailure) : BoundaryFileRead
}
/** Proof transition: raw `Path` -> bounded regular non-symlink bytes; expected file failures return `Rejected`; bytes stay at the task boundary. */
private fun readBoundaryFile(path: Path, maximumBytes: Long): BoundaryFileRead = try {
    when {
        !Files.exists(path, NOFOLLOW_LINKS) -> BoundaryFileRead.Rejected(AuthoritySourceFailure.MISSING)
        Files.isSymbolicLink(path) -> BoundaryFileRead.Rejected(AuthoritySourceFailure.SYMLINK)
        !Files.isRegularFile(path, NOFOLLOW_LINKS) -> BoundaryFileRead.Rejected(AuthoritySourceFailure.NOT_REGULAR)
        Files.size(path) > maximumBytes -> BoundaryFileRead.Rejected(AuthoritySourceFailure.TOO_LARGE)
        else -> BoundaryFileRead.Complete(Files.readAllBytes(path))
    }
} catch (_: IOException) {
    BoundaryFileRead.Rejected(AuthoritySourceFailure.READ_FAILED)
}
private fun BoundaryFileRead.asTextOrReject(owner: String): String = when (this) {
    is BoundaryFileRead.Complete -> bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> rejectAuthority(owner, failure.name)
}
/** Proof transition: declared authority path -> observed byte digest; expected file failures return `Rejected`; raw bytes stay at the Gradle task boundary. */
private fun observeAuthoritySource(repositoryRoot: Path, sourcePath: AuthoritySourcePath): AuthoritySourceObservation {
    val configured = Path.of(sourcePath.value)
    val resolved = (if (configured.isAbsolute) configured else repositoryRoot.resolve(configured)).normalize()
    return when (val read = readBoundaryFile(resolved, MAX_SOURCE_ARTIFACT_BYTES)) {
        is BoundaryFileRead.Rejected -> AuthoritySourceObservation.Rejected(read.failure)
        is BoundaryFileRead.Complete -> AuthoritySourceObservation.Complete(
            AuthorityArtifactDigest(sha256Bytes(read.bytes)),
        )
    }
}
private fun AuthorityAdmissionFailure.render(): String = when (this) {
    is AuthorityAdmissionFailure.SourcePathNotAllowed -> "SOURCE_PATH_NOT_ALLOWED:${sourceId.value}"
    is AuthorityAdmissionFailure.SourceUnavailable -> "SOURCE_UNAVAILABLE:${sourceId.value}:${failure.name}"
    is AuthorityAdmissionFailure.SourceDigestMismatch -> "SOURCE_DIGEST_MISMATCH:${sourceId.value}"
    else -> javaClass.simpleName
}
private fun rejectAuthority(owner: String, failure: String): Nothing =
    throw GradleException("$owner rejected: $failure")
private fun writeTextAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content)
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
private const val MAX_AUTHORITY_BYTES = 1L shl 20
private const val MAX_SOURCE_ARTIFACT_BYTES = 32L shl 20
@CacheableTask
abstract class VerifyDeliveryProjectionsTask : DefaultTask() {
    @get:Input abstract val expectedProgramProjection: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val programProjectionFile: RegularFileProperty
    @get:Input abstract val expectedRequirementTraceProjection: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val requirementTraceProjectionFile: RegularFileProperty
    @TaskAction
    fun verifyProjections() {
        verifyProjection(programProjectionFile, expectedProgramProjection, "delivery program")
        verifyProjection(requirementTraceProjectionFile, expectedRequirementTraceProjection, "requirement trace")
    }
    private fun verifyProjection(input: RegularFileProperty, expected: Property<String>, description: String) {
        if (input.get().asFile.readText() != expected.get()) {
            throw GradleException("checked-in $description differs from the typed Kotlin authority")
        }
    }
}
