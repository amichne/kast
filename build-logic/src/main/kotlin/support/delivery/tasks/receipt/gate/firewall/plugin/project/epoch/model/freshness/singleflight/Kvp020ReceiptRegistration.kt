package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp020ReceiptProgression(
    program: DeliveryProgram,
    projectAdmission: TaskReceiptRegistration,
    freshness: TaskReceiptRegistration,
    configureFreshness: Kvp019ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val singleFlight = taskReceiptRegistration(program, TaskId("KVP-020"))
    val mainRoot =
        "runtime/ide-read/src/main/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val testRoot =
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/"
    val reportRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/singleflight/"
    val sharedArtifactPaths = listOf(
        "AGENTS.md",
        "settings.gradle.kts",
        "runtime/ide-read/AGENTS.md",
        "runtime/ide-read/build.gradle.kts",
        mainRoot + "AGENTS.md",
        mainRoot + "ProjectReadOutcomes.kt",
        mainRoot + "ProjectReadPermit.kt",
        mainRoot + "ProjectReadSingleFlight.kt",
        testRoot + "AGENTS.md",
        testRoot + "SingleFlightFixtures.kt",
        testRoot + "SingleFlightReportFixture.kt",
        testRoot + "SingleFlightTransitionEvidence.kt",
        "build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt",
        "build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt",
        "build-logic/src/test/kotlin/support/architecture/policy/KastCleanSlatePolicyTest.kt",
        "gradle/architecture/kast-architecture-policy.json",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/Kvp019ReceiptRegistration.kt",
        reportRoot + "AGENTS.md",
        reportRoot + "Kvp020ReceiptDependencies.kt",
        reportRoot + "Kvp020ReceiptProgression.kt",
        reportRoot + "Kvp020ReceiptRegistration.kt",
        reportRoot + "Kvp020ReceiptTasks.kt",
        reportRoot + "Kvp020ReportTasks.kt",
        reportRoot + "Kvp020SingleFlightReport.kt",
        reportRoot + "Kvp020SingleFlightTransitions.kt",
        "gradle/delivery/kast-vfs-passive-reused-index-program.json",
        "gradle/delivery/kast-vfs-passive-requirements.json",
        "docs/kast-vfs-passive-reused-index-delivery-program.md",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp020_delivery.py",
    )
    val redArtifactPaths = sharedArtifactPaths +
        (testRoot + "SingleFlightNegativeTest.kt")
    val greenArtifactPaths = redArtifactPaths +
        (testRoot + "SingleFlightTest.kt")

    fun Kvp020ReceiptTaskBase.configureSingleFlight() {
        configureFreshness()
        singleFlightTaskId.set(singleFlight.task.id.value)
        singleFlightRedGateId.set(singleFlight.redGate.id)
        singleFlightGreenGateId.set(singleFlight.greenGate.id)
        singleFlightCompletionGateId.set(singleFlight.completionGate.id)
        singleFlightRedReceiptId.set(singleFlight.redGate.outputReceiptId)
        singleFlightGreenReceiptId.set(singleFlight.greenGate.outputReceiptId)
        singleFlightCompletionReceiptId.set(singleFlight.completionGate.outputReceiptId)
        singleFlightRedCommand.set(singleFlight.redGate.command)
        singleFlightGreenCommand.set(singleFlight.greenGate.command)
        singleFlightCompletionCommand.set(singleFlight.completionGate.command)
        singleFlightTaskInputDigest.set(singleFlight.taskInputDigest)
        singleFlightCompletionInputDigest.set(singleFlight.completionInputDigest)
        singleFlightProofReportPath.set(singleFlight.task.outputs.single().path)
        singleFlightRedArtifactPaths.set(redArtifactPaths)
        singleFlightGreenArtifactPaths.set(greenArtifactPaths)
        singleFlightRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        singleFlightGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        directProjectRedReceiptFile.set(projectAdmission.redReceipt)
        directProjectGreenReceiptFile.set(projectAdmission.greenReceipt)
        directProjectProofReportFile.set(projectAdmission.proofReport)
        directProjectCompletionReceiptFile.set(projectAdmission.completionReceipt)
        directFreshnessRedReceiptFile.set(freshness.redReceipt)
        directFreshnessGreenReceiptFile.set(freshness.greenReceipt)
        directFreshnessProofReportFile.set(freshness.proofReport)
        directFreshnessCompletionReceiptFile.set(freshness.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP020RedReceipt",
        RecordKvp020RedReceiptTask::class.java,
    ) {
        configureSingleFlight()
        dependsOn(
            "verifyKVP014CompletionReceipt",
            "verifyKVP019CompletionReceipt",
            ":runtime:ide-read:verifySingleFlightReportNegative",
        )
        receiptFile.set(singleFlight.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP020GreenReceipt",
        RecordKvp020GreenReceiptTask::class.java,
    ) {
        configureSingleFlight()
        dependsOn(recordRed, ":runtime:ide-read:verifySingleFlightReportNegative")
        redReceiptFile.set(singleFlight.redReceipt)
        proofReportFile.set(singleFlight.proofReport)
        receiptFile.set(singleFlight.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP020Completion",
        DeriveKvp020CompletionReceiptTask::class.java,
    ) {
        configureSingleFlight()
        dependsOn(recordGreen)
        mustRunAfter(":runtime:ide-read:generateSingleFlightReport")
        redReceiptFile.set(singleFlight.redReceipt)
        greenReceiptFile.set(singleFlight.greenReceipt)
        proofReportFile.set(singleFlight.proofReport)
        receiptFile.set(singleFlight.completionReceipt)
    }
    tasks.register(
        "verifyKVP020CompletionReceipt",
        VerifyKvp020CompletionReceiptTask::class.java,
    ) {
        configureSingleFlight()
        dependsOn(derive)
        redReceiptFile.set(singleFlight.redReceipt)
        greenReceiptFile.set(singleFlight.greenReceipt)
        proofReportFile.set(singleFlight.proofReport)
        completionReceiptFile.set(singleFlight.completionReceipt)
    }
    val cancellableTasks = registerKvp021ReceiptProgression(
        program,
        freshness,
        singleFlight,
    ) {
        configureSingleFlight()
    }
    return setOf(singleFlight.task.id) + cancellableTasks
}
