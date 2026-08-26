package support.delivery

import org.gradle.api.Project

/** Registers exact-head typed receipt progression for KVP-001 through KVP-010 and KVP-012/013. */
internal fun Project.registerDeliveryReceiptProgression(): Set<TaskId> {
    val validated = KastVfsPassiveReusedIndexProgram.validated
    val program = validated.program
    val expectedProgramFingerprint = validated.projection()
        .getValue("programFingerprint") as String
    val authority = taskReceiptRegistration(program, TaskId("KVP-001"))
    val typeModel = taskReceiptRegistration(program, TaskId("KVP-002"))
    val graph = taskReceiptRegistration(program, TaskId("KVP-003"))
    val canonical = taskReceiptRegistration(program, TaskId("KVP-004"))
    val projection = taskReceiptRegistration(program, TaskId("KVP-005"))
    val gateGraph = taskReceiptRegistration(program, TaskId("KVP-006"))
    val deliveryProof = taskReceiptRegistration(program, TaskId("KVP-007"))
    val deliveryState = taskReceiptRegistration(program, TaskId("KVP-008"))
    val firewall = taskReceiptRegistration(program, TaskId("KVP-009"))
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

    fun Kvp005ReceiptTaskBase.configureProjection() {
        configureCanonicalProgram()
        projectionTaskId.set(projection.task.id.value)
        projectionRedGateId.set(projection.redGate.id)
        projectionGreenGateId.set(projection.greenGate.id)
        projectionCompletionGateId.set(projection.completionGate.id)
        projectionRedReceiptId.set(projection.redGate.outputReceiptId)
        projectionGreenReceiptId.set(projection.greenGate.outputReceiptId)
        projectionCompletionReceiptId.set(projection.completionGate.outputReceiptId)
        projectionRedCommand.set(projection.redGate.command)
        projectionGreenCommand.set(projection.greenGate.command)
        projectionCompletionCommand.set(projection.completionGate.command)
        projectionTaskInputDigest.set(projection.taskInputDigest)
        projectionCompletionInputDigest.set(projection.completionInputDigest)
        projectionProofReportPath.set(projection.task.outputs.single().path)
        programRedReceiptFile.set(canonical.redReceipt)
        programGreenReceiptFile.set(canonical.greenReceipt)
        programProofReportFile.set(canonical.proofReport)
        programCompletionReceiptFile.set(canonical.completionReceipt)
    }

    fun Kvp006ReceiptTaskBase.configureGateGraph() {
        configureProjection()
        gateGraphTaskId.set(gateGraph.task.id.value)
        gateGraphRedGateId.set(gateGraph.redGate.id)
        gateGraphGreenGateId.set(gateGraph.greenGate.id)
        gateGraphCompletionGateId.set(gateGraph.completionGate.id)
        gateGraphRedReceiptId.set(gateGraph.redGate.outputReceiptId)
        gateGraphGreenReceiptId.set(gateGraph.greenGate.outputReceiptId)
        gateGraphCompletionReceiptId.set(gateGraph.completionGate.outputReceiptId)
        gateGraphRedCommand.set(gateGraph.redGate.command)
        gateGraphGreenCommand.set(gateGraph.greenGate.command)
        gateGraphCompletionCommand.set(gateGraph.completionGate.command)
        gateGraphTaskInputDigest.set(gateGraph.taskInputDigest)
        gateGraphCompletionInputDigest.set(gateGraph.completionInputDigest)
        gateGraphNegativeReportPath.set("build/reports/delivery/KVP-006-gradle-gates-negative.json")
        gateGraphProofReportPath.set(gateGraph.task.outputs.single().path)
        projectionRedReceiptFile.set(projection.redReceipt)
        projectionGreenReceiptFile.set(projection.greenReceipt)
        projectionProofReportFile.set(projection.proofReport)
        projectionCompletionReceiptFile.set(projection.completionReceipt)
    }

    fun Kvp007ReceiptTaskBase.configureDeliveryProof() {
        configureGateGraph()
        deliveryProofTaskId.set(deliveryProof.task.id.value)
        deliveryProofRedGateId.set(deliveryProof.redGate.id)
        deliveryProofGreenGateId.set(deliveryProof.greenGate.id)
        deliveryProofCompletionGateId.set(deliveryProof.completionGate.id)
        deliveryProofRedReceiptId.set(deliveryProof.redGate.outputReceiptId)
        deliveryProofGreenReceiptId.set(deliveryProof.greenGate.outputReceiptId)
        deliveryProofCompletionReceiptId.set(deliveryProof.completionGate.outputReceiptId)
        deliveryProofRedCommand.set(deliveryProof.redGate.command)
        deliveryProofGreenCommand.set(deliveryProof.greenGate.command)
        deliveryProofCompletionCommand.set(deliveryProof.completionGate.command)
        deliveryProofTaskInputDigest.set(deliveryProof.taskInputDigest)
        deliveryProofCompletionInputDigest.set(deliveryProof.completionInputDigest)
        deliveryProofReportPath.set(deliveryProof.task.outputs.single().path)
        gateGraphRedReceiptFile.set(gateGraph.redReceipt)
        gateGraphGreenReceiptFile.set(gateGraph.greenReceipt)
        gateGraphProofReportFile.set(gateGraph.proofReport)
        gateGraphCompletionReceiptFile.set(gateGraph.completionReceipt)
    }

    fun Kvp008ReceiptTaskBase.configureDeliveryState() {
        configureDeliveryProof()
        deliveryStateTaskId.set(deliveryState.task.id.value)
        deliveryStateRedGateId.set(deliveryState.redGate.id)
        deliveryStateGreenGateId.set(deliveryState.greenGate.id)
        deliveryStateCompletionGateId.set(deliveryState.completionGate.id)
        deliveryStateRedReceiptId.set(deliveryState.redGate.outputReceiptId)
        deliveryStateGreenReceiptId.set(deliveryState.greenGate.outputReceiptId)
        deliveryStateCompletionReceiptId.set(deliveryState.completionGate.outputReceiptId)
        deliveryStateRedCommand.set(deliveryState.redGate.command)
        deliveryStateGreenCommand.set(deliveryState.greenGate.command)
        deliveryStateCompletionCommand.set(deliveryState.completionGate.command)
        deliveryStateTaskInputDigest.set(deliveryState.taskInputDigest)
        deliveryStateCompletionInputDigest.set(deliveryState.completionInputDigest)
        deliveryStateProofReportPath.set(deliveryState.task.outputs.single().path)
        deliveryProofRedReceiptFile.set(deliveryProof.redReceipt)
        deliveryProofGreenReceiptFile.set(deliveryProof.greenReceipt)
        deliveryProofReportFile.set(deliveryProof.proofReport)
        deliveryProofCompletionReceiptFile.set(deliveryProof.completionReceipt)
    }

    fun Kvp009ReceiptTaskBase.configureFirewall() {
        configureGateGraph()
        firewallTaskId.set(firewall.task.id.value)
        firewallRedGateId.set(firewall.redGate.id)
        firewallGreenGateId.set(firewall.greenGate.id)
        firewallCompletionGateId.set(firewall.completionGate.id)
        firewallRedReceiptId.set(firewall.redGate.outputReceiptId)
        firewallGreenReceiptId.set(firewall.greenGate.outputReceiptId)
        firewallCompletionReceiptId.set(firewall.completionGate.outputReceiptId)
        firewallRedCommand.set(firewall.redGate.command)
        firewallGreenCommand.set(firewall.greenGate.command)
        firewallCompletionCommand.set(firewall.completionGate.command)
        firewallTaskInputDigest.set(firewall.taskInputDigest)
        firewallCompletionInputDigest.set(firewall.completionInputDigest)
        firewallProofReportPath.set(firewall.task.outputs.single().path)
        directGateGraphRedReceiptFile.set(gateGraph.redReceipt)
        directGateGraphGreenReceiptFile.set(gateGraph.greenReceipt)
        directGateGraphProofReportFile.set(gateGraph.proofReport)
        directGateGraphCompletionReceiptFile.set(gateGraph.completionReceipt)
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

    val recordProjectionRed = tasks.register(
        "recordKVP005RedReceipt",
        RecordKvp005RedReceiptTask::class.java,
    ) {
        configureProjection(); dependsOn("verifyKVP004CompletionReceipt")
        receiptFile.set(projection.redReceipt)
    }
    val recordProjectionGreen = tasks.register(
        "recordKVP005GreenReceipt",
        RecordKvp005GreenReceiptTask::class.java,
    ) {
        configureProjection(); dependsOn(recordProjectionRed)
        redReceiptFile.set(projection.redReceipt); proofReportFile.set(projection.proofReport)
        receiptFile.set(projection.greenReceipt)
    }
    val deriveProjection = tasks.register(
        "deriveKVP005Completion",
        DeriveKvp005CompletionReceiptTask::class.java,
    ) {
        configureProjection(); dependsOn(recordProjectionGreen)
        redReceiptFile.set(projection.redReceipt); greenReceiptFile.set(projection.greenReceipt)
        proofReportFile.set(projection.proofReport); receiptFile.set(projection.completionReceipt)
    }
    tasks.register("verifyKVP005CompletionReceipt", VerifyKvp005CompletionReceiptTask::class.java) {
        configureProjection(); dependsOn(deriveProjection)
        redReceiptFile.set(projection.redReceipt); greenReceiptFile.set(projection.greenReceipt)
        proofReportFile.set(projection.proofReport)
        completionReceiptFile.set(projection.completionReceipt)
    }

    val recordGateGraphRed = tasks.register(
        "recordKVP006RedReceipt",
        RecordKvp006RedReceiptTask::class.java,
    ) {
        configureGateGraph()
        dependsOn(
            "verifyKVP003CompletionReceipt",
            "verifyKVP005CompletionReceipt",
            "verifyKastVfsPassiveGateGraphNegative",
        )
        receiptFile.set(gateGraph.redReceipt)
    }
    val recordGateGraphGreen = tasks.register(
        "recordKVP006GreenReceipt",
        RecordKvp006GreenReceiptTask::class.java,
    ) {
        configureGateGraph(); dependsOn(recordGateGraphRed, "verifyKastVfsPassiveGateGraph")
        redReceiptFile.set(gateGraph.redReceipt); proofReportFile.set(gateGraph.proofReport)
        receiptFile.set(gateGraph.greenReceipt)
    }
    val deriveGateGraph = tasks.register(
        "deriveKVP006Completion",
        DeriveKvp006CompletionReceiptTask::class.java,
    ) {
        configureGateGraph(); dependsOn(recordGateGraphGreen)
        redReceiptFile.set(gateGraph.redReceipt); greenReceiptFile.set(gateGraph.greenReceipt)
        proofReportFile.set(gateGraph.proofReport); receiptFile.set(gateGraph.completionReceipt)
    }
    tasks.register("verifyKVP006CompletionReceipt", VerifyKvp006CompletionReceiptTask::class.java) {
        configureGateGraph(); dependsOn(deriveGateGraph)
        redReceiptFile.set(gateGraph.redReceipt); greenReceiptFile.set(gateGraph.greenReceipt)
        proofReportFile.set(gateGraph.proofReport)
        completionReceiptFile.set(gateGraph.completionReceipt)
    }
    registerKvp007ReceiptProgression(deliveryProof) { configureDeliveryProof() }
    registerKvp008ReceiptProgression(deliveryState) { configureDeliveryState() }
    registerKvp009ReceiptProgression(firewall) { configureFirewall() }
    val pluginTasks = registerKvp012ReceiptProgression(program, typeModel, firewall) {
        configureFirewall()
    }
    return setOf(
        authority.task.id,
        typeModel.task.id,
        graph.task.id,
        canonical.task.id,
        projection.task.id,
        gateGraph.task.id,
        deliveryProof.task.id,
        deliveryState.task.id,
        firewall.task.id,
    ) + pluginTasks
}
