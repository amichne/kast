package support.delivery

import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

internal enum class Kvp038ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REJECTED,
    LEGAL_PATH_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal data class Kvp038ProofContext(
    val version: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependencies: AdmittedKvp038Dependencies,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp038ImplementationScope,
    val cases: Kvp038Cases,
    val report: Kvp038CleanCheckoutDocument,
    val reportRaw: String,
)

internal sealed interface Kvp038Preparation {
    data class Complete(val context: Kvp038ProofContext) : Kvp038Preparation
    data class Rejected(val failure: Kvp038ProofFailure) : Kvp038Preparation
}

private sealed interface Kvp038TaskTextRead {
    data class Complete(val raw: String) : Kvp038TaskTextRead
    data object Rejected : Kvp038TaskTextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-038 graph packet")
abstract class GenerateKvp038TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp038Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-038 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-038 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Exercises the graph-named clean-checkout misuse")
abstract class Kvp038NegativeTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:InputFile abstract val harnessFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val cases = when (val admitted = admitKvp038Cases(canonicalKvp038TaskPacket())) {
            is Kvp038CaseAdmission.Complete -> admitted.cases
            Kvp038CaseAdmission.Rejected -> throw GradleException("KVP-038 cases rejected")
        }
        val result = execOperations.exec {
            executable("bash")
            args(
                harnessFile.get().asFile.absolutePath,
                "--self-test",
                "--evidence", evidenceFile.get().asFile.absolutePath,
            )
            isIgnoreExitValue = true
        }
        val evidence = when (val read = readText038(evidenceFile.path())) {
            is Kvp038TaskTextRead.Complete -> read.raw
            Kvp038TaskTextRead.Rejected -> throw GradleException(
                "KVP-038 named misuse evidence unreadable",
            )
        }
        if (result.exitValue != 0 || evidence != "rejectedFixtureCount=1\n") {
            throw GradleException("KVP-038 named misuse was not rejected")
        }
        val raw = encodeKvp038Negative(cases)
        writeTextAtomically(reportFile.get().asFile.toPath(), raw)
        if (admitKvp038Negative(raw, cases) !is Kvp038NegativeAdmission.Complete) {
            throw GradleException("KVP-038 negative evidence rejected")
        }
        logger.lifecycle(
            "KVP-038 misuse REJECTED: {} ({} exercised fixture)",
            cases.misuseName,
            1,
        )
    }
}

@UntrackedTask(because = "Creates and proves one detached exact-head clean checkout")
abstract class Kvp038AcceptanceTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val harnessFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val result = execOperations.exec {
            workingDir(root.toFile())
            executable("bash")
            args(
                harnessFile.get().asFile.absolutePath,
                "--root", root.toString(),
                "--head", head.value,
                "--evidence", evidenceFile.get().asFile.absolutePath,
            )
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException(
            "KVP-038 detached checkout rejected with status ${result.exitValue}",
        )
        val evidence = when (val read = readText038(evidenceFile.path())) {
            is Kvp038TaskTextRead.Complete -> read.raw
            Kvp038TaskTextRead.Rejected -> throw GradleException(
                "KVP-038 detached evidence unreadable",
            )
        }
        val report = when (val admitted = admitKvp038Evidence(evidence, head)) {
            is Kvp038ReportAdmission.Complete -> admitted.report
            is Kvp038ReportAdmission.Qualified -> throw GradleException(
                "KVP-038 detached checkout qualified instead of completing",
            )
            Kvp038ReportAdmission.Rejected -> throw GradleException(
                "KVP-038 detached evidence rejected",
            )
        }
        writeTextAtomically(reportFile.path(), encodeKvp038Report(report))
        logger.lifecycle("KVP-038 legal path COMPLETE at detached head {}", head.value)
    }
}

@UntrackedTask(because = "Runs KVP-038's detached proof and emits one content receipt")
abstract class ProveKvp038Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp008RedReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp008GreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp008CompletionReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp008ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp036ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp036ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp037ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp037ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val cleanCheckoutReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val paths = Kvp038DependencyPaths(
            kvp008RedReceiptFile.path(), kvp008GreenReceiptFile.path(),
            kvp008CompletionReceiptFile.path(), kvp008ReportFile.path(),
            kvp036ReceiptFile.path(), kvp036ReportFile.path(),
            kvp037ReceiptFile.path(), kvp037ReportFile.path(),
        )
        val context = when (val prepared = prepareKvp038Context(
            execOperations, root, head, packetFile.path(), paths,
            negativeReportFile.path(), cleanCheckoutReportFile.path(),
        )) {
            is Kvp038Preparation.Complete -> prepared.context
            is Kvp038Preparation.Rejected -> throw GradleException(
                "KVP-038 preparation rejected: ${prepared.failure}",
            )
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(cleanCheckoutReportFile.path().toAbsolutePath().normalize())
            .toString()
        if (expected != observed) throw GradleException(
            "KVP-038 output rejected: ${Kvp038ProofFailure.OUTPUT_PATH_REJECTED}",
        )
        val receipt = issueTaskProofReceiptAtBoundary(
            root, head, context.receiptExpectation(), receiptFile.path(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-038 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, receipt={}",
            receipt.digest.value,
        )
    }
}

/**
 * Proof transition: raw packet, predecessor, misuse, and legal evidence -> `Kvp038Preparation`.
 *
 * Establishes one fully admitted KVP-038 capability carrying every proof needed for receipt
 * issuance. Expected packet, closure, scope, input, misuse, and legal-path failures remain closed
 * [Kvp038ProofFailure]; raw path extraction is permitted only at this Gradle task boundary.
 */
internal fun prepareKvp038Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    dependencyPaths: Kvp038DependencyPaths,
    negativePath: Path,
    reportPath: Path,
): Kvp038Preparation {
    val (expected, version) = canonicalKvp038Packet()
    val packetRaw = when (val read = readText038(packetPath)) {
        is Kvp038TaskTextRead.Complete -> read.raw
        Kvp038TaskTextRead.Rejected ->
            return preparationRejected(Kvp038ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp038ProofFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp038Dependencies(
        packet.packet, head, dependencyPaths,
    )) {
        is Kvp038DependencyAdmission.Complete -> admitted.dependencies
        is Kvp038DependencyAdmission.Rejected -> return preparationRejected(
            Kvp038ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp038RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp038RelevantInputAdmission.Complete -> admitted.digest
        is Kvp038RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp038ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp038ImplementationScope(
        exec, root, dependencies.implementationBaseline, head, packet.packet,
    )) {
        is Kvp038ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp038ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp038ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp038Cases(packet.packet)) {
        is Kvp038CaseAdmission.Complete -> admitted.cases
        Kvp038CaseAdmission.Rejected -> return preparationRejected(Kvp038ProofFailure.CASE_REJECTED)
    }
    val negative = when (val read = readText038(negativePath)) {
        is Kvp038TaskTextRead.Complete -> read.raw
        Kvp038TaskTextRead.Rejected ->
            return preparationRejected(Kvp038ProofFailure.NEGATIVE_REJECTED)
    }
    if (admitKvp038Negative(negative, cases) !is Kvp038NegativeAdmission.Complete) {
        return preparationRejected(Kvp038ProofFailure.NEGATIVE_REJECTED)
    }
    val reportRaw = when (val read = readText038(reportPath)) {
        is Kvp038TaskTextRead.Complete -> read.raw
        Kvp038TaskTextRead.Rejected ->
            return preparationRejected(Kvp038ProofFailure.LEGAL_PATH_REJECTED)
    }
    val report = try {
        kotlinx.serialization.json.Json.decodeFromString(
            Kvp038CleanCheckoutDocument.serializer(), reportRaw,
        )
    } catch (_: kotlinx.serialization.SerializationException) {
        return preparationRejected(Kvp038ProofFailure.LEGAL_PATH_REJECTED)
    } catch (_: IllegalArgumentException) {
        return preparationRejected(Kvp038ProofFailure.LEGAL_PATH_REJECTED)
    }
    if (encodeKvp038Report(report) != reportRaw || report.outcome != Kvp038Outcome.COMPLETE ||
        report.repositoryHead != head.value || report.detachedHead != head.value
    ) return preparationRejected(Kvp038ProofFailure.LEGAL_PATH_REJECTED)
    return Kvp038Preparation.Complete(Kvp038ProofContext(
        version, packet, dependencies, relevant, scope, cases, report, reportRaw,
    ))
}

/** Validated canonical graph -> KVP-038 packet and stable proof-program version. */
internal fun canonicalKvp038Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-038"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-038 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp038TaskPacket(): TaskPacket = canonicalKvp038Packet().first

/**
 * Proof transition: `Kvp038ProofContext -> TaskProofReceiptExpectation`.
 *
 * Preserves the admitted packet, dependencies, relevant-input digest, command/toolchain identity,
 * legal observations, and output digest. Refinement failure is rendered only at this Gradle
 * issuance boundary; callers receive only the stronger expectation.
 */
internal fun Kvp038ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    val observations = linkedMapOf(
        "misuseOutcome" to "REJECTED",
        "legalPathOutcome" to "COMPLETE",
        "detachedHeadMatches" to (report.detachedHead == report.repositoryHead).toString(),
        "projectionDiffClean" to report.projectionDiffClean.toString(),
        "structuralGatesPassed" to report.structuralGatesPassed.toString(),
        "hostedAssetsBuilt" to report.hostedAssetsBuilt.toString(),
        "installedAcceptancePassed" to report.installedAcceptancePassed.toString(),
        "currentWorktreeOutputCount" to report.currentWorktreeOutputCount.toString(),
        "reusedGradleCacheCount" to report.reusedGradleCacheCount.toString(),
        "untrackedFixtureCount" to report.untrackedFixtureCount.toString(),
        "implementationCommitCount" to scope.commitCount.toString(),
        "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    )
    return when (val refined = TaskProofReceiptExpectation.refine(
        version.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        dependencies.digests,
        relevantInputDigest.value,
        packet.packet.kvp038CommandDigest().value,
        currentKvp038ToolchainDigest().value,
        observations,
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-038 receipt expectation rejected: ${refined.failure}",
        )
    }
}

private fun readText038(path: Path): Kvp038TaskTextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp038TaskTextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp038TaskTextRead.Rejected
}

private fun preparationRejected(failure: Kvp038ProofFailure) = Kvp038Preparation.Rejected(failure)
private fun RegularFileProperty.path() = get().asFile.toPath()
