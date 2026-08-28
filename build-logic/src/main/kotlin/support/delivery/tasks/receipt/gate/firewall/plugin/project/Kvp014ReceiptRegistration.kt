package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp014ReceiptProgression(
    program: DeliveryProgram,
    compatibility: TaskReceiptRegistration,
    configureCompatibility: Kvp012ReceiptTaskBase.() -> Unit,
): Set<TaskId> {
    val projectAdmission = taskReceiptRegistration(program, TaskId("KVP-014"))
    val negativeTestPath =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/" +
            "ExistingProjectAdmissionNegativeTest.kt"
    val positiveTestPath =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/" +
            "ExistingProjectAdmissionTest.kt"
    val moduleBuildPath = "workspace/intellij-read/build.gradle.kts"

    fun Kvp014ReceiptTaskBase.configureProjectAdmission() {
        configureCompatibility()
        projectAdmissionTaskId.set(projectAdmission.task.id.value)
        projectAdmissionRedGateId.set(projectAdmission.redGate.id)
        projectAdmissionGreenGateId.set(projectAdmission.greenGate.id)
        projectAdmissionCompletionGateId.set(projectAdmission.completionGate.id)
        projectAdmissionRedReceiptId.set(projectAdmission.redGate.outputReceiptId)
        projectAdmissionGreenReceiptId.set(projectAdmission.greenGate.outputReceiptId)
        projectAdmissionCompletionReceiptId.set(projectAdmission.completionGate.outputReceiptId)
        projectAdmissionRedCommand.set(projectAdmission.redGate.command)
        projectAdmissionGreenCommand.set(projectAdmission.greenGate.command)
        projectAdmissionCompletionCommand.set(projectAdmission.completionGate.command)
        projectAdmissionTaskInputDigest.set(projectAdmission.taskInputDigest)
        projectAdmissionCompletionInputDigest.set(projectAdmission.completionInputDigest)
        projectAdmissionProofReportPath.set(projectAdmission.task.outputs.single().path)
        projectAdmissionNegativeTestPath.set(negativeTestPath)
        projectAdmissionPositiveTestPath.set(positiveTestPath)
        projectAdmissionModuleBuildPath.set(moduleBuildPath)

        directCompatibilityRedReceiptFile.set(compatibility.redReceipt)
        directCompatibilityGreenReceiptFile.set(compatibility.greenReceipt)
        directCompatibilityProofReportFile.set(compatibility.proofReport)
        directCompatibilityCompletionReceiptFile.set(compatibility.completionReceipt)
        projectAdmissionNegativeTestFile.set(layout.projectDirectory.file(negativeTestPath))
        projectAdmissionPositiveTestFile.set(layout.projectDirectory.file(positiveTestPath))
        projectAdmissionModuleBuildFile.set(layout.projectDirectory.file(moduleBuildPath))
    }

    val recordRed = tasks.register(
        "recordKVP014RedReceipt",
        RecordKvp014RedReceiptTask::class.java,
    ) {
        configureProjectAdmission()
        dependsOn("verifyKVP009CompletionReceipt", "verifyKVP012CompletionReceipt")
        receiptFile.set(projectAdmission.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP014GreenReceipt",
        RecordKvp014GreenReceiptTask::class.java,
    ) {
        configureProjectAdmission()
        dependsOn(recordRed)
        redReceiptFile.set(projectAdmission.redReceipt)
        proofReportFile.set(projectAdmission.proofReport)
        receiptFile.set(projectAdmission.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP014Completion",
        DeriveKvp014CompletionReceiptTask::class.java,
    ) {
        configureProjectAdmission()
        dependsOn(recordGreen)
        mustRunAfter(":workspace:intellij-read:generateExistingProjectAdmissionReport")
        redReceiptFile.set(projectAdmission.redReceipt)
        greenReceiptFile.set(projectAdmission.greenReceipt)
        proofReportFile.set(projectAdmission.proofReport)
        receiptFile.set(projectAdmission.completionReceipt)
    }
    tasks.register(
        "verifyKVP014CompletionReceipt",
        VerifyKvp014CompletionReceiptTask::class.java,
    ) {
        configureProjectAdmission()
        dependsOn(derive)
        redReceiptFile.set(projectAdmission.redReceipt)
        greenReceiptFile.set(projectAdmission.greenReceipt)
        proofReportFile.set(projectAdmission.proofReport)
        completionReceiptFile.set(projectAdmission.completionReceipt)
    }
    val epochTasks = registerKvp015ReceiptProgression(program, projectAdmission) {
        configureProjectAdmission()
    }
    return setOf(projectAdmission.task.id) + epochTasks
}
