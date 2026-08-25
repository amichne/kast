package support.delivery

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

private data class TaskReceiptRegistration(
    val task: TaskNode,
    val redGate: GateNode,
    val greenGate: GateNode,
    val completionGate: GateNode,
    val redReceipt: Provider<RegularFile>,
    val greenReceipt: Provider<RegularFile>,
    val completionReceipt: RegularFile,
    val proofReport: RegularFile,
    val taskInputDigest: String,
    val completionInputDigest: String,
)

/** Registers exact-head typed receipt progression from KVP-001 through KVP-004. */
internal fun Project.registerDeliveryReceiptProgression(): Set<TaskId> {
    val validated = KastVfsPassiveReusedIndexProgram.validated
    val program = validated.program
    val expectedProgramFingerprint = validated.projection()
        .getValue("programFingerprint") as String
    val receiptDirectory = layout.buildDirectory.dir("reports/delivery/receipts")

    fun registration(taskId: String): TaskReceiptRegistration {
        val task = program.tasks.single { it.id.value == taskId }
        val redGate = program.gates.single { it.id == task.red.gateId }
        val greenGate = program.gates.single { it.id == task.green.gateId }
        val completionGate = program.gates.single {
            it.taskId == task.id && it.kind == GateKind.TASK_COMPLETION
        }
        return TaskReceiptRegistration(
            task,
            redGate,
            greenGate,
            completionGate,
            receiptDirectory.map { it.file("${redGate.outputReceiptId}.receipt.json") },
            receiptDirectory.map { it.file("${greenGate.outputReceiptId}.receipt.json") },
            layout.projectDirectory.file(task.completionReceipt.outputPath),
            layout.projectDirectory.file(task.outputs.single().path),
            sha256(canonicalJson(task.inputs)).value,
            sha256(canonicalJson(mapOf(
                "receiptId" to task.completionReceipt.receiptId,
                "requiredGateIds" to task.completionReceipt.requiredGateIds.sorted(),
                "requiredDependencyReceiptIds" to
                    task.completionReceipt.dependencyReceiptIds.sorted(),
            ))).value,
        )
    }

    val authority = registration("KVP-001")
    val typeModel = registration("KVP-002")
    val graph = registration("KVP-003")
    val canonical = registration("KVP-004")
    val authorityNegativeReportPath = "build/reports/delivery/KVP-001-authority-negative.json"
    val authorityVerificationReportPath =
        KastVfsPassiveReusedIndexProgram.authorityVerificationOutputPath.value

    fun Kvp001ReceiptTaskBase.configureAuthority() {
        group = "verification"
        repositoryRootPath.set(layout.projectDirectory.asFile.absolutePath)
        baseRevision.set(program.targetHead)
        programFingerprint.set(expectedProgramFingerprint)
        requirementFingerprint.set(program.requirementFingerprint.value)
        sourceDigests.set(program.sourceDigests.mapValues { it.value.value })
        taskId.set(authority.task.id.value)
        redGateId.set(authority.redGate.id)
        greenGateId.set(authority.greenGate.id)
        completionGateId.set(authority.completionGate.id)
        redReceiptId.set(authority.redGate.outputReceiptId)
        greenReceiptId.set(authority.greenGate.outputReceiptId)
        completionReceiptId.set(authority.completionGate.outputReceiptId)
        redCommand.set(authority.redGate.command)
        greenCommand.set(authority.greenGate.command)
        completionCommand.set(authority.completionGate.command)
        taskInputDigest.set(authority.taskInputDigest)
        completionInputDigest.set(authority.completionInputDigest)
        redProofReportPath.set(authorityNegativeReportPath)
        greenProofReportPath.set(authorityVerificationReportPath)
        redArtifactPaths.set(listOf(authorityNegativeReportPath))
        greenArtifactPaths.set(listOf(
            KastVfsPassiveReusedIndexProgram.authorityLedgerOutputPath.value,
            KastVfsPassiveReusedIndexProgram.authorityContradictionOutputPath.value,
            authorityVerificationReportPath,
        ))
    }

    fun Kvp002ReceiptTaskBase.configureTypeModel() {
        configureAuthority()
        candidateTaskId.set(typeModel.task.id.value)
        candidateRedGateId.set(typeModel.redGate.id)
        candidateGreenGateId.set(typeModel.greenGate.id)
        candidateCompletionGateId.set(typeModel.completionGate.id)
        candidateRedReceiptId.set(typeModel.redGate.outputReceiptId)
        candidateGreenReceiptId.set(typeModel.greenGate.outputReceiptId)
        candidateCompletionReceiptId.set(typeModel.completionGate.outputReceiptId)
        candidateRedCommand.set(typeModel.redGate.command)
        candidateGreenCommand.set(typeModel.greenGate.command)
        candidateCompletionCommand.set(typeModel.completionGate.command)
        candidateTaskInputDigest.set(typeModel.taskInputDigest)
        candidateCompletionInputDigest.set(typeModel.completionInputDigest)
        proofReportPath.set(typeModel.task.outputs.single().path)
        authorityRedReceiptFile.set(authority.redReceipt)
        authorityGreenReceiptFile.set(authority.greenReceipt)
        authorityCompletionReceiptFile.set(authority.completionReceipt)
    }

    fun Kvp003ReceiptTaskBase.configureGraph() {
        configureTypeModel()
        graphTaskId.set(graph.task.id.value)
        graphRedGateId.set(graph.redGate.id)
        graphGreenGateId.set(graph.greenGate.id)
        graphCompletionGateId.set(graph.completionGate.id)
        graphRedReceiptId.set(graph.redGate.outputReceiptId)
        graphGreenReceiptId.set(graph.greenGate.outputReceiptId)
        graphCompletionReceiptId.set(graph.completionGate.outputReceiptId)
        graphRedCommand.set(graph.redGate.command)
        graphGreenCommand.set(graph.greenGate.command)
        graphCompletionCommand.set(graph.completionGate.command)
        graphTaskInputDigest.set(graph.taskInputDigest)
        graphCompletionInputDigest.set(graph.completionInputDigest)
        graphProofReportPath.set(graph.task.outputs.single().path)
        predecessorRedReceiptFile.set(typeModel.redReceipt)
        predecessorGreenReceiptFile.set(typeModel.greenReceipt)
        predecessorProofReportFile.set(typeModel.proofReport)
        predecessorCompletionReceiptFile.set(typeModel.completionReceipt)
    }

    fun Kvp004ReceiptTaskBase.configureCanonicalProgram() {
        configureGraph()
        programTaskId.set(canonical.task.id.value)
        programRedGateId.set(canonical.redGate.id)
        programGreenGateId.set(canonical.greenGate.id)
        programCompletionGateId.set(canonical.completionGate.id)
        programRedReceiptId.set(canonical.redGate.outputReceiptId)
        programGreenReceiptId.set(canonical.greenGate.outputReceiptId)
        programCompletionReceiptId.set(canonical.completionGate.outputReceiptId)
        programRedCommand.set(canonical.redGate.command)
        programGreenCommand.set(canonical.greenGate.command)
        programCompletionCommand.set(canonical.completionGate.command)
        programTaskInputDigest.set(canonical.taskInputDigest)
        programCompletionInputDigest.set(canonical.completionInputDigest)
        programProofReportPath.set(canonical.task.outputs.single().path)
        graphRedReceiptFile.set(graph.redReceipt)
        graphGreenReceiptFile.set(graph.greenReceipt)
        graphProofReportFile.set(graph.proofReport)
        graphCompletionReceiptFile.set(graph.completionReceipt)
    }

    val recordAuthorityRed = tasks.register(
        "recordKVP001RedReceipt",
        RecordKvp001RedReceiptTask::class.java,
    ) {
        configureAuthority()
        dependsOn("verifyKastVfsPassiveAuthorityNegative")
        receiptFile.set(authority.redReceipt)
    }
    val recordAuthorityGreen = tasks.register(
        "recordKVP001GreenReceipt",
        RecordKvp001GreenReceiptTask::class.java,
    ) {
        configureAuthority()
        dependsOn(recordAuthorityRed, "verifyKastVfsPassiveAuthority")
        redReceiptFile.set(authority.redReceipt)
        receiptFile.set(authority.greenReceipt)
    }
    val deriveAuthority = tasks.register(
        "deriveKVP001Completion",
        DeriveKvp001CompletionReceiptTask::class.java,
    ) {
        configureAuthority()
        dependsOn(recordAuthorityRed, recordAuthorityGreen)
        redReceiptFile.set(authority.redReceipt)
        greenReceiptFile.set(authority.greenReceipt)
        receiptFile.set(authority.completionReceipt)
    }
    tasks.register("verifyKVP001CompletionReceipt", VerifyKvp001CompletionReceiptTask::class.java) {
        configureAuthority()
        dependsOn(deriveAuthority)
        redReceiptFile.set(authority.redReceipt)
        greenReceiptFile.set(authority.greenReceipt)
        completionReceiptFile.set(authority.completionReceipt)
    }

    val recordTypeRed = tasks.register("recordKVP002RedReceipt", RecordKvp002RedReceiptTask::class.java) {
        configureTypeModel(); dependsOn("verifyKVP001CompletionReceipt")
        receiptFile.set(typeModel.redReceipt)
    }
    val recordTypeGreen = tasks.register("recordKVP002GreenReceipt", RecordKvp002GreenReceiptTask::class.java) {
        configureTypeModel(); dependsOn(recordTypeRed)
        redReceiptFile.set(typeModel.redReceipt); proofReportFile.set(typeModel.proofReport)
        receiptFile.set(typeModel.greenReceipt)
    }
    val deriveType = tasks.register("deriveKVP002Completion", DeriveKvp002CompletionReceiptTask::class.java) {
        configureTypeModel(); dependsOn(recordTypeGreen)
        redReceiptFile.set(typeModel.redReceipt); greenReceiptFile.set(typeModel.greenReceipt)
        proofReportFile.set(typeModel.proofReport); receiptFile.set(typeModel.completionReceipt)
    }
    tasks.register("verifyKVP002CompletionReceipt", VerifyKvp002CompletionReceiptTask::class.java) {
        configureTypeModel(); dependsOn(deriveType)
        redReceiptFile.set(typeModel.redReceipt); greenReceiptFile.set(typeModel.greenReceipt)
        proofReportFile.set(typeModel.proofReport); completionReceiptFile.set(typeModel.completionReceipt)
    }

    val recordGraphRed = tasks.register("recordKVP003RedReceipt", RecordKvp003RedReceiptTask::class.java) {
        configureGraph(); dependsOn("verifyKVP002CompletionReceipt"); receiptFile.set(graph.redReceipt)
    }
    val recordGraphGreen = tasks.register("recordKVP003GreenReceipt", RecordKvp003GreenReceiptTask::class.java) {
        configureGraph(); dependsOn(recordGraphRed)
        redReceiptFile.set(graph.redReceipt); proofReportFile.set(graph.proofReport)
        receiptFile.set(graph.greenReceipt)
    }
    val deriveGraph = tasks.register("deriveKVP003Completion", DeriveKvp003CompletionReceiptTask::class.java) {
        configureGraph(); dependsOn(recordGraphGreen)
        redReceiptFile.set(graph.redReceipt); greenReceiptFile.set(graph.greenReceipt)
        proofReportFile.set(graph.proofReport); receiptFile.set(graph.completionReceipt)
    }
    tasks.register("verifyKVP003CompletionReceipt", VerifyKvp003CompletionReceiptTask::class.java) {
        configureGraph(); dependsOn(deriveGraph)
        redReceiptFile.set(graph.redReceipt); greenReceiptFile.set(graph.greenReceipt)
        proofReportFile.set(graph.proofReport); completionReceiptFile.set(graph.completionReceipt)
    }

    val recordProgramRed = tasks.register("recordKVP004RedReceipt", RecordKvp004RedReceiptTask::class.java) {
        configureCanonicalProgram(); dependsOn("verifyKVP003CompletionReceipt")
        receiptFile.set(canonical.redReceipt)
    }
    val recordProgramGreen = tasks.register("recordKVP004GreenReceipt", RecordKvp004GreenReceiptTask::class.java) {
        configureCanonicalProgram(); dependsOn(recordProgramRed)
        redReceiptFile.set(canonical.redReceipt); proofReportFile.set(canonical.proofReport)
        receiptFile.set(canonical.greenReceipt)
    }
    val deriveProgram = tasks.register("deriveKVP004Completion", DeriveKvp004CompletionReceiptTask::class.java) {
        configureCanonicalProgram(); dependsOn(recordProgramGreen)
        redReceiptFile.set(canonical.redReceipt); greenReceiptFile.set(canonical.greenReceipt)
        proofReportFile.set(canonical.proofReport); receiptFile.set(canonical.completionReceipt)
    }
    tasks.register("verifyKVP004CompletionReceipt", VerifyKvp004CompletionReceiptTask::class.java) {
        configureCanonicalProgram(); dependsOn(deriveProgram)
        redReceiptFile.set(canonical.redReceipt); greenReceiptFile.set(canonical.greenReceipt)
        proofReportFile.set(canonical.proofReport); completionReceiptFile.set(canonical.completionReceipt)
    }
    return setOf(authority.task.id, typeModel.task.id, graph.task.id, canonical.task.id)
}
