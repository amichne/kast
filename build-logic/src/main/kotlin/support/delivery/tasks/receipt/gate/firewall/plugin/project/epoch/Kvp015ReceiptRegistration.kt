package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp015ReceiptProgression(
    program: DeliveryProgram,
    projectAdmission: TaskReceiptRegistration,
    configureProjectAdmission: Kvp014ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val epoch = taskReceiptRegistration(program, TaskId("KVP-015"))
    val testRoot =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val negativeTestPath = testRoot + "EpochSignalCharacterizationNegativeTest.kt"
    val positiveTestPath = testRoot + "EpochSignalCharacterizationTest.kt"
    val apiContractPath = testRoot + "EpochSignalApiContract.kt"
    val classContractPath = testRoot + "epoch/EpochSignalClassContract.kt"
    val fixturePath = testRoot + "EpochSignalFixtures.kt"
    val moduleBuildPath = "workspace/intellij-read/build.gradle.kts"
    val engineeringLedgerPath = "docs/engineering/ide-read-epoch-ledger.md"

    fun Kvp015ReceiptTaskBase.configureEpoch() {
        configureProjectAdmission()
        epochTaskId.set(epoch.task.id.value)
        epochRedGateId.set(epoch.redGate.id)
        epochGreenGateId.set(epoch.greenGate.id)
        epochCompletionGateId.set(epoch.completionGate.id)
        epochRedReceiptId.set(epoch.redGate.outputReceiptId)
        epochGreenReceiptId.set(epoch.greenGate.outputReceiptId)
        epochCompletionReceiptId.set(epoch.completionGate.outputReceiptId)
        epochRedCommand.set(epoch.redGate.command)
        epochGreenCommand.set(epoch.greenGate.command)
        epochCompletionCommand.set(epoch.completionGate.command)
        epochTaskInputDigest.set(epoch.taskInputDigest)
        epochCompletionInputDigest.set(epoch.completionInputDigest)
        epochProofReportPath.set(epoch.task.outputs.single().path)
        epochNegativeTestPath.set(negativeTestPath)
        epochPositiveTestPath.set(positiveTestPath)
        epochApiContractPath.set(apiContractPath)
        epochClassContractPath.set(classContractPath)
        epochFixturePath.set(fixturePath)
        epochModuleBuildPath.set(moduleBuildPath)
        epochEngineeringLedgerPath.set(engineeringLedgerPath)

        directProjectRedReceiptFile.set(projectAdmission.redReceipt)
        directProjectGreenReceiptFile.set(projectAdmission.greenReceipt)
        directProjectProofReportFile.set(projectAdmission.proofReport)
        directProjectCompletionReceiptFile.set(projectAdmission.completionReceipt)
        epochNegativeTestFile.set(layout.projectDirectory.file(negativeTestPath))
        epochPositiveTestFile.set(layout.projectDirectory.file(positiveTestPath))
        epochApiContractFile.set(layout.projectDirectory.file(apiContractPath))
        epochClassContractFile.set(layout.projectDirectory.file(classContractPath))
        epochFixtureFile.set(layout.projectDirectory.file(fixturePath))
        epochModuleBuildFile.set(layout.projectDirectory.file(moduleBuildPath))
        epochEngineeringLedgerFile.set(layout.projectDirectory.file(engineeringLedgerPath))
    }

    val recordRed = tasks.register(
        "recordKVP015RedReceipt",
        RecordKvp015RedReceiptTask::class.java,
    ) {
        configureEpoch()
        dependsOn("verifyKVP014CompletionReceipt")
        receiptFile.set(epoch.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP015GreenReceipt",
        RecordKvp015GreenReceiptTask::class.java,
    ) {
        configureEpoch()
        dependsOn(recordRed)
        redReceiptFile.set(epoch.redReceipt)
        proofReportFile.set(epoch.proofReport)
        receiptFile.set(epoch.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP015Completion",
        DeriveKvp015CompletionReceiptTask::class.java,
    ) {
        configureEpoch()
        dependsOn(recordGreen)
        redReceiptFile.set(epoch.redReceipt)
        greenReceiptFile.set(epoch.greenReceipt)
        proofReportFile.set(epoch.proofReport)
        receiptFile.set(epoch.completionReceipt)
    }
    tasks.register(
        "verifyKVP015CompletionReceipt",
        VerifyKvp015CompletionReceiptTask::class.java,
    ) {
        configureEpoch()
        dependsOn(derive)
        redReceiptFile.set(epoch.redReceipt)
        greenReceiptFile.set(epoch.greenReceipt)
        proofReportFile.set(epoch.proofReport)
        completionReceiptFile.set(epoch.completionReceipt)
    }
    val detached = registerKvp016ReceiptProgression(program, epoch) {
        configureEpoch()
    }
    val readEpoch = registerKvp017ReceiptProgression(program, epoch) {
        configureEpoch()
    }
    val hostedTasks = registerKvp018ReceiptProgression(
        program,
        epoch,
        taskReceiptRegistration(program, TaskId("KVP-016")),
        taskReceiptRegistration(program, TaskId("KVP-017")),
    ) {
        configureEpoch()
    }
    return setOf(epoch.task.id, detached, readEpoch) + hostedTasks
}
