package support.delivery

import org.gradle.api.Project

internal fun Project.registerKvp019ReceiptProgression(
    program: DeliveryProgram,
    readEpoch: TaskReceiptRegistration,
    hosted: TaskReceiptRegistration,
    configureHosted: Kvp018ReceiptTaskBase.() -> Unit,
): TaskId {
    val freshness = taskReceiptRegistration(program, TaskId("KVP-019"))
    val contractRoot =
        "workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/"
    val adapterRoot =
        "workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val testRoot =
        "workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/"
    val reportRoot =
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/freshness/"
    val sharedArtifactPaths = listOf(
        "workspace/contract/AGENTS.md",
        contractRoot + "AGENTS.md",
        contractRoot + "VfsPassiveReadCapability.kt",
        "workspace/intellij-read/AGENTS.md",
        adapterRoot + "AGENTS.md",
        adapterRoot + "ExistingProjectAdmission.kt",
        adapterRoot + "VfsPassiveReadAdmission.kt",
        adapterRoot + "epoch/AGENTS.md",
        adapterRoot + "epoch/ProjectReadEpochSourceFactory.kt",
        testRoot + "AGENTS.md",
        testRoot + "ExistingProjectAdmissionFixtures.kt",
        testRoot + "epoch/AGENTS.md",
        testRoot + "epoch/VfsPassiveAdmissionFixtures.kt",
        "workspace/intellij-read/build.gradle.kts",
        "build-logic/src/main/kotlin/support/delivery/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/Kvp015ReceiptRegistration.kt",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/AGENTS.md",
        "build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/" +
            "project/epoch/model/Kvp018ReceiptRegistration.kt",
        reportRoot + "AGENTS.md",
        reportRoot + "Kvp019ReceiptDependencies.kt",
        reportRoot + "Kvp019ReceiptProgression.kt",
        reportRoot + "Kvp019ReceiptRegistration.kt",
        reportRoot + "Kvp019ReceiptTasks.kt",
        reportRoot + "Kvp019ReportTasks.kt",
        reportRoot + "Kvp019VfsPassiveReport.kt",
        "scripts/AGENTS.md",
        "scripts/verify_bundle.py",
        "scripts/verify_kvp019_delivery.py",
    )
    val redArtifactPaths = sharedArtifactPaths +
        (testRoot + "epoch/VfsPassiveAdmissionNegativeTest.kt")
    val greenArtifactPaths = redArtifactPaths +
        (testRoot + "epoch/VfsPassiveAdmissionTest.kt")

    fun Kvp019ReceiptTaskBase.configureFreshness() {
        configureHosted()
        freshnessTaskId.set(freshness.task.id.value)
        freshnessRedGateId.set(freshness.redGate.id)
        freshnessGreenGateId.set(freshness.greenGate.id)
        freshnessCompletionGateId.set(freshness.completionGate.id)
        freshnessRedReceiptId.set(freshness.redGate.outputReceiptId)
        freshnessGreenReceiptId.set(freshness.greenGate.outputReceiptId)
        freshnessCompletionReceiptId.set(freshness.completionGate.outputReceiptId)
        freshnessRedCommand.set(freshness.redGate.command)
        freshnessGreenCommand.set(freshness.greenGate.command)
        freshnessCompletionCommand.set(freshness.completionGate.command)
        freshnessTaskInputDigest.set(freshness.taskInputDigest)
        freshnessCompletionInputDigest.set(freshness.completionInputDigest)
        freshnessProofReportPath.set(freshness.task.outputs.single().path)
        freshnessRedArtifactPaths.set(redArtifactPaths)
        freshnessGreenArtifactPaths.set(greenArtifactPaths)
        freshnessRedArtifactFiles.from(redArtifactPaths.map(layout.projectDirectory::file))
        freshnessGreenArtifactFiles.from(greenArtifactPaths.map(layout.projectDirectory::file))
        directHostedRedReceiptFile.set(hosted.redReceipt)
        directHostedGreenReceiptFile.set(hosted.greenReceipt)
        directHostedProofReportFile.set(hosted.proofReport)
        directHostedCompletionReceiptFile.set(hosted.completionReceipt)
    }

    val recordRed = tasks.register(
        "recordKVP019RedReceipt",
        RecordKvp019RedReceiptTask::class.java,
    ) {
        configureFreshness()
        dependsOn(
            "verifyKVP017CompletionReceipt",
            "verifyKVP018CompletionReceipt",
            ":workspace:intellij-read:verifyVfsPassiveReportNegative",
        )
        receiptFile.set(freshness.redReceipt)
    }
    val recordGreen = tasks.register(
        "recordKVP019GreenReceipt",
        RecordKvp019GreenReceiptTask::class.java,
    ) {
        configureFreshness()
        dependsOn(recordRed, ":workspace:intellij-read:verifyVfsPassiveReportNegative")
        redReceiptFile.set(freshness.redReceipt)
        proofReportFile.set(freshness.proofReport)
        receiptFile.set(freshness.greenReceipt)
    }
    val derive = tasks.register(
        "deriveKVP019Completion",
        DeriveKvp019CompletionReceiptTask::class.java,
    ) {
        configureFreshness()
        dependsOn(recordGreen)
        mustRunAfter(":workspace:intellij-read:generateVfsPassiveReport")
        redReceiptFile.set(freshness.redReceipt)
        greenReceiptFile.set(freshness.greenReceipt)
        proofReportFile.set(freshness.proofReport)
        receiptFile.set(freshness.completionReceipt)
    }
    tasks.register(
        "verifyKVP019CompletionReceipt",
        VerifyKvp019CompletionReceiptTask::class.java,
    ) {
        configureFreshness()
        dependsOn(derive)
        redReceiptFile.set(freshness.redReceipt)
        greenReceiptFile.set(freshness.greenReceipt)
        proofReportFile.set(freshness.proofReport)
        completionReceiptFile.set(freshness.completionReceipt)
    }
    return freshness.task.id
}
