package support.delivery

import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

abstract class Kvp001ReceiptTaskBase : DefaultTask() {
    @get:Input
    abstract val repositoryRootPath: Property<String>

    @get:Input
    abstract val baseRevision: Property<String>

    @get:Input
    abstract val programFingerprint: Property<String>

    @get:Input
    abstract val requirementFingerprint: Property<String>

    @get:Input
    abstract val sourceDigests: MapProperty<String, String>

    @get:Input
    abstract val taskId: Property<String>

    @get:Input
    abstract val redGateId: Property<String>

    @get:Input
    abstract val greenGateId: Property<String>

    @get:Input
    abstract val completionGateId: Property<String>

    @get:Input
    abstract val redReceiptId: Property<String>

    @get:Input
    abstract val greenReceiptId: Property<String>

    @get:Input
    abstract val completionReceiptId: Property<String>

    @get:Input
    abstract val redCommand: Property<String>

    @get:Input
    abstract val greenCommand: Property<String>

    @get:Input
    abstract val completionCommand: Property<String>

    @get:Input
    abstract val taskInputDigest: Property<String>

    @get:Input
    abstract val completionInputDigest: Property<String>

    @get:Input
    abstract val redProofReportPath: Property<String>

    @get:Input
    abstract val greenProofReportPath: Property<String>

    @get:Input
    abstract val redArtifactPaths: ListProperty<String>

    @get:Input
    abstract val greenArtifactPaths: ListProperty<String>

    /**
     * Proof transition: configured repository-root text -> normalized `Path` at the Gradle task
     * boundary. Malformed input becomes a closed receipt rejection before evidence reads.
     */
    internal fun repositoryRoot(): Path = parseRepositoryRoot(repositoryRootPath.get())

    /**
     * Proof transition: configured KVP-001 task properties plus `AuthorityGitRevision` ->
     * `Kvp001ReceiptContext`. Raw Gradle values remain within this effect boundary; each proof
     * contract is refined to [ProofReceiptExpectation] before use.
     */
    internal fun context(exactHead: AuthorityGitRevision) = Kvp001ReceiptContext(
        repositoryRoot = repositoryRoot(),
        baseRevision = baseRevision.get(),
        exactHead = exactHead.value,
        programFingerprint = programFingerprint.get(),
        requirementFingerprint = requirementFingerprint.get(),
        sourceDigests = sourceDigests.get(),
        taskId = taskId.get(),
        redGateId = redGateId.get(),
        greenGateId = greenGateId.get(),
        completionGateId = completionGateId.get(),
        redReceiptId = redReceiptId.get(),
        greenReceiptId = greenReceiptId.get(),
        completionReceiptId = completionReceiptId.get(),
        redCommand = redCommand.get(),
        greenCommand = greenCommand.get(),
        completionCommand = completionCommand.get(),
        taskInputDigest = taskInputDigest.get(),
        completionInputDigest = completionInputDigest.get(),
        redProofReportPath = redProofReportPath.get(),
        greenProofReportPath = greenProofReportPath.get(),
        redArtifactPaths = redArtifactPaths.get(),
        greenArtifactPaths = greenArtifactPaths.get(),
    )
}

internal data class Kvp001ReceiptContext(
    val repositoryRoot: Path,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val sourceDigests: Map<String, String>,
    val taskId: String,
    val redGateId: String,
    val greenGateId: String,
    val completionGateId: String,
    val redReceiptId: String,
    val greenReceiptId: String,
    val completionReceiptId: String,
    val redCommand: String,
    val greenCommand: String,
    val completionCommand: String,
    val taskInputDigest: String,
    val completionInputDigest: String,
    val redProofReportPath: String,
    val greenProofReportPath: String,
    val redArtifactPaths: List<String>,
    val greenArtifactPaths: List<String>,
) {
    /**
     * Proof transition: KVP-001 RED report bytes -> `ProofReceiptExpectation`.
     *
     * Establishes exact negative cases, command/input digests, and artifact bytes. A closed failure
     * is rendered only at this outer Gradle boundary.
     */
    fun redExpectation(): ProofReceiptExpectation {
        val observation = observeAuthorityNegativeProof(readText(redProofReportPath))
            .negativeValuesOrReject()
        return expectation(
            redReceiptId,
            redGateId,
            redCommand,
            taskInputDigest,
            emptyMap(),
            observation,
            artifactDigests(redArtifactPaths),
        )
    }

    /**
     * Proof transition: KVP-001 GREEN report bytes plus `AdmittedProofReceipt` ->
     * `ProofReceiptExpectation`. Establishes exact authority observations and the RED dependency;
     * a closed failure is rendered only at this outer Gradle boundary.
     */
    fun greenExpectation(redReceipt: AdmittedProofReceipt): ProofReceiptExpectation {
        val observation = observeAuthorityVerificationProof(
            readText(greenProofReportPath),
            baseRevision,
            exactHead,
            programFingerprint,
            requirementFingerprint,
            sourceDigests,
        ).verificationValuesOrReject()
        return expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            mapOf(redReceipt.receiptId.value to redReceipt.digest.value),
            observation,
            artifactDigests(greenArtifactPaths),
        )
    }

    /**
     * Proof transition: admitted KVP-001 RED and GREEN receipts -> completion
     * `ProofReceiptExpectation`. Establishes the exact two-receipt closure; configuration failures
     * remain closed receipt failures at this Gradle boundary.
     */
    fun completionExpectation(
        redReceipt: AdmittedProofReceipt,
        greenReceipt: AdmittedProofReceipt,
    ): ProofReceiptExpectation = expectation(
        completionReceiptId,
        completionGateId,
        completionCommand,
        completionInputDigest,
        mapOf(
            redReceipt.receiptId.value to redReceipt.digest.value,
            greenReceipt.receiptId.value to greenReceipt.digest.value,
        ),
        mapOf(
            "admittedGateReceiptCount" to "2",
            "outcome" to "COMPLETE",
        ),
        emptyMap(),
    )

    /**
     * Proof transition: declared receipt path plus `ProofReceiptExpectation` ->
     * `AdmittedProofReceipt`. Establishes bounded regular-file evidence and every receipt invariant;
     * expected failure is [ProofReceiptFailure] rendered at the Gradle boundary.
     */
    fun admit(path: String, expectation: ProofReceiptExpectation): AdmittedProofReceipt {
        return admitResolved(resolveBoundaryPath(path), expectation)
    }

    /**
     * Proof transition: Gradle-provided receipt `Path` plus `ProofReceiptExpectation` ->
     * `AdmittedProofReceipt`. Establishes bounded regular-file evidence and every receipt invariant;
     * expected failure is [ProofReceiptFailure] rendered at the Gradle boundary.
     */
    fun admit(path: Path, expectation: ProofReceiptExpectation): AdmittedProofReceipt =
        admitResolved(path.toAbsolutePath().normalize(), expectation)

    private fun admitResolved(
        path: Path,
        expectation: ProofReceiptExpectation,
    ): AdmittedProofReceipt {
        val raw = when (val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)) {
            is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
            is BoundaryFileRead.Rejected -> rejectReceipt(
                "receipt ${path.fileName}",
                ProofReceiptFailure.MALFORMED_DOCUMENT,
                read.failure.name,
            )
        }
        val admission = admitProofReceipt(raw, expectation)
        return when (admission) {
            is ProofReceiptAdmission.Complete -> admission.receipt
            is ProofReceiptAdmission.Rejected -> rejectReceipt("receipt admission", admission.failure)
        }
    }

    /**
     * Proof transition: configured gate fields -> `ProofReceiptExpectation`.
     * Establishes every receipt identity and digest, including an explicitly selected task owner.
     * Expected failure is [ProofReceiptFailure] rendered here; raw fields stay at this boundary.
     */
    fun expectation(
        receiptId: String,
        gateId: String,
        command: String,
        inputDigest: String,
        dependencies: Map<String, String>,
        observations: Map<String, String>,
        artifacts: Map<String, String>,
        receiptTaskId: String = taskId,
    ): ProofReceiptExpectation = when (
        val parsed = ProofReceiptExpectation.parse(
            receiptId,
            baseRevision,
            exactHead,
            programFingerprint,
            requirementFingerprint,
            receiptTaskId,
            gateId,
            dependencies,
            inputDigest,
            sha256(command).value,
            observations,
            artifacts,
        )
    ) {
        is ProofReceiptExpectationResult.Complete -> parsed.expectation
        is ProofReceiptExpectationResult.Rejected -> {
            rejectReceipt("configured receipt expectation", parsed.failure)
        }
    }

    fun artifactDigests(paths: List<String>): Map<String, String> =
        paths.sorted().associateWith { path -> sha256Bytes(readBytes(path)) }

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    private fun readBytes(path: String): ByteArray {
        val resolved = resolveBoundaryPath(path)
        return when (val read = readBoundaryFile(resolved, MAX_RECEIPT_EVIDENCE_BYTES)) {
            is BoundaryFileRead.Complete -> read.bytes
            is BoundaryFileRead.Rejected -> rejectReceipt(
                "receipt evidence $path",
                ProofReceiptFailure.MALFORMED_DOCUMENT,
                read.failure.name,
            )
        }
    }

    private fun resolveBoundaryPath(path: String): Path {
        val resolved = try {
            repositoryRoot.resolve(Path.of(path)).normalize()
        } catch (_: InvalidPathException) {
            rejectReceipt("receipt evidence path", ProofReceiptFailure.MALFORMED_DOCUMENT)
        }
        if (!resolved.startsWith(repositoryRoot)) {
            rejectReceipt("receipt evidence path", ProofReceiptFailure.MALFORMED_DOCUMENT)
        }
        return resolved
    }
}

private fun parseRepositoryRoot(value: String): Path = try {
    Path.of(value).toAbsolutePath().normalize()
} catch (_: InvalidPathException) {
    rejectReceipt("receipt repository root", ProofReceiptFailure.MALFORMED_DOCUMENT)
}

private fun AuthorityGateProofObservation.negativeValuesOrReject(): Map<String, String> = when (this) {
    is AuthorityGateProofObservation.NegativeComplete -> mapOf(
        "outcome" to "COMPLETE",
        "rejectedCases" to rejectedCases.map { it.name }.sorted().joinToString(","),
    )
    is AuthorityGateProofObservation.VerificationComplete -> rejectReceipt(
        "authority RED gate observation",
        ProofReceiptFailure.MALFORMED_DOCUMENT,
        "WRONG_OBSERVATION_KIND",
    )
    is AuthorityGateProofObservation.Rejected -> rejectReceipt(
        "authority gate observation",
        ProofReceiptFailure.MALFORMED_DOCUMENT,
        failure.name,
    )
}

private fun AuthorityGateProofObservation.verificationValuesOrReject(): Map<String, String> =
    when (this) {
        is AuthorityGateProofObservation.VerificationComplete -> mapOf(
            "admittedSourceIds" to admittedSourceIds.map { it.value }.sorted().joinToString(","),
            "outcome" to "COMPLETE",
        )
        is AuthorityGateProofObservation.NegativeComplete -> rejectReceipt(
            "authority GREEN gate observation",
            ProofReceiptFailure.MALFORMED_DOCUMENT,
            "WRONG_OBSERVATION_KIND",
        )
        is AuthorityGateProofObservation.Rejected -> rejectReceipt(
            "authority gate observation",
            ProofReceiptFailure.MALFORMED_DOCUMENT,
            failure.name,
        )
    }

internal fun rejectReceipt(
    owner: String,
    failure: ProofReceiptFailure,
    detail: String? = null,
): Nothing = rejectAuthority(owner, listOfNotNull(failure.name, detail).joinToString(":"))

internal const val MAX_RECEIPT_EVIDENCE_BYTES = 32L shl 20
