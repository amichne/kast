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

internal enum class Kvp037ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REPORT_REJECTED,
    FAILURE_MATRIX_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal sealed interface Kvp037Preparation {
    data class Complete(val context: Kvp037ProofContext) : Kvp037Preparation
    data class Rejected(val failure: Kvp037ProofFailure) : Kvp037Preparation
}

private sealed interface Kvp037TextRead {
    data class Complete(val raw: String) : Kvp037TextRead
    data object Rejected : Kvp037TextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-037 graph packet")
abstract class GenerateKvp037TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp037Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-037 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-037 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Exercises every graph-owned KVP-037 forbidden-work mutation")
abstract class Kvp037NegativeTask : DefaultTask() {
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val count = canonicalKvp037TaskPacket().task.forbiddenWork.size
        val raw = encodeKvp037Negative(count)
        writeTextAtomically(reportFile.get().asFile.toPath(), raw)
        if (admitKvp037Negative(raw, count) !is Kvp037NegativeAdmission.Complete) {
            throw GradleException("KVP-037 negative evidence rejected")
        }
        logger.lifecycle("KVP-037 rejected all {} failure-matrix misuses", count)
    }
}

@UntrackedTask(because = "Runs the installed KVP-037 failure harness against external state")
abstract class Kvp037AcceptanceTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val harnessFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = observeExactHead(root)
        val result = execOperations.exec {
            workingDir(root.toFile())
            executable("python3")
            args(
                harnessFile.get().asFile.absolutePath,
                "--root", root.toString(),
                "--head", head.value,
                "--report", reportFile.get().asFile.absolutePath,
            )
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException(
            "KVP-037 installed failure matrix rejected with status ${result.exitValue}",
        )
        val raw = readBoundaryFile(reportFile.get().asFile.toPath(), MAX_RECEIPT_EVIDENCE_BYTES)
        if (raw !is BoundaryFileRead.Complete ||
            admitKvp037Report(raw.bytes.toString(Charsets.UTF_8), DeliveryGeneration(head.value)) !is
            Kvp037ReportAdmission.Complete
        ) throw GradleException("KVP-037 installed failure report rejected")
        logger.lifecycle("KVP-037 admitted 9 closed failures and 8 unsupported operations")
    }
}

@UntrackedTask(because = "Runs KVP-037's content proof and emits one receipt")
abstract class ProveKvp037Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp036ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp036ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val failureMatrixFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val evidence = listOf(
            kvp025ReceiptFile.path() to kvp025ReportFile.path(),
            kvp026ReceiptFile.path() to kvp026ReportFile.path(),
            kvp027ReceiptFile.path() to kvp027ReportFile.path(),
            kvp031ReceiptFile.path() to kvp031ReportFile.path(),
            kvp036ReceiptFile.path() to kvp036ReportFile.path(),
        )
        val context = when (val prepared = prepareKvp037Context(
            execOperations,
            root,
            head,
            packetFile.path(),
            evidence,
            negativeReportFile.path(),
            failureMatrixFile.path(),
        )) {
            is Kvp037Preparation.Complete -> prepared.context
            is Kvp037Preparation.Rejected -> throw GradleException(
                "KVP-037 preparation rejected: ${prepared.failure}",
            )
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(failureMatrixFile.path().toAbsolutePath().normalize()).toString()
        if (expected != observed) throw GradleException(
            "KVP-037 output rejected: ${Kvp037ProofFailure.OUTPUT_PATH_REJECTED}",
        )
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(),
            receiptFile.path(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-037 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, failures={}, unsupported={}, receipt={}",
            context.report.failureCases.size,
            context.report.unsupportedOperations.size,
            receipt.digest.value,
        )
    }

    private fun RegularFileProperty.path() = get().asFile.toPath()
}

/** Raw packet/dependencies/reports plus repository observation -> fully admitted KVP-037 context. */
internal fun prepareKvp037Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    dependencyEvidence: List<Pair<Path, Path>>,
    negativeReport: Path,
    failureMatrix: Path,
): Kvp037Preparation {
    val (expected, version) = canonicalKvp037Packet()
    val packetRaw = (readKvp037Text(packetPath) as? Kvp037TextRead.Complete)?.raw
        ?: return preparationRejected(Kvp037ProofFailure.PACKET_REJECTED)
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp037ProofFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp037Dependencies(
        packet.packet,
        head,
        dependencyEvidence,
    )) {
        is Kvp037DependencyAdmission.Complete -> admitted.dependencies
        is Kvp037DependencyAdmission.Rejected -> return preparationRejected(
            Kvp037ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp037RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp037RelevantInputAdmission.Complete -> admitted.digest
        is Kvp037RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp037ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp037ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        head,
        packet.packet,
    )) {
        is Kvp037ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp037ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp037ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp037Cases(packet.packet)) {
        is Kvp037CaseAdmission.Complete -> admitted.cases
        Kvp037CaseAdmission.Rejected -> return preparationRejected(Kvp037ProofFailure.CASE_REJECTED)
    }
    val negativeRaw = (readKvp037Text(negativeReport) as? Kvp037TextRead.Complete)?.raw
        ?: return preparationRejected(Kvp037ProofFailure.NEGATIVE_REPORT_REJECTED)
    if (admitKvp037Negative(negativeRaw, cases.forbiddenWork.size) !is
        Kvp037NegativeAdmission.Complete
    ) return preparationRejected(Kvp037ProofFailure.NEGATIVE_REPORT_REJECTED)
    val reportRaw = (readKvp037Text(failureMatrix) as? Kvp037TextRead.Complete)?.raw
        ?: return preparationRejected(Kvp037ProofFailure.FAILURE_MATRIX_REJECTED)
    val report = when (val admitted = admitKvp037Report(reportRaw, head)) {
        is Kvp037ReportAdmission.Complete -> admitted.report
        is Kvp037ReportAdmission.Qualified,
        Kvp037ReportAdmission.Rejected -> return preparationRejected(
            Kvp037ProofFailure.FAILURE_MATRIX_REJECTED,
        )
    }
    return Kvp037Preparation.Complete(Kvp037ProofContext(
        version,
        packet,
        dependencies,
        relevant,
        scope,
        cases,
        report,
        reportRaw,
    ))
}

/** Validated canonical graph -> KVP-037 packet and stable proof-program version. */
internal fun canonicalKvp037Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-037"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-037 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp037TaskPacket(): TaskPacket = canonicalKvp037Packet().first

private fun readKvp037Text(path: Path): Kvp037TextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp037TextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp037TextRead.Rejected
}

private fun preparationRejected(failure: Kvp037ProofFailure) = Kvp037Preparation.Rejected(failure)
