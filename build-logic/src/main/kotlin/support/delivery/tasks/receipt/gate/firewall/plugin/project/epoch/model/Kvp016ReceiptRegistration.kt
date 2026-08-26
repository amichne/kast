package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp016ReceiptProgression(
    program: DeliveryProgram,
    epoch: TaskReceiptRegistration,
    configureEpoch: Kvp015ReceiptTaskBase.() -> Unit,
): TaskId {
    val detached = taskReceiptRegistration(program, TaskId("KVP-016"))
    val mainRoot =
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val testRoot =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/" +
            "detached/"
    val detachedModelPath = mainRoot + "DetachedIdeWorkspaceModel.kt"
    val refinementPath = mainRoot + "DetachedIdeWorkspaceModelRefinement.kt"
    val valueRefinementPath = mainRoot + "DetachedModelValueRefinement.kt"
    val capturePath = mainRoot + "DetachedModelCapture.kt"
    val existingProjectAdmissionPath = mainRoot + "ExistingProjectAdmission.kt"
    val liveCapturePath = mainRoot + "LiveDetachedModelCapture.kt"
    val negativeTestPath = testRoot + "DetachedModelNegativeTest.kt"
    val positiveTestPath = testRoot + "DetachedModelTest.kt"
    val fixturesPath = testRoot + "DetachedModelFixtures.kt"
    val classContractPath = testRoot + "DetachedModelClassContract.kt"
    val classpathUrlContractPath = testRoot + "DetachedClasspathUrlRefinementTest.kt"
    val moduleBuildPath = "workspace/intellij-read/build.gradle.kts"

    fun Kvp016ReceiptTaskBase.configureDetached() {
        configureEpoch()
        detachedTaskId.set(detached.task.id.value)
        detachedRedGateId.set(detached.redGate.id)
        detachedGreenGateId.set(detached.greenGate.id)
        detachedCompletionGateId.set(detached.completionGate.id)
        detachedRedReceiptId.set(detached.redGate.outputReceiptId)
        detachedGreenReceiptId.set(detached.greenGate.outputReceiptId)
        detachedCompletionReceiptId.set(detached.completionGate.outputReceiptId)
        detachedRedCommand.set(detached.redGate.command)
        detachedGreenCommand.set(detached.greenGate.command)
        detachedCompletionCommand.set(detached.completionGate.command)
        detachedTaskInputDigest.set(detached.taskInputDigest)
        detachedCompletionInputDigest.set(detached.completionInputDigest)
        detachedProofReportPath.set(detached.task.outputs.single().path)
        this.detachedModelPath.set(detachedModelPath)
        detachedRefinementPath.set(refinementPath)
        detachedValueRefinementPath.set(valueRefinementPath)
        detachedCapturePath.set(capturePath)
        detachedExistingProjectAdmissionPath.set(existingProjectAdmissionPath)
        detachedLiveCapturePath.set(liveCapturePath)
        detachedNegativeTestPath.set(negativeTestPath)
        detachedPositiveTestPath.set(positiveTestPath)
        detachedFixturesPath.set(fixturesPath)
        detachedClassContractPath.set(classContractPath)
        detachedClasspathUrlContractPath.set(classpathUrlContractPath)
        detachedModuleBuildPath.set(moduleBuildPath)

        directEpochRedReceiptFile.set(epoch.redReceipt)
        directEpochGreenReceiptFile.set(epoch.greenReceipt)
        directEpochProofReportFile.set(epoch.proofReport)
        directEpochCompletionReceiptFile.set(epoch.completionReceipt)
        detachedModelFile.set(layout.projectDirectory.file(detachedModelPath))
        detachedRefinementFile.set(layout.projectDirectory.file(refinementPath))
        detachedValueRefinementFile.set(layout.projectDirectory.file(valueRefinementPath))
        detachedCaptureFile.set(layout.projectDirectory.file(capturePath))
        detachedExistingProjectAdmissionFile.set(
            layout.projectDirectory.file(existingProjectAdmissionPath),
        )
        detachedLiveCaptureFile.set(layout.projectDirectory.file(liveCapturePath))
        detachedNegativeTestFile.set(layout.projectDirectory.file(negativeTestPath))
        detachedPositiveTestFile.set(layout.projectDirectory.file(positiveTestPath))
        detachedFixturesFile.set(layout.projectDirectory.file(fixturesPath))
        detachedClassContractFile.set(layout.projectDirectory.file(classContractPath))
        detachedClasspathUrlContractFile.set(layout.projectDirectory.file(classpathUrlContractPath))
        detachedModuleBuildFile.set(layout.projectDirectory.file(moduleBuildPath))
    }

    val recordRed = tasks.register(
        "recordKVP016RedReceipt",
        RecordKvp016RedReceiptTask::class.java,
    ) {
        configureDetached()
        dependsOn("verifyKVP014CompletionReceipt", "verifyKVP015CompletionReceipt")
        receiptFile.set(detached.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP016GreenReceipt",
        RecordKvp016GreenReceiptTask::class.java,
    ) {
        configureDetached()
        dependsOn(recordRed)
        redReceiptFile.set(detached.redReceipt)
        proofReportFile.set(detached.proofReport)
        receiptFile.set(detached.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP016Completion",
        DeriveKvp016CompletionReceiptTask::class.java,
    ) {
        configureDetached()
        dependsOn(recordGreen)
        redReceiptFile.set(detached.redReceipt)
        greenReceiptFile.set(detached.greenReceipt)
        proofReportFile.set(detached.proofReport)
        receiptFile.set(detached.completionReceipt)
    }
    tasks.register(
        "verifyKVP016CompletionReceipt",
        VerifyKvp016CompletionReceiptTask::class.java,
    ) {
        configureDetached()
        dependsOn(derive)
        redReceiptFile.set(detached.redReceipt)
        greenReceiptFile.set(detached.greenReceipt)
        proofReportFile.set(detached.proofReport)
        completionReceiptFile.set(detached.completionReceipt)
    }
    return detached.task.id
}
