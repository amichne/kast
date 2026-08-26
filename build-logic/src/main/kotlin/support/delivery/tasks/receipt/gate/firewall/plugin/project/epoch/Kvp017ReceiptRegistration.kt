package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp017ReceiptProgression(
    program: DeliveryProgram,
    signalLedger: TaskReceiptRegistration,
    configureSignalLedger: Kvp015ReceiptTaskBase.() -> Unit,
): TaskId {
    val readEpoch = taskReceiptRegistration(program, TaskId("KVP-017"))
    val contractMainRoot =
        "workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/"
    val contractTestRoot =
        "workspace/contract/src/test/kotlin/io/github/amichne/kast/workspace/contract/"
    val adapterMainRoot =
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val adapterTestRoot =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val adapterEpochMainRoot = adapterMainRoot + "epoch/"
    val adapterEpochTestRoot = adapterTestRoot + "epoch/"
    val contractEpochPath = contractMainRoot + "epoch/ProjectReadEpoch.kt"
    val contractNegativeTestPath = contractTestRoot + "ProjectReadEpochNegativeTest.kt"
    val contractPositiveTestPath = contractTestRoot + "ProjectReadEpochTest.kt"
    val observationPath = adapterEpochMainRoot + "ProjectReadEpochObservation.kt"
    val liveObservationPath = adapterEpochMainRoot + "LiveProjectReadEpochObservation.kt"
    val existingProjectAdmissionPath = adapterMainRoot + "ExistingProjectAdmission.kt"
    val adapterPositiveTestPath = adapterTestRoot + "EpochSignalCharacterizationTest.kt"
    val signalFixturePath = adapterTestRoot + "EpochSignalFixtures.kt"
    val signalApiContractPath = adapterTestRoot + "EpochSignalApiContract.kt"
    val signalClassContractPath = adapterEpochTestRoot + "EpochSignalClassContract.kt"
    val additionalArtifactPaths = listOf(
        contractMainRoot + "epoch/SemanticReadLease.kt",
        adapterEpochMainRoot + "ProjectReadEpochIdentity.kt",
        adapterEpochMainRoot + "ProjectReadEpochVfsListener.kt",
        adapterEpochTestRoot + "ProjectReadEpochIdentityTest.kt",
        adapterEpochTestRoot + "ProjectReadEpochDetachmentTest.kt",
        adapterEpochTestRoot + "EpochSignalProductionResources.kt",
        adapterTestRoot + "ExistingProjectAdmissionFixtures.kt",
        adapterTestRoot + "ExistingProjectAdmissionNegativeTest.kt",
        adapterTestRoot + "ExistingProjectAdmissionTest.kt",
        "workspace/intellij-read/src/test/resources/KVP-017-read-epoch.expected.json",
        "docs/engineering/ide-project-read-epoch.md",
        "scripts/verify_kvp017_report.py",
    )
    val contractBuildPath = "workspace/contract/build.gradle.kts"
    val adapterBuildPath = "workspace/intellij-read/build.gradle.kts"

    fun Kvp017ReceiptTaskBase.configureReadEpoch() {
        configureSignalLedger()
        readEpochTaskId.set(readEpoch.task.id.value)
        readEpochRedGateId.set(readEpoch.redGate.id)
        readEpochGreenGateId.set(readEpoch.greenGate.id)
        readEpochCompletionGateId.set(readEpoch.completionGate.id)
        readEpochRedReceiptId.set(readEpoch.redGate.outputReceiptId)
        readEpochGreenReceiptId.set(readEpoch.greenGate.outputReceiptId)
        readEpochCompletionReceiptId.set(readEpoch.completionGate.outputReceiptId)
        readEpochRedCommand.set(readEpoch.redGate.command)
        readEpochGreenCommand.set(readEpoch.greenGate.command)
        readEpochCompletionCommand.set(readEpoch.completionGate.command)
        readEpochTaskInputDigest.set(readEpoch.taskInputDigest)
        readEpochCompletionInputDigest.set(readEpoch.completionInputDigest)
        readEpochProofReportPath.set(readEpoch.task.outputs.single().path)
        readEpochContractPath.set(contractEpochPath)
        readEpochContractNegativeTestPath.set(contractNegativeTestPath)
        readEpochContractPositiveTestPath.set(contractPositiveTestPath)
        readEpochObservationPath.set(observationPath)
        readEpochLiveObservationPath.set(liveObservationPath)
        readEpochExistingProjectAdmissionPath.set(existingProjectAdmissionPath)
        readEpochAdapterPositiveTestPath.set(adapterPositiveTestPath)
        readEpochSignalFixturePath.set(signalFixturePath)
        readEpochSignalApiContractPath.set(signalApiContractPath)
        readEpochSignalClassContractPath.set(signalClassContractPath)
        readEpochAdditionalArtifactPaths.set(additionalArtifactPaths)
        readEpochContractBuildPath.set(contractBuildPath)
        readEpochAdapterBuildPath.set(adapterBuildPath)

        directSignalLedgerRedReceiptFile.set(signalLedger.redReceipt)
        directSignalLedgerGreenReceiptFile.set(signalLedger.greenReceipt)
        directSignalLedgerProofReportFile.set(signalLedger.proofReport)
        directSignalLedgerCompletionReceiptFile.set(signalLedger.completionReceipt)
        readEpochContractFile.set(layout.projectDirectory.file(contractEpochPath))
        readEpochContractNegativeTestFile.set(
            layout.projectDirectory.file(contractNegativeTestPath),
        )
        readEpochContractPositiveTestFile.set(
            layout.projectDirectory.file(contractPositiveTestPath),
        )
        readEpochObservationFile.set(layout.projectDirectory.file(observationPath))
        readEpochLiveObservationFile.set(layout.projectDirectory.file(liveObservationPath))
        readEpochExistingProjectAdmissionFile.set(
            layout.projectDirectory.file(existingProjectAdmissionPath),
        )
        readEpochAdapterPositiveTestFile.set(
            layout.projectDirectory.file(adapterPositiveTestPath),
        )
        readEpochSignalFixtureFile.set(layout.projectDirectory.file(signalFixturePath))
        readEpochSignalApiContractFile.set(layout.projectDirectory.file(signalApiContractPath))
        readEpochSignalClassContractFile.set(layout.projectDirectory.file(signalClassContractPath))
        readEpochAdditionalArtifactFiles.from(
            additionalArtifactPaths.map(layout.projectDirectory::file),
        )
        readEpochContractBuildFile.set(layout.projectDirectory.file(contractBuildPath))
        readEpochAdapterBuildFile.set(layout.projectDirectory.file(adapterBuildPath))
    }

    val recordRed = tasks.register(
        "recordKVP017RedReceipt",
        RecordKvp017RedReceiptTask::class.java,
    ) {
        configureReadEpoch()
        dependsOn("verifyKVP015CompletionReceipt")
        receiptFile.set(readEpoch.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP017GreenReceipt",
        RecordKvp017GreenReceiptTask::class.java,
    ) {
        configureReadEpoch()
        dependsOn(recordRed)
        redReceiptFile.set(readEpoch.redReceipt)
        proofReportFile.set(readEpoch.proofReport)
        receiptFile.set(readEpoch.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP017Completion",
        DeriveKvp017CompletionReceiptTask::class.java,
    ) {
        configureReadEpoch()
        dependsOn(recordGreen)
        redReceiptFile.set(readEpoch.redReceipt)
        greenReceiptFile.set(readEpoch.greenReceipt)
        proofReportFile.set(readEpoch.proofReport)
        receiptFile.set(readEpoch.completionReceipt)
    }
    tasks.register(
        "verifyKVP017CompletionReceipt",
        VerifyKvp017CompletionReceiptTask::class.java,
    ) {
        configureReadEpoch()
        dependsOn(derive)
        redReceiptFile.set(readEpoch.redReceipt)
        greenReceiptFile.set(readEpoch.greenReceipt)
        proofReportFile.set(readEpoch.proofReport)
        completionReceiptFile.set(readEpoch.completionReceipt)
    }
    return readEpoch.task.id
}
